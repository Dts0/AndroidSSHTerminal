package com.sshtool.ssh

import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64
import java.util.Locale

class HostKeyTrustStoreTest {

    // Tracks and cleans up temp files instead of leaking them across runs.
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newStore(): HostKeyStoreBackend =
        HostKeyStoreBackend(File.createTempFile("hostkeys", ".tsv", tempFolder.root))

    @Test
    fun fingerprint_isStableForBase64Key() {
        val store = newStore()
        val key = "c3NoLXJzYQ=="
        val fp1 = store.fingerprint(key)
        val fp2 = store.fingerprint(key)
        assertEquals(fp1, fp2)
    }

    @Test
    fun trustIdentifiers_areStableAcrossLocales() {
        val originalLocale = Locale.getDefault()
        try {
            val store = newStore()
            val key = "c3NoLWVkMjU1MTk="
            Locale.setDefault(Locale.US)
            val usFingerprint = store.fingerprint(key)
            store.trust("locale.example.com", 2222, "ssh-ed25519", key)

            Locale.setDefault(Locale("ar", "EG"))
            assertEquals(usFingerprint, store.fingerprint(key))
            assertTrue(store.isTrusted("locale.example.com", 2222, "ssh-ed25519", key))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun trust_persistsAndMatches() {
        val store = newStore()
        val key = "c3NoLWVkMjU1MTk="
        assertFalse(store.isTrusted("example.com", 22, "ssh-ed25519", key))
        store.trust("example.com", 22, "ssh-ed25519", key)
        assertTrue(store.isTrusted("example.com", 22, "ssh-ed25519", key))
    }

    @Test
    fun trust_doesNotMatchWhenAlgorithmDiffers() {
        // Pinning now compares the algorithm as well as host+port+fingerprint,
        // so a key offered under a different algorithm must not match the pin.
        val store = newStore()
        val key = "c3NoLWVkMjU1MTk="
        store.trust("example.com", 22, "ssh-ed25519", key)

        assertFalse(store.isTrusted("example.com", 22, "ssh-rsa", key))
        assertTrue(store.isTrusted("example.com", 22, "ssh-ed25519", key))
    }

    @Test
    fun hasTrustedHost_distinguishesFirstConnectFromKeyChange() {
        val store = newStore()
        assertFalse(store.hasTrustedHost("example.com", 22))
        store.trust("example.com", 22, "ssh-ed25519", "c3NoLWVkMjU1MTk=")
        // A different key for the same host still reports the host as pinned,
        // so the caller can tell "first connect" from "key changed".
        assertTrue(store.hasTrustedHost("example.com", 22))
        assertFalse(store.hasTrustedHost("other.com", 22))
    }

    @Test
    fun trust_replacesExistingEntryForSameHostAndPort() {
        val store = newStore()
        val oldKey = "c3NoLWVkMjU1MTk="
        val newKey = "c3NoLXJzYQ=="

        store.trust("example.com", 22, "ssh-ed25519", oldKey)
        store.trust("example.com", 22, "ssh-rsa", newKey)

        assertFalse(store.isTrusted("example.com", 22, "ssh-ed25519", oldKey))
        assertTrue(store.isTrusted("example.com", 22, "ssh-rsa", newKey))
    }

    @Test
    fun removeHost_dropsAllPinsForHostPort() {
        // Deleting a host entry must purge its trusted pins so an orphan
        // fingerprint is not silently reused by a later host of the same
        // name+port (which would bypass the first-connect TOFU prompt).
        val store = newStore()
        val key = "c3NoLWVkMjU1MTk="

        store.trust("example.com", 22, "ssh-ed25519", key)
        assertTrue(store.hasTrustedHost("example.com", 22))

        store.removeHost("example.com", 22)

        assertFalse(store.hasTrustedHost("example.com", 22))
        assertFalse(store.isTrusted("example.com", 22, "ssh-ed25519", key))
    }

    @Test
    fun removeHost_leavesOtherHostsUntouched() {
        val store = newStore()
        val key = "c3NoLWVkMjU1MTk="

        store.trust("example.com", 22, "ssh-ed25519", key)
        store.trust("other.com", 22, "ssh-ed25519", key)
        store.trust("example.com", 2222, "ssh-ed25519", key)

        store.removeHost("example.com", 22)

        assertFalse(store.hasTrustedHost("example.com", 22))
        // Different host on the same port survives.
        assertTrue(store.hasTrustedHost("other.com", 22))
        // Same host on a different port survives.
        assertTrue(store.hasTrustedHost("example.com", 2222))
    }

    @Test
    fun removeHost_whenNothingPinnedIsNoOp() {
        val store = newStore()
        // Removing a host that was never trusted must not throw and must not
        // corrupt the store file.
        store.removeHost("ghost.example.com", 22)

        store.trust("example.com", 22, "ssh-ed25519", "c3NoLWVkMjU1MTk=")
        assertTrue(store.hasTrustedHost("example.com", 22))
    }

    @Test
    fun removeHost_allowsReTrustAfterDeletion() {
        // The TOFU guarantee: after a host is deleted and re-added, the next
        // connection must re-prompt because the old pin is gone.
        val store = newStore()
        val key = "c3NoLWVkMjU1MTk="

        store.trust("example.com", 22, "ssh-ed25519", key)
        assertTrue(store.hasTrustedHost("example.com", 22))

        store.removeHost("example.com", 22)
        assertFalse(store.hasTrustedHost("example.com", 22))

        // Re-trust works as a fresh pin.
        store.trust("example.com", 22, "ssh-ed25519", key)
        assertTrue(store.isTrusted("example.com", 22, "ssh-ed25519", key))
    }

    @Test
    fun trust_doesNotStoreHostnameInCleartext() {
        // The hostname must be hashed on disk so the file doesn't leak the
        // user's SSH host list (m1). "example.com" must not appear verbatim.
        val file = File.createTempFile("hostkeys", ".tsv", tempFolder.root)
        val store = HostKeyStoreBackend(file)
        store.trust("secret.example.com", 2222, "ssh-ed25519", "c3NoLWVkMjU1MTk=")

        val onDisk = file.readText()
        assertFalse("hostname leaked in cleartext: $onDisk", onDisk.contains("secret.example.com"))
        // And the stored id is the hashed form.
        assertTrue(onDisk.contains("h:"))
    }

    @Test
    fun migrate_legacyCleartextEntriesAreRehashedOnLoad() {
        // Write a pre-m1 file with a cleartext hostname, in the old 4-column
        // layout. Loading through the store must rehash it and still match.
        val file = File.createTempFile("hostkeys", ".tsv", tempFolder.root)
        val key = "c3NoLWVkMjU1MTk="
        // host<TAB>port<TAB>algo<TAB>fingerprint — cleartext host, no "h:" prefix.
        val cleartextFp = HostKeyStoreBackend(file).fingerprint(key)
        file.writeText("legacy.example.com\t22\tssh-ed25519\t$cleartextFp\n")

        val store = HostKeyStoreBackend(file)
        // After load-triggered migration, the cleartext host is recognized.
        assertTrue(store.isTrusted("legacy.example.com", 22, "ssh-ed25519", key))
        // And the file on disk no longer contains the cleartext hostname.
        val onDisk = file.readText()
        assertFalse("legacy host not migrated: $onDisk", onDisk.contains("legacy.example.com"))
        assertTrue(onDisk.contains("h:"))
    }

    @Test
    fun removeHost_nonDefaultPortLeavesDefaultPortKnownHostUntouched() {
        val directory = tempFolder.newFolder("remove-exact-legacy")
        val trustedFile = File(directory, "trusted_host_keys.tsv")
        val knownHostsFile = File(directory, "known_hosts")
        val defaultKey = keyBlob("ssh-ed25519", 1)
        val alternateKey = keyBlob("ssh-ed25519", 2)
        knownHostsFile.writeText(
            knownHostLine("example.com", defaultKey) +
                knownHostLine("[example.com]:2222", alternateKey)
        )
        val store = HostKeyTrustStore(trustedFile, knownHostsFile)

        store.removeHost("example.com", 2222)

        val jsch = JSch().also { it.setKnownHosts(knownHostsFile.absolutePath) }
        assertEquals(
            HostKeyRepository.OK,
            jsch.hostKeyRepository.check("example.com", defaultKey)
        )
        assertFalse(knownHostsFile.readText().contains("[example.com]:2222"))
        // JSch falls a bracketed endpoint back to the bare host after the exact
        // entry is removed, so the surviving port-22 key yields CHANGED rather
        // than NOT_INCLUDED for a different key. It must never yield OK.
        assertEquals(
            HostKeyRepository.CHANGED,
            jsch.hostKeyRepository.check("[example.com]:2222", alternateKey)
        )
    }

    @Test
    fun snapshotAndRestore_restoresOnlyEndpointAndPreservesConcurrentHosts() {
        val directory = tempFolder.newFolder("snapshot-both-stores")
        val trustedFile = File(directory, "trusted_host_keys.tsv")
        val knownHostsFile = File(directory, "known_hosts")
        val host = "snapshot.example.com"
        val oldKey = keyBlob("ssh-ed25519", 3)
        val newKey = keyBlob("ssh-ed25519", 4)
        knownHostsFile.writeText(knownHostLine(host, oldKey))
        val store = HostKeyTrustStore(trustedFile, knownHostsFile)
        store.trust(host, 22, "ssh-ed25519", Base64.getEncoder().encodeToString(oldKey))
        val snapshot = store.snapshotHost(host, 22)

        store.trust(host, 22, "ssh-ed25519", Base64.getEncoder().encodeToString(newKey))
        knownHostsFile.writeText(knownHostLine("wrong.example.com", newKey))
        store.restoreHost(host, 22, snapshot)

        assertTrue(
            store.isTrusted(
                host,
                22,
                "ssh-ed25519",
                Base64.getEncoder().encodeToString(oldKey)
            )
        )
        assertFalse(
            store.isTrusted(
                host,
                22,
                "ssh-ed25519",
                Base64.getEncoder().encodeToString(newKey)
            )
        )
        val reloaded = JSch().also { it.setKnownHosts(knownHostsFile.absolutePath) }
            .hostKeyRepository
        assertEquals(HostKeyRepository.OK, reloaded.check(host, oldKey))
        // Restoring host A must merge with current disk state rather than
        // replacing the whole file and losing host B.
        assertEquals(HostKeyRepository.OK, reloaded.check("wrong.example.com", newKey))
    }

    private fun knownHostLine(host: String, key: ByteArray): String =
        "$host ssh-ed25519 ${Base64.getEncoder().encodeToString(key)}\n"

    private fun keyBlob(algorithm: String, payload: Int): ByteArray {
        val name = algorithm.toByteArray(Charsets.UTF_8)
        return ByteArray(4 + name.size + 1).also { blob ->
            blob[0] = ((name.size ushr 24) and 0xFF).toByte()
            blob[1] = ((name.size ushr 16) and 0xFF).toByte()
            blob[2] = ((name.size ushr 8) and 0xFF).toByte()
            blob[3] = (name.size and 0xFF).toByte()
            System.arraycopy(name, 0, blob, 4, name.size)
            blob[blob.lastIndex] = payload.toByte()
        }
    }
}
