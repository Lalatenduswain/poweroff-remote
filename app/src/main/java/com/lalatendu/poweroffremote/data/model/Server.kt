package com.lalatendu.poweroffremote.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

enum class AuthMethod { PASSWORD, PRIVATE_KEY }

/** How the app should reach a machine that is currently powered off. */
enum class WakeMethod {
    /** Broadcast a Wake-on-LAN magic packet from the phone. Only works on the same L2 network. */
    BROADCAST,

    /** Ask another always-on saved server (over SSH) to emit the magic packet. Works remotely. */
    GATEWAY,

    /** Machine cannot be woken by this app. */
    NONE
}

@Serializable
data class Server(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val port: Int = 22,
    val username: String = "root",

    val authMethod: AuthMethod = AuthMethod.PASSWORD,
    val password: String = "",
    val privateKeyPem: String = "",
    val privateKeyPassphrase: String = "",

    /** Run the power commands through sudo. Ignored when username is root. */
    val useSudo: Boolean = true,

    /** Left blank when sudo is configured NOPASSWD. */
    val sudoPassword: String = "",

    val shutdownCommand: String = DEFAULT_SHUTDOWN,
    val rebootCommand: String = DEFAULT_REBOOT,

    val wakeMethod: WakeMethod = WakeMethod.BROADCAST,
    val macAddress: String = "",
    /** Optional explicit broadcast address, e.g. 192.168.1.255. Blank = every interface broadcast. */
    val wolBroadcast: String = "",
    val wolPort: Int = 9,
    /** Optional SecureOn password, 6 hex bytes, e.g. 11-22-33-44-55-66. */
    val wolSecureOn: String = "",
    /** Id of the saved server used to relay the magic packet when wakeMethod is GATEWAY. */
    val wakeGatewayId: String = "",

    val connectTimeoutSec: Int = 12,

    /** Trust-on-first-use record: "SHA256:base64" of the host key we accepted. */
    val hostKeyFingerprint: String = "",

    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
) {
    val effectiveUseSudo: Boolean get() = useSudo && username.trim() != "root"

    val displayTarget: String get() = "$username@$host:$port"

    fun validate(): String? = when {
        name.isBlank() -> "Give the server a name"
        host.isBlank() -> "Host or IP is required"
        port !in 1..65535 -> "Port must be between 1 and 65535"
        username.isBlank() -> "Username is required"
        authMethod == AuthMethod.PASSWORD && password.isEmpty() -> "Password is required"
        authMethod == AuthMethod.PRIVATE_KEY && privateKeyPem.isBlank() -> "Private key is required"
        wakeMethod == WakeMethod.BROADCAST && !isValidMac(macAddress) ->
            "A valid MAC address is required for Wake-on-LAN"
        wakeMethod == WakeMethod.GATEWAY && !isValidMac(macAddress) ->
            "A valid MAC address is required for Wake-on-LAN"
        wakeMethod == WakeMethod.GATEWAY && wakeGatewayId.isBlank() ->
            "Pick the server that will relay the magic packet"
        wolPort !in 1..65535 -> "Wake-on-LAN port must be between 1 and 65535"
        wolSecureOn.isNotBlank() && !isValidMac(wolSecureOn) ->
            "SecureOn password must be 6 hex bytes, e.g. 11-22-33-44-55-66"
        else -> null
    }

    companion object {
        const val DEFAULT_SHUTDOWN = "shutdown -h now"
        const val DEFAULT_REBOOT = "shutdown -r now"

        private val MAC_RE = Regex("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$")

        fun isValidMac(mac: String): Boolean = MAC_RE.matches(mac.trim())

        /** Accepts "aabbccddeeff", "aa:bb:...", "aa-bb-..." and returns the 6 raw bytes. */
        fun macBytes(mac: String): ByteArray? {
            val hex = mac.trim().replace(":", "").replace("-", "").replace(".", "")
            if (hex.length != 12 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
            return ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
    }
}
