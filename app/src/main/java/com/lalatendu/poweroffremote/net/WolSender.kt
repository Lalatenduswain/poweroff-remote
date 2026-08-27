package com.lalatendu.poweroffremote.net

import com.lalatendu.poweroffremote.data.model.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

data class WolOutcome(
    val ok: Boolean,
    val targets: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * Sends a Wake-on-LAN magic packet from the phone.
 *
 * This only reaches the machine when the phone is on the same broadcast domain — a magic packet
 * cannot be routed. Over Tailscale, a VPN, or mobile data, use the SSH gateway relay instead.
 */
object WolSender {

    suspend fun send(server: Server): WolOutcome = withContext(Dispatchers.IO) {
        val packet = buildMagicPacket(server.macAddress, server.wolSecureOn)
            ?: return@withContext WolOutcome(false, error = "Invalid MAC address")

        val targets = resolveTargets(server.wolBroadcast)
        if (targets.isEmpty()) {
            return@withContext WolOutcome(
                false,
                error = "No broadcast address available — is the phone on Wi-Fi?"
            )
        }

        val reached = mutableListOf<String>()
        var lastError: String? = null
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                targets.forEach { target ->
                    try {
                        socket.send(
                            DatagramPacket(
                                packet,
                                packet.size,
                                InetAddress.getByName(target),
                                server.wolPort,
                            )
                        )
                        reached += "$target:${server.wolPort}"
                    } catch (e: Exception) {
                        lastError = e.message ?: e.javaClass.simpleName
                    }
                }
            }
        } catch (e: Exception) {
            return@withContext WolOutcome(false, error = e.message ?: e.javaClass.simpleName)
        }

        if (reached.isEmpty()) WolOutcome(false, error = lastError ?: "Could not send the packet")
        else WolOutcome(true, reached)
    }

    /** 6 x 0xFF, then the MAC repeated 16 times, then the optional 6-byte SecureOn password. */
    fun buildMagicPacket(mac: String, secureOn: String = ""): ByteArray? {
        val macBytes = Server.macBytes(mac) ?: return null
        val secureBytes = if (secureOn.isBlank()) null else Server.macBytes(secureOn)
        if (secureOn.isNotBlank() && secureBytes == null) return null

        val body = ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { macBytes[it % 6] }
        return if (secureBytes == null) body else body + secureBytes
    }

    /** The explicit broadcast if one is configured, otherwise every interface broadcast we can find. */
    private fun resolveTargets(configuredBroadcast: String): List<String> {
        if (configuredBroadcast.isNotBlank()) return listOf(configuredBroadcast.trim())

        val found = linkedSetOf<String>()
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .mapNotNull { it.broadcast?.hostAddress }
                .forEach { found += it }
        }
        found += "255.255.255.255"
        return found.toList()
    }
}
