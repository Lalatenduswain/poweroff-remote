package com.lalatendu.poweroffremote

import com.lalatendu.poweroffremote.data.model.AuthMethod
import com.lalatendu.poweroffremote.data.model.Server
import com.lalatendu.poweroffremote.data.model.WakeMethod
import com.lalatendu.poweroffremote.domain.PowerController
import com.lalatendu.poweroffremote.net.WolSender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MagicPacketTest {

    @Test
    fun `packet is 102 bytes of sync stream plus sixteen mac repeats`() {
        val packet = WolSender.buildMagicPacket("aa:bb:cc:dd:ee:ff")
        assertNotNull(packet)
        packet!!
        assertEquals(102, packet.size)
        repeat(6) { assertEquals(0xFF.toByte(), packet[it]) }
        val expected = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte())
        for (repeat in 0 until 16) {
            for (i in 0 until 6) {
                assertEquals(expected[i], packet[6 + repeat * 6 + i])
            }
        }
    }

    @Test
    fun `secure on password is appended`() {
        val packet = WolSender.buildMagicPacket("aa-bb-cc-dd-ee-ff", "11:22:33:44:55:66")
        assertEquals(108, packet!!.size)
        assertEquals(0x11.toByte(), packet[102])
        assertEquals(0x66.toByte(), packet[107])
    }

    @Test
    fun `separator style does not matter but length does`() {
        assertNotNull(WolSender.buildMagicPacket("AABBCCDDEEFF"))
        assertNull(WolSender.buildMagicPacket("aa:bb:cc:dd:ee"))
        assertNull(WolSender.buildMagicPacket("zz:bb:cc:dd:ee:ff"))
    }
}

class GatewayCommandTest {

    @Test
    fun `command falls through wakeonlan then python then etherwake`() {
        val command = PowerController.gatewayWakeCommand(
            mac = "aa:bb:cc:dd:ee:ff",
            broadcast = "192.168.1.255",
            port = 9,
        )
        assertTrue(command.contains("command -v wakeonlan"))
        assertTrue(command.contains("command -v python3"))
        assertTrue(command.contains("command -v etherwake"))
        assertTrue(command.contains("192.168.1.255"))
        assertTrue(command.startsWith("if "))
        assertTrue(command.endsWith("fi"))
    }

    @Test
    fun `wakeonlan is skipped when a secure on password is set`() {
        val command = PowerController.gatewayWakeCommand(
            mac = "aa:bb:cc:dd:ee:ff",
            broadcast = "255.255.255.255",
            port = 9,
            secureOn = "11-22-33-44-55-66",
        )
        assertFalse(command.contains("command -v wakeonlan"))
        assertTrue(command.contains("python3 -c"))
    }

    @Test
    fun `shell metacharacters are stripped before the string reaches a remote shell`() {
        val command = PowerController.gatewayWakeCommand(
            mac = "aa:bb:cc:dd:ee:ff",
            broadcast = "10.0.0.255'; rm -rf / #",
            port = 9,
        )
        // The whole payload collapses into one inert token inside the quoted argument.
        assertTrue(command.contains("'10.0.0.255rm-rf'"))
        assertFalse(command.contains("rm -rf"))
        assertFalse(command.contains("/ #"))
    }
}

class ServerValidationTest {

    private val base = Server(
        name = "lab",
        host = "192.168.1.200",
        username = "lala",
        authMethod = AuthMethod.PASSWORD,
        password = "secret",
        wakeMethod = WakeMethod.NONE,
    )

    @Test
    fun `a complete password server validates`() {
        assertNull(base.validate())
    }

    @Test
    fun `wake on lan needs a mac address`() {
        assertNotNull(base.copy(wakeMethod = WakeMethod.BROADCAST).validate())
        assertNull(base.copy(wakeMethod = WakeMethod.BROADCAST, macAddress = "aa:bb:cc:dd:ee:ff").validate())
    }

    @Test
    fun `gateway wake needs a relay server`() {
        val gatewayOnly = base.copy(wakeMethod = WakeMethod.GATEWAY, macAddress = "aa:bb:cc:dd:ee:ff")
        assertNotNull(gatewayOnly.validate())
        assertNull(gatewayOnly.copy(wakeGatewayId = "some-id").validate())
    }

    @Test
    fun `sudo is bypassed when connecting as root`() {
        assertFalse(base.copy(username = "root", useSudo = true).effectiveUseSudo)
        assertTrue(base.copy(username = "lala", useSudo = true).effectiveUseSudo)
    }

    @Test
    fun `a key based server needs a key`() {
        val keyServer = base.copy(authMethod = AuthMethod.PRIVATE_KEY, password = "")
        assertNotNull(keyServer.validate())
        assertNull(keyServer.copy(privateKeyPem = "-----BEGIN OPENSSH PRIVATE KEY-----").validate())
    }
}
