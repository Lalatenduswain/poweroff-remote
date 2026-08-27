package com.lalatendu.poweroffremote.net

import android.util.Base64
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import com.lalatendu.poweroffremote.data.model.AuthMethod
import com.lalatendu.poweroffremote.data.model.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

data class SshOutcome(
    val ok: Boolean,
    val exitCode: Int = -1,
    val stdout: String = "",
    val stderr: String = "",
    val error: String? = null,
    /** Fingerprint of the key the server presented, in OpenSSH "SHA256:..." form. */
    val hostKeyFingerprint: String? = null,
    /** True when the server presented a key different from the one previously trusted. */
    val hostKeyChanged: Boolean = false,
) {
    /** stderr first — that is where sudo and shutdown put anything worth reading. */
    val combinedOutput: String
        get() = listOf(stderr.trim(), stdout.trim()).filter { it.isNotEmpty() }.joinToString("\n")
}

object SshClient {

    init {
        // Widen the defaults of the mwiede/jsch fork just enough to talk to older sshd builds
        // that still only offer ssh-rsa. Everything weaker stays disabled.
        runCatching {
            listOf("server_host_key", "PubkeyAcceptedAlgorithms").forEach { key ->
                val current = JSch.getConfig(key).orEmpty()
                if (!current.contains("ssh-rsa")) JSch.setConfig(key, "$current,ssh-rsa")
            }
        }
    }

    /**
     * Opens one session, runs [command], and returns its output.
     *
     * @param stdin written to the remote command's standard input (used to feed `sudo -S`).
     * @param expectDisconnect set for shutdown/reboot, where the link dropping mid-command is
     *   the expected outcome rather than a failure.
     */
    suspend fun exec(
        server: Server,
        command: String,
        stdin: String? = null,
        expectDisconnect: Boolean = false,
    ): SshOutcome = withContext(Dispatchers.IO) {
        val hostKeys = TofuHostKeyRepository(server.hostKeyFingerprint)
        var session: Session? = null
        try {
            session = openSession(server, hostKeys)

            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(command)

            val stdoutSink = ByteArrayOutputStream()
            val stderrSink = ByteArrayOutputStream()
            channel.setOutputStream(stdoutSink)
            channel.setErrStream(stderrSink)
            // Must be obtained before connect(): this is the pipe into the remote command's stdin.
            val remoteStdin = channel.outputStream

            channel.connect(server.connectTimeoutSec * 1000)

            if (stdin != null) remoteStdin.write(stdin.toByteArray())
            runCatching { remoteStdin.flush(); remoteStdin.close() }

            val deadlineMs = System.currentTimeMillis() + commandTimeoutMs(server)
            var droppedEarly = false
            while (!channel.isClosed) {
                if (System.currentTimeMillis() > deadlineMs) break
                if (!session.isConnected) { droppedEarly = true; break }
                Thread.sleep(60)
            }

            // Read the channel state before disconnecting it, or every case looks "closed".
            val closedCleanly = channel.isClosed
            val exit = channel.exitStatus
            val stdout = stdoutSink.toString("UTF-8")
            val stderr = stderrSink.toString("UTF-8")
            runCatching { channel.disconnect() }

            val timedOut = !closedCleanly && !droppedEarly
            val ok = when {
                exit == 0 -> true
                // sshd often dies before it can send an exit status for a shutdown, so no status
                // is the expected result there — unless the output says the command was refused.
                expectDisconnect && exit == -1 && !looksLikeRefusal(stderr) -> true
                else -> false
            }

            SshOutcome(
                ok = ok,
                exitCode = exit,
                stdout = stdout,
                stderr = stderr,
                error = when {
                    ok -> null
                    exit == -1 && timedOut -> "Command timed out after ${commandTimeoutMs(server) / 1000}s"
                    exit == -1 && droppedEarly -> "Connection dropped before the command reported a result"
                    exit == -1 -> "The command was refused before it could run"
                    else -> "Command exited with status $exit"
                },
                hostKeyFingerprint = hostKeys.presentedFingerprint,
            )
        } catch (e: Exception) {
            SshOutcome(
                ok = false,
                error = describe(e, hostKeys),
                hostKeyFingerprint = hostKeys.presentedFingerprint,
                hostKeyChanged = hostKeys.sawChangedKey,
            )
        } finally {
            runCatching { session?.disconnect() }
        }
    }

    /** Authenticates and reports who/where we landed, without changing anything on the server. */
    suspend fun probe(server: Server): SshOutcome =
        exec(server, "echo \"\$(id -un)@\$(hostname) \$(uname -sr)\"; uptime")

