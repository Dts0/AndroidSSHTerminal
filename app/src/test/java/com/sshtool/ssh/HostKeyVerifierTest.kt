package com.sshtool.ssh

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Base64

/**
 * Tests for the pure host-key parsing helper (M4): parsing failures must
 * return null rather than coercing to a specific algorithm like "ssh-rsa",
 * which could cause a key to be verified against the wrong trust entry.
 */
class HostKeyVerifierTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun detectHostKeyAlgorithm_parsesWellFormedKey() {
        val name = "ssh-ed25519".toByteArray(Charsets.UTF_8)
        assertEquals("ssh-ed25519", detectHostKeyAlgorithm(keyBlob(name, 1)))
    }

    @Test
    fun detectHostKeyAlgorithm_parsesRsaKey() {
        val name = "ssh-rsa".toByteArray(Charsets.UTF_8)
        assertEquals("ssh-rsa", detectHostKeyAlgorithm(keyBlob(name, 1)))
    }

    @Test
    fun detectHostKeyAlgorithm_returnsNullOnTruncatedKey() {
        // Must NOT default to "ssh-rsa" — that was the M4 bug.
        assertNull(detectHostKeyAlgorithm(ByteArray(2)))
        assertNull(detectHostKeyAlgorithm(ByteArray(0)))
    }

    @Test
    fun detectHostKeyAlgorithm_returnsNullWhenAlgoLenExceedsBlob() {
        // 4-byte length prefix claims 10 bytes but only 1 follows.
        val blob = byteArrayOf(0, 0, 0, 10, 65)
        assertNull(detectHostKeyAlgorithm(blob))
    }

    @Test
    fun detectHostKeyAlgorithm_returnsNullWhenAlgoLenIsZero() {
        val blob = byteArrayOf(0, 0, 0, 0, 65)
        assertNull(detectHostKeyAlgorithm(blob))
    }

    @Test
    fun strictRepository_matchingAppPinReturnsOkAndClearsPriorRejection() {
        val host = "example.com"
        val key = keyBlob("ssh-ed25519".toByteArray(), 1)
        val store = newStore()
        val strict = strictRepository(host, 22, store)

        assertEquals(HostKeyRepository.NOT_INCLUDED, strict.check(host, key))
        assertNotNull(strict.lastRejectedKey)
        assertEquals(
            StrictHostKeyRepository.RejectionReason.FIRST_USE,
            strict.lastRejectedKey?.reason
        )

        store.trust(host, 22, "ssh-ed25519", Base64.getEncoder().encodeToString(key))
        assertEquals(HostKeyRepository.OK, strict.check(host, key))
        assertNull(strict.lastRejectedKey)
    }

    @Test
    fun strictRepository_appPinIsAuthoritativeOverDelegateOk() {
        val host = "example.com"
        val pinnedKey = keyBlob("ssh-ed25519".toByteArray(), 1)
        val staleKey = keyBlob("ssh-ed25519".toByteArray(), 2)
        val store = newStore()
        val delegate = JSch().hostKeyRepository
        delegate.add(HostKey(host, staleKey), null)
        store.trust(host, 22, "ssh-ed25519", Base64.getEncoder().encodeToString(pinnedKey))
        val strict = strictRepository(host, 22, store, delegate)

        assertEquals(HostKeyRepository.OK, delegate.check(host, staleKey))
        assertEquals(HostKeyRepository.CHANGED, strict.check(host, staleKey))
        assertNotNull(strict.lastRejectedKey)
        assertEquals(
            StrictHostKeyRepository.RejectionReason.CHANGED,
            strict.lastRejectedKey?.reason
        )
    }

    @Test
    fun strictRepository_migratesExactLegacyEntryIntoAppPin() {
        val host = "legacy.example.com"
        val key = keyBlob("ssh-ed25519".toByteArray(), 3)
        val store = newStore()
        val delegate = JSch().hostKeyRepository
        delegate.add(HostKey(host, key), null)
        val strict = strictRepository(host, 22, store, delegate)

        assertEquals(HostKeyRepository.OK, strict.check(host, key))
        assertFalse(store.hasTrustedHost(host, 22))
        strict.persistPendingHostKey()
        assertTrue(
            store.isTrusted(
                host,
                22,
                "ssh-ed25519",
                Base64.getEncoder().encodeToString(key)
            )
        )
        assertNull(strict.lastRejectedKey)
    }

    @Test
    fun strictRepository_changedLegacyEntryRecordsChangedReason() {
        val host = "changed-legacy.example.com"
        val oldKey = keyBlob("ssh-ed25519".toByteArray(), 31)
        val presentedKey = keyBlob("ssh-ed25519".toByteArray(), 32)
        val store = newStore()
        val delegate = JSch().hostKeyRepository
        delegate.add(HostKey(host, oldKey), null)
        val strict = strictRepository(host, 22, store, delegate)

        assertEquals(HostKeyRepository.CHANGED, strict.check(host, presentedKey))
        assertEquals(
            StrictHostKeyRepository.RejectionReason.CHANGED,
            strict.lastRejectedKey?.reason
        )
        assertFalse(store.hasTrustedHost(host, 22))
    }

    @Test
    fun strictRepository_revokedLegacyKeyIsNeverMigratedIntoAppPin() {
        val host = "revoked.example.com"
        val key = keyBlob("ssh-ed25519".toByteArray(), 33)
        val store = newStore()
        val knownHostsFile = tempFolder.newFile("revoked_known_hosts")
        knownHostsFile.writeText(
            "@revoked $host ssh-ed25519 ${Base64.getEncoder().encodeToString(key)}\n"
        )
        val delegate = JSch().also {
            it.setKnownHosts(knownHostsFile.absolutePath)
        }.hostKeyRepository
        val strict = strictRepository(host, 22, store, delegate)

        // mwiede JSch 0.2.18 itself returns OK here despite the marker; the
        // app wrapper must enforce revocation before legacy migration.
        assertEquals(HostKeyRepository.OK, delegate.check(host, key))
        assertEquals(HostKeyRepository.CHANGED, strict.check(host, key))
        assertFalse(store.hasTrustedHost(host, 22))
        assertEquals(
            StrictHostKeyRepository.RejectionReason.CHANGED,
            strict.lastRejectedKey?.reason
        )
    }

    @Test
    fun strictRepository_matchingAppPinRepairsChangedDelegateWithHashedHost() {
        val host = "repair.example.com"
        val pinnedKey = keyBlob("ssh-ed25519".toByteArray(), 4)
        val staleKey = keyBlob("ssh-ed25519".toByteArray(), 5)
        val store = newStore()
        val knownHostsFile = tempFolder.newFile("repair_known_hosts")
        knownHostsFile.writeText(
            "$host ssh-ed25519 ${Base64.getEncoder().encodeToString(staleKey)}\n"
        )
        val delegate = JSch().also {
            it.setKnownHosts(knownHostsFile.absolutePath)
        }.hostKeyRepository
        store.trust(host, 22, "ssh-ed25519", Base64.getEncoder().encodeToString(pinnedKey))
        val strict = strictRepository(host, 22, store, delegate)

        assertEquals(HostKeyRepository.CHANGED, delegate.check(host, pinnedKey))
        assertEquals(HostKeyRepository.OK, strict.check(host, pinnedKey))
        strict.persistPendingHostKey()
        // The wrapper recognizes the freshly-added hashed HostKey immediately.
        assertEquals(HostKeyRepository.OK, strict.check(host, pinnedKey))
        assertTrue(delegate.hostKey.any { it.host.startsWith("|1|") })
        assertFalse(delegate.hostKey.any { it.host == host && it.key == HostKey(host, staleKey).key })

        // JSch turns an OpenSSH hashed line into its HashedHostKey subtype when
        // loading it. Verify the repaired on-disk delegate is valid on restart.
        val reloaded = JSch().also {
            it.setKnownHosts(knownHostsFile.absolutePath)
        }.hostKeyRepository
        assertEquals(HostKeyRepository.OK, reloaded.check(host, pinnedKey))
    }

    @Test
    fun strictRepository_nonDefaultPortDoesNotInheritBareHostLegacyEntry() {
        val host = "example.com"
        val repositoryHost = "[$host]:2222"
        val key = keyBlob("ssh-ed25519".toByteArray(), 6)
        val store = newStore()
        val delegate = JSch().hostKeyRepository
        delegate.add(HostKey(host, key), null)
        val strict = strictRepository(host, 2222, store, delegate)

        assertEquals(HostKeyRepository.NOT_INCLUDED, strict.check(repositoryHost, key))
        assertFalse(store.hasTrustedHost(host, 2222))
        assertNotNull(strict.lastRejectedKey)
        assertEquals(
            StrictHostKeyRepository.RejectionReason.FIRST_USE,
            strict.lastRejectedKey?.reason
        )
    }

    @Test
    fun strictRepository_staleDelegatesRepairKnownHostsWithoutLostUpdate() {
        val directory = tempFolder.newFolder("concurrent-known-hosts")
        val knownHostsFile = File(directory, "known_hosts").apply { createNewFile() }
        val store = HostKeyTrustStore(
            File(directory, "trusted_host_keys.tsv"),
            knownHostsFile
        )
        val firstHost = "first.example.com"
        val secondHost = "second.example.com"
        val firstKey = keyBlob("ssh-ed25519".toByteArray(), 41)
        val secondKey = keyBlob("ssh-ed25519".toByteArray(), 42)
        store.trust(
            firstHost,
            22,
            "ssh-ed25519",
            Base64.getEncoder().encodeToString(firstKey)
        )
        store.trust(
            secondHost,
            22,
            "ssh-ed25519",
            Base64.getEncoder().encodeToString(secondKey)
        )

        // Both delegates intentionally load the same empty, now-stale image.
        val firstDelegate = JSch().also { it.setKnownHosts(knownHostsFile.absolutePath) }
            .hostKeyRepository
        val secondDelegate = JSch().also { it.setKnownHosts(knownHostsFile.absolutePath) }
            .hostKeyRepository
        val first = strictRepository(
            firstHost,
            22,
            store,
            firstDelegate,
            store.legacyRepository
        )
        val second = strictRepository(
            secondHost,
            22,
            store,
            secondDelegate,
            store.legacyRepository
        )

        assertEquals(HostKeyRepository.OK, first.check(firstHost, firstKey))
        first.persistPendingHostKey()
        assertEquals(HostKeyRepository.OK, second.check(secondHost, secondKey))
        second.persistPendingHostKey()

        val reloaded = JSch().also { it.setKnownHosts(knownHostsFile.absolutePath) }
            .hostKeyRepository
        assertEquals(HostKeyRepository.OK, reloaded.check(firstHost, firstKey))
        assertEquals(HostKeyRepository.OK, reloaded.check(secondHost, secondKey))
    }

    @Test
    fun strictRepository_pendingRepairPropagatesAtomicFileFailure() {
        val blocker = tempFolder.newFile("not-a-directory")
        val store = HostKeyTrustStore(
            tempFolder.newFile("trusted-for-failed-repair.tsv"),
            File(blocker, "known_hosts")
        )
        val host = "write-failure.example.com"
        val key = keyBlob("ssh-ed25519".toByteArray(), 43)
        store.trust(
            host,
            22,
            "ssh-ed25519",
            Base64.getEncoder().encodeToString(key)
        )
        val strict = strictRepository(
            host,
            22,
            store,
            legacyRepository = store.legacyRepository
        )

        assertEquals(HostKeyRepository.OK, strict.check(host, key))
        assertThrows(IOException::class.java) {
            strict.persistPendingHostKey()
        }
    }

    private fun newStore(): HostKeyStoreBackend =
        HostKeyStoreBackend(File.createTempFile("strict-hostkeys", ".tsv", tempFolder.root))

    private fun strictRepository(
        host: String,
        port: Int,
        store: HostKeyStoreBackend,
        delegate: HostKeyRepository = JSch().hostKeyRepository,
        legacyRepository: LegacyHostKeyRepository = DelegateLegacyHostKeyRepository(delegate)
    ): StrictHostKeyRepository = StrictHostKeyRepository(
        delegate = delegate,
        verifier = HostKeyVerifier(host, port, store),
        host = host,
        port = port,
        trustStore = store,
        legacyRepository = legacyRepository
    )

    /** Builds an SSH key blob: 4-byte big-endian length + name + 1 payload byte. */
    private fun keyBlob(name: ByteArray, payload: Int): ByteArray {
        val blob = ByteArray(4 + name.size + 1)
        blob[0] = ((name.size ushr 24) and 0xFF).toByte()
        blob[1] = ((name.size ushr 16) and 0xFF).toByte()
        blob[2] = ((name.size ushr 8) and 0xFF).toByte()
        blob[3] = (name.size and 0xFF).toByte()
        System.arraycopy(name, 0, blob, 4, name.size)
        blob[blob.size - 1] = payload.toByte()
        return blob
    }
}
