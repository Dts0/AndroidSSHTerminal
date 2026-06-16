package com.sshtool.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HostKeyTrustStoreTest {
    @Test
    fun fingerprint_isStableForBase64Key() {
        val file = File.createTempFile("hostkeys", ".tsv")
        val store = HostKeyStoreBackend(file)
        val key = "c3NoLXJzYQ=="
        val fp1 = store.fingerprint(key)
        val fp2 = store.fingerprint(key)
        assertEquals(fp1, fp2)
    }

    @Test
    fun trust_persistsAndMatches() {
        val file = File.createTempFile("hostkeys", ".tsv")
        val store = HostKeyStoreBackend(file)
        val key = "c3NoLWVkMjU1MTk="
        assertFalse(store.isTrusted("example.com", 22, "ssh-ed25519", key))
        store.trust("example.com", 22, "ssh-ed25519", key)
        assertTrue(store.isTrusted("example.com", 22, "ssh-ed25519", key))
    }

    @Test
    fun trust_doesNotMatchWhenAlgorithmDiffers() {
        // Pinning now compares the algorithm as well as host+port+fingerprint,
        // so a key offered under a different algorithm must not match the pin.
        val file = File.createTempFile("hostkeys", ".tsv")
        val store = HostKeyStoreBackend(file)
        val key = "c3NoLWVkMjU1MTk="
        store.trust("example.com", 22, "ssh-ed25519", key)

        assertFalse(store.isTrusted("example.com", 22, "ssh-rsa", key))
        assertTrue(store.isTrusted("example.com", 22, "ssh-ed25519", key))
    }

    @Test
    fun hasTrustedHost_distinguishesFirstConnectFromKeyChange() {
        val file = File.createTempFile("hostkeys", ".tsv")
        val store = HostKeyStoreBackend(file)
        assertFalse(store.hasTrustedHost("example.com", 22))
        store.trust("example.com", 22, "ssh-ed25519", "c3NoLWVkMjU1MTk=")
        // A different key for the same host still reports the host as pinned,
        // so the caller can tell "first connect" from "key changed".
        assertTrue(store.hasTrustedHost("example.com", 22))
        assertFalse(store.hasTrustedHost("other.com", 22))
    }

    @Test
    fun trust_replacesExistingEntryForSameHostAndPort() {
        val file = File.createTempFile("hostkeys", ".tsv")
        val store = HostKeyStoreBackend(file)
        val oldKey = "c3NoLWVkMjU1MTk="
        val newKey = "c3NoLXJzYQ=="

        store.trust("example.com", 22, "ssh-ed25519", oldKey)
        store.trust("example.com", 22, "ssh-rsa", newKey)

        assertFalse(store.isTrusted("example.com", 22, "ssh-ed25519", oldKey))
        assertTrue(store.isTrusted("example.com", 22, "ssh-rsa", newKey))
    }
}
