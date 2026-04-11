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
}