    private fun openSession(server: Server, hostKeys: TofuHostKeyRepository): Session {
        val jsch = JSch()
        jsch.hostKeyRepository = hostKeys

        if (server.authMethod == AuthMethod.PRIVATE_KEY) {
            jsch.addIdentity(
                "key-${server.id}",
                server.privateKeyPem.trim().plus("\n").toByteArray(),
                null,
                server.privateKeyPassphrase.takeIf { it.isNotEmpty() }?.toByteArray(),
            )
        }

        val session = jsch.getSession(server.username.trim(), server.host.trim(), server.port)
        session.setDaemonThread(true)
        session.setConfig("StrictHostKeyChecking", "ask")
        session.setConfig(
            "PreferredAuthentications",
            if (server.authMethod == AuthMethod.PRIVATE_KEY) "publickey"
            else "password,keyboard-interactive",
        )
        if (server.authMethod == AuthMethod.PASSWORD) session.setPassword(server.password.toByteArray())
        session.userInfo = CredentialAnswers(server)
        session.connect(server.connectTimeoutSec * 1000)
        return session
    }

    /**
     * A shutdown that never reports a status is normal; one that was rejected still says so on
     * stderr first. Without this check a hung `sudo` would be reported as a successful power off.
     */
    private fun looksLikeRefusal(stderr: String): Boolean {
        val text = stderr.lowercase()
        return listOf(
            "sudo:", "password", "permission denied", "not permitted", "must be root",
            "command not found", "no such file", "not in the sudoers", "operation not permitted",
            "authentication failure", "access denied",
        ).any { text.contains(it) }
    }

    private fun commandTimeoutMs(server: Server): Long =
        maxOf(20_000L, server.connectTimeoutSec * 2_000L)

    private fun describe(e: Exception, hostKeys: TofuHostKeyRepository): String {
        if (hostKeys.sawChangedKey) {
            return "Host key mismatch. The server presented " +
                "${hostKeys.presentedFingerprint} but this app trusts " +
                "${hostKeys.trustedFingerprint}. Either the server was rebuilt, or something is " +
                "intercepting the connection. Nothing was sent."
        }
        val raw = e.message.orEmpty()
        return when {
            raw.contains("Auth fail", true) || raw.contains("Auth cancel", true) ->
                "Authentication failed — check the username, password or key"
            raw.contains("USERAUTH fail", true) ->
                "Authentication failed — the key was rejected by the server"
            raw.contains("invalid privatekey", true) || raw.contains("not a valid", true) ->
                "That private key could not be parsed. Export it in OpenSSH or PEM format."
            raw.contains("timeout", true) || raw.contains("timed out", true) ->
                "Timed out reaching the host — it may be off, asleep or unreachable"
            raw.contains("Connection refused", true) ->
                "Connection refused — is sshd listening on that port?"
            raw.contains("UnknownHost", true) || e is java.net.UnknownHostException ->
                "Host not found — check the address"
            raw.isBlank() -> e.javaClass.simpleName
            else -> raw
        }
    }

    /**
     * Trust on first use. An unknown key is accepted once and pinned; a key that later changes
     * aborts the handshake *before* any credential is sent.
     */
    private class TofuHostKeyRepository(val trustedFingerprint: String) : HostKeyRepository {

        var presentedFingerprint: String? = null
            private set
        var sawChangedKey: Boolean = false
            private set

        override fun check(host: String, key: ByteArray): Int {
            val fingerprint = fingerprintOf(key)
            presentedFingerprint = fingerprint
            return when {
                trustedFingerprint.isBlank() -> HostKeyRepository.NOT_INCLUDED
                trustedFingerprint == fingerprint -> HostKeyRepository.OK
                else -> { sawChangedKey = true; HostKeyRepository.CHANGED }
            }
        }

        // The caller persists presentedFingerprint after a successful connect, so nothing to do.
        override fun add(hostkey: HostKey, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID(): String = "poweroff-remote-tofu"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    /** Feeds stored credentials to JSch and refuses any host key that changed. */
    private class CredentialAnswers(private val server: Server) : UserInfo, UIKeyboardInteractive {

        override fun getPassword(): String = server.password
        override fun getPassphrase(): String = server.privateKeyPassphrase
        override fun promptPassword(message: String?): Boolean = server.password.isNotEmpty()
        override fun promptPassphrase(message: String?): Boolean = true
        override fun showMessage(message: String?) = Unit

        /** Yes to an unknown host (first use), never to one whose key changed. */
        override fun promptYesNo(message: String?): Boolean =
            message?.contains("changed", ignoreCase = true) != true

        override fun promptKeyboardInteractive(
            destination: String?,
            name: String?,
            instruction: String?,
            prompt: Array<out String>?,
            echo: BooleanArray?,
        ): Array<String>? {
            if (prompt == null || prompt.size != 1 || echo?.getOrNull(0) == true) return null
            if (server.password.isEmpty()) return null
            return arrayOf(server.password)
        }
    }

    /** OpenSSH-style "SHA256:<base64 without padding>". */
    fun fingerprintOf(key: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key)
        return "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
