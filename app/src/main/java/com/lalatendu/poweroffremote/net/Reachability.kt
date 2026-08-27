package com.lalatendu.poweroffremote.net

import com.lalatendu.poweroffremote.data.model.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

data class ReachOutcome(val up: Boolean, val latencyMs: Long, val detail: String)

/** Cheap "is it alive?" probe: open a TCP connection to the SSH port and drop it. */
object Reachability {

    suspend fun check(server: Server, timeoutMs: Int = 4000): ReachOutcome =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            try {
                Socket().use { socket ->
                    socket.connect(
                        InetSocketAddress(server.host.trim(), server.port),
                        timeoutMs,
                    )
                }
                val elapsed = System.currentTimeMillis() - started
                ReachOutcome(true, elapsed, "Port ${server.port} answered in ${elapsed} ms")
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - started
                val reason = when (e) {
                    is java.net.SocketTimeoutException -> "no answer within ${timeoutMs} ms"
                    is java.net.UnknownHostException -> "host name did not resolve"
                    else -> e.message ?: e.javaClass.simpleName
                }
                ReachOutcome(false, elapsed, "Port ${server.port} unreachable — $reason")
            }
        }
}
