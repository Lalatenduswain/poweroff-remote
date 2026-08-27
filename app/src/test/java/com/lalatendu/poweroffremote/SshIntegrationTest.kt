package com.lalatendu.poweroffremote

import com.lalatendu.poweroffremote.data.model.AuthMethod
import com.lalatendu.poweroffremote.data.model.Server
import com.lalatendu.poweroffremote.net.SshClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Drives the real SSH client against a real sshd. Skipped unless a host is supplied:
 *
 *   ./gradlew :app:testDebugUnitTest -Ppoweroff.itHost=127.0.0.1 \
 *     -Ppoweroff.itUser=$USER -Ppoweroff.itKey=$HOME/.ssh/id_rsa
 *
 * Nothing here shuts anything down — the power-off path is covered by simulating a channel that
 * closes without an exit status, which is what sshd does when the machine goes away underneath it.
 */
class SshIntegrationTest {

    private lateinit var base: Server

    @Before
    fun setUp() {
        val host = System.getProperty("poweroff.itHost")
        assumeTrue("no poweroff.itHost supplied; skipping SSH integration tests", host != null)

        val keyPath = System.getProperty("poweroff.itKey")
        val password = System.getProperty("poweroff.itPassword").orEmpty()

        base = Server(
            name = "integration",
            host = host!!,
            port = System.getProperty("poweroff.itPort")?.toIntOrNull() ?: 22,
            username = System.getProperty("poweroff.itUser") ?: System.getProperty("user.name"),
            authMethod = if (keyPath != null) AuthMethod.PRIVATE_KEY else AuthMethod.PASSWORD,
            privateKeyPem = keyPath?.let { File(it).readText() }.orEmpty(),
            password = password,
            connectTimeoutSec = 10,
        )
    }

    @Test
    fun `probe authenticates and reports the remote identity`() {
        val outcome = runBlocking { SshClient.probe(base) }
        assertTrue("probe failed: ${outcome.error} / ${outcome.combinedOutput}", outcome.ok)
        assertEquals(0, outcome.exitCode)
        assertTrue(outcome.stdout.contains("@"))
        assertNotNull(outcome.hostKeyFingerprint)
        assertTrue(outcome.hostKeyFingerprint!!.startsWith("SHA256:"))

        // Optional cross-check against what OpenSSH itself prints, so the pinned string stays
        // something a human can compare with `ssh-keygen -lf`:
        //   -Ppoweroff.itFingerprint="$(ssh-keyscan host 2>/dev/null | ssh-keygen -lf - | awk '{print $2}' | paste -sd,)"
        val expected = System.getProperty("poweroff.itFingerprint")
        if (!expected.isNullOrBlank()) {
            val accepted = expected.split(",").map { it.trim() }
            assertTrue(
                "app computed ${outcome.hostKeyFingerprint}, ssh-keygen reports $accepted",
                outcome.hostKeyFingerprint in accepted,
            )
        }
    }

    @Test
    fun `a non zero exit status is surfaced, not swallowed`() {
        val outcome = runBlocking { SshClient.exec(base, "exit 7") }
        assertFalse(outcome.ok)
        assertEquals(7, outcome.exitCode)
        assertTrue(outcome.error!!.contains("7"))
    }

    @Test
    fun `stdout and stderr are captured separately`() {
        val outcome = runBlocking { SshClient.exec(base, "echo out; echo err >&2") }
        assertTrue(outcome.ok)
        assertTrue(outcome.stdout.contains("out"))
        assertTrue(outcome.stderr.contains("err"))
        // stderr is shown first, because that is where sudo complains.
        assertTrue(outcome.combinedOutput.startsWith("err"))
    }

    /** This is the mechanism that feeds a stored sudo password to `sudo -S`. */
    @Test
    fun `stdin reaches the remote command`() {
        val outcome = runBlocking { SshClient.exec(base, "cat", stdin = "swordfish\n") }
        assertTrue("exec failed: ${outcome.error}", outcome.ok)
        assertEquals("swordfish", outcome.stdout.trim())
    }

    @Test
    fun `a pinned host key that matches is accepted`() {
        val fingerprint = runBlocking { SshClient.probe(base) }.hostKeyFingerprint
        assertNotNull(fingerprint)

        val pinned = base.copy(hostKeyFingerprint = fingerprint!!)
        val outcome = runBlocking { SshClient.exec(pinned, "true") }
        assertTrue("pinned connect failed: ${outcome.error}", outcome.ok)
        assertFalse(outcome.hostKeyChanged)
    }

    @Test
    fun `a pinned host key that does not match aborts before credentials are sent`() {
        val wrongPin = base.copy(
            hostKeyFingerprint = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            // Deliberately broken credentials: if the key check did not run first this would
            // fail as an auth error instead.
            authMethod = AuthMethod.PASSWORD,
            password = "definitely-not-the-password",
            privateKeyPem = "",
        )
        val outcome = runBlocking { SshClient.exec(wrongPin, "true") }

        assertFalse(outcome.ok)
        assertTrue("expected a host key rejection, got: ${outcome.error}", outcome.hostKeyChanged)
        assertTrue(outcome.error!!.contains("Host key mismatch"))
        assertFalse("must not look like an auth failure", outcome.error!!.contains("Authentication"))
    }

    /**
     * sshd sends exit-signal rather than exit-status when the command dies by signal, so
     * getExitStatus() stays -1. That is exactly what a real `shutdown -h now` looks like.
     */
    @Test
    fun `a command that dies without an exit status counts as a successful power off`() {
        val command = "sh -c 'kill -9 \$\$'"

        val asPowerOff = runBlocking { SshClient.exec(base, command, expectDisconnect = true) }
        assertEquals(-1, asPowerOff.exitCode)
        assertTrue("power off should tolerate a missing exit status", asPowerOff.ok)

        val asPlainCommand = runBlocking { SshClient.exec(base, command) }
        assertEquals(-1, asPlainCommand.exitCode)
        assertFalse("a normal command must not be excused the same way", asPlainCommand.ok)
    }

    /** Regression test: a refused sudo used to be reported as a successful power off. */
    @Test
    fun `a refusal on stderr is not excused even when the exit status is missing`() {
        val outcome = runBlocking {
            SshClient.exec(
                base,
                "sh -c 'echo \"sudo: a password is required\" >&2; kill -9 \$\$'",
                expectDisconnect = true,
            )
        }
        assertEquals(-1, outcome.exitCode)
        assertFalse("a refused sudo must never read as a successful shutdown", outcome.ok)
        assertTrue(outcome.combinedOutput.contains("password is required"))
    }

    @Test
    fun `a bad host fails with a useful message rather than a stack trace`() {
        val unreachable = base.copy(host = "203.0.113.1", connectTimeoutSec = 3)
        val outcome = runBlocking { SshClient.exec(unreachable, "true") }
        assertFalse(outcome.ok)
        assertNotNull(outcome.error)
        assertFalse(outcome.error!!.contains("Exception"))
    }
}
