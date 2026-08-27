package com.lalatendu.poweroffremote

import com.lalatendu.poweroffremote.data.model.Server
import com.lalatendu.poweroffremote.net.WolSender
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * Exercises the real socket path rather than just the packet builder: binds a UDP listener and
 * checks that what lands on the wire is a well formed magic packet.
 */
class WolLoopbackTest {

    @Test
    fun `the packet that reaches the wire is a valid magic packet`() {
        DatagramSocket(0).use { listener ->
            listener.soTimeout = 4000
            val port = listener.localPort

            val server = Server(
                name = "loopback",
                host = "127.0.0.1",
                macAddress = "aa:bb:cc:dd:ee:ff",
                wolBroadcast = "127.0.0.1",
                wolPort = port,
            )

            val outcome = runBlocking { WolSender.send(server) }
            assertTrue("send reported: ${outcome.error}", outcome.ok)
            assertEquals(listOf("127.0.0.1:$port"), outcome.targets)

            val buffer = ByteArray(256)
            val received = DatagramPacket(buffer, buffer.size)
            listener.receive(received)

            assertEquals(102, received.length)
            repeat(6) { assertEquals(0xFF.toByte(), buffer[it]) }
            assertEquals(0xAA.toByte(), buffer[6])
            assertEquals(0xFF.toByte(), buffer[11])
            // Last of the sixteen repeats.
            assertEquals(0xAA.toByte(), buffer[96])
            assertEquals(0xFF.toByte(), buffer[101])
        }
    }

    @Test
    fun `secure on bytes reach the wire too`() {
        DatagramSocket(0).use { listener ->
            listener.soTimeout = 4000
            val server = Server(
                name = "loopback",
                host = "127.0.0.1",
                macAddress = "aa:bb:cc:dd:ee:ff",
                wolBroadcast = "127.0.0.1",
                wolPort = listener.localPort,
                wolSecureOn = "11-22-33-44-55-66",
            )

            assertTrue(runBlocking { WolSender.send(server) }.ok)

            val buffer = ByteArray(256)
            val received = DatagramPacket(buffer, buffer.size)
            listener.receive(received)

            assertEquals(108, received.length)
            assertEquals(0x11.toByte(), buffer[102])
            assertEquals(0x66.toByte(), buffer[107])
        }
    }
}
