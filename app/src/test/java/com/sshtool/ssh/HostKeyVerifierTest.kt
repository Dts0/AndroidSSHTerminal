package com.sshtool.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the pure host-key parsing helper (M4): parsing failures must
 * return null rather than coercing to a specific algorithm like "ssh-rsa",
 * which could cause a key to be verified against the wrong trust entry.
 */
class HostKeyVerifierTest {

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
