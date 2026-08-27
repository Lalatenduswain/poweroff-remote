package com.lalatendu.poweroffremote

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lalatendu.poweroffremote.data.crypto.CryptoManager
import com.lalatendu.poweroffremote.data.model.ActionType
import com.lalatendu.poweroffremote.data.model.AuthMethod
import com.lalatendu.poweroffremote.data.model.Server
import com.lalatendu.poweroffremote.data.store.LogRepository
import com.lalatendu.poweroffremote.data.store.ServerRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Drives the encrypted vault end to end on the device: repository -> AES-GCM -> internal storage
 * and back.
 *
 * Uses its own file names and its own Keystore alias, so the real servers.vault and the real key
 * are never touched.
 */
@RunWith(AndroidJUnit4::class)
class VaultStorageTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var alias: String
    private lateinit var crypto: CryptoManager
    private lateinit var serversFile: String
    private lateinit var logsFile: String

    private val secret = "TOPSECRET-passphrase-9f3a"

    private val sample = Server(
        name = "home lab",
        host = "192.168.1.200",
        username = "lalatendu",
        authMethod = AuthMethod.PASSWORD,
        password = secret,
        macAddress = "aa:bb:cc:dd:ee:ff",
    )

    @Before
    fun setUp() {
        val stamp = System.nanoTime()
        alias = "poweroff_test_$stamp"
        crypto = CryptoManager(alias)
        serversFile = "servers-test-$stamp.vault"
        logsFile = "activity-test-$stamp.vault"
    }

    @After
    fun tearDown() {
        File(context.filesDir, serversFile).delete()
        File(context.filesDir, logsFile).delete()
        crypto.destroyKey()
    }

    private fun servers() = ServerRepository(context, serversFile, crypto)
    private fun logs() = LogRepository(context, logsFile, crypto)
    private fun serversOnDisk() = File(context.filesDir, serversFile)

    @Test
    fun theProductionVaultIsNeverTouched() {
        assertFalse(serversFile == "servers.vault")
        assertFalse(logsFile == "activity.vault")
        assertFalse(alias == CryptoManager.DEFAULT_ALIAS)
    }

    @Test
    fun aSavedServerCanBeReadBack() {
        val repo = servers()
        repo.upsert(sample)

        assertEquals(1, repo.servers.value.size)
        val loaded = repo.get(sample.id)
        assertNotNull(loaded)
        assertEquals("home lab", loaded!!.name)
        assertEquals(secret, loaded.password)
        assertEquals("aa:bb:cc:dd:ee:ff", loaded.macAddress)
    }

    /** The real test of the vault: a brand new repository has to decrypt what the last one wrote. */
    @Test
    fun credentialsSurviveAProcessRestart() {
        servers().upsert(sample)

        val reopened = servers()
        assertEquals(1, reopened.servers.value.size)
        assertEquals(secret, reopened.get(sample.id)!!.password)
    }

    @Test
    fun nothingReadableEverReachesTheDisk() {
        servers().upsert(
            sample.copy(
                privateKeyPem = "-----BEGIN OPENSSH PRIVATE KEY-----\nsensitive\n",
                sudoPassword = "sudo-$secret",
            )
        )

        val onDisk = serversOnDisk()
        assertTrue("the vault file was never written", onDisk.exists())
        val raw = onDisk.readText()

        assertFalse("the SSH password is on disk in the clear", raw.contains(secret))
        assertFalse("the sudo password is on disk in the clear", raw.contains("sudo-$secret"))
        assertFalse("the private key is on disk in the clear", raw.contains("BEGIN OPENSSH"))
        assertFalse("the host is on disk in the clear", raw.contains("192.168.1.200"))
        assertFalse("the server name is on disk in the clear", raw.contains("home lab"))
        assertFalse("the payload is not even JSON", raw.contains("\"password\""))
    }

    @Test
    fun editingAServerReplacesItRatherThanDuplicatingIt() {
        val repo = servers()
        repo.upsert(sample)
        repo.upsert(sample.copy(name = "renamed", password = "second"))

        assertEquals(1, repo.servers.value.size)
        assertEquals("renamed", repo.get(sample.id)!!.name)
        assertEquals("second", servers().get(sample.id)!!.password)
    }

    @Test
    fun deletingAServerRemovesItFromDiskToo() {
        val repo = servers()
        repo.upsert(sample)
        repo.delete(sample.id)

        assertTrue(repo.servers.value.isEmpty())
        assertTrue(servers().servers.value.isEmpty())
        assertFalse(serversOnDisk().readText().contains(secret))
    }

    @Test
    fun hostKeyPinningPersists() {
        val repo = servers()
        repo.upsert(sample)
        assertEquals("", repo.get(sample.id)!!.hostKeyFingerprint)

        repo.rememberHostKey(sample.id, "SHA256:abc123")
        assertEquals("SHA256:abc123", servers().get(sample.id)!!.hostKeyFingerprint)

        repo.forgetHostKey(sample.id)
        assertEquals("", servers().get(sample.id)!!.hostKeyFingerprint)
    }

    @Test
    fun eraseAllLeavesNothingBehind() {
        val repo = servers()
        repo.upsert(sample)
        repo.eraseAll()

        assertTrue(repo.servers.value.isEmpty())
        assertFalse(serversOnDisk().exists())
        assertTrue(servers().servers.value.isEmpty())
    }

    /** What happens after the key is gone: the app must start empty, not crash on launch. */
    @Test
    fun anUndecryptableVaultIsDiscardedInsteadOfCrashing() {
        servers().upsert(sample)
        assertTrue(serversOnDisk().exists())

        serversOnDisk().writeText("this is not a valid encrypted vault")

        val reopened = servers()
        assertTrue("a corrupt vault must load as empty", reopened.servers.value.isEmpty())
        assertFalse("the unreadable file should be cleared away", serversOnDisk().exists())
    }

    @Test
    fun aVaultWrittenUnderADifferentKeyIsDiscarded() {
        servers().upsert(sample)

        val strangerAlias = "poweroff_test_stranger_${System.nanoTime()}"
        val stranger = CryptoManager(strangerAlias)
        try {
            val reopened = ServerRepository(context, serversFile, stranger)
            assertTrue("another key must not read our vault", reopened.servers.value.isEmpty())
        } finally {
            stranger.destroyKey()
        }
    }

    @Test
    fun theActivityLogEncryptsAndOrdersNewestFirst() {
        val repo = logs()
        repo.record(sample.id, sample.name, ActionType.POWER_OFF, true, "first", detail = secret)
        repo.record(sample.id, sample.name, ActionType.WAKE, false, "second")

        assertEquals(2, repo.entries.value.size)
        assertEquals("second", repo.entries.value.first().message)
        assertEquals(2, logs().entries.value.size)

        val raw = File(context.filesDir, logsFile).readText()
        assertFalse("log details are secrets too", raw.contains(secret))
        assertFalse(raw.contains("home lab"))
    }

    @Test
    fun theActivityLogIsCappedSoTheFileCannotGrowForever() {
        val repo = logs()
        repeat(410) { repo.record(sample.id, "s", ActionType.STATUS, true, "entry $it") }

        assertEquals(400, repo.entries.value.size)
        assertEquals("entry 409", repo.entries.value.first().message)
        assertEquals(400, logs().entries.value.size)
    }

    @Test
    fun clearingTheLogRemovesTheFile() {
        val repo = logs()
        repo.record(sample.id, "s", ActionType.REBOOT, true, "something")
        repo.clear()

        assertTrue(repo.entries.value.isEmpty())
        assertFalse(File(context.filesDir, logsFile).exists())
    }

    @Test
    fun logsCanBeFilteredToOneServer() {
        val repo = logs()
        repo.record("server-a", "A", ActionType.POWER_OFF, true, "a1")
        repo.record("server-b", "B", ActionType.POWER_OFF, true, "b1")
        repo.record("server-a", "A", ActionType.WAKE, true, "a2")

        assertEquals(2, repo.forServer("server-a").size)
        assertEquals(1, repo.forServer("server-b").size)
        assertNull(repo.forServer("server-c").firstOrNull())
    }
}
