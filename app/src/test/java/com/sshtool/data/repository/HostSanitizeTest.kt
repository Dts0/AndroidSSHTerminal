package com.sshtool.data.repository

import com.sshtool.data.model.Host
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies that secret fields are stripped before a Host is persisted, so
 * passwords/private keys/passphrases never reach Room. These fields are
 * @Ignore on the entity; the sanitizer is the runtime guarantee.
 */
class HostSanitizeTest {

    @Test
    fun sanitize_clearsAllSecretFields() {
        val host = Host(id = 1, name = "h", host = "example.com", username = "u").apply {
            password = "s3cret"
            privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----"
            passphrase = "passphrase123"
        }

        val sanitized = host.sanitizeForDatabase()

        assertNull(sanitized.password)
        assertNull(sanitized.privateKey)
        assertNull(sanitized.passphrase)
    }

    @Test
    fun sanitize_preservesNonSecretFields() {
        val host = Host(id = 7, name = "prod", host = "prod.example.com", port = 2222, username = "ops").apply {
            password = "pw"
            privateKey = "key"
            passphrase = "ph"
        }

        val sanitized = host.sanitizeForDatabase()

        assertEquals(7L, sanitized.id)
        assertEquals("prod", sanitized.name)
        assertEquals("prod.example.com", sanitized.host)
        assertEquals(2222, sanitized.port)
        assertEquals("ops", sanitized.username)
    }

    @Test
    fun sanitize_doesNotMutateOriginal() {
        val host = Host(id = 1, name = "h", host = "example.com", username = "u").apply {
            password = "s3cret"
            privateKey = "key"
            passphrase = "ph"
        }

        val sanitized = host.sanitizeForDatabase()

        // The original instance keeps its secrets; only the copy is clean.
        assertEquals("s3cret", host.password)
        assertEquals("key", host.privateKey)
        assertEquals("ph", host.passphrase)
        // And the result is a distinct instance.
        assertNotSame(host as Any, sanitized as Any)
    }

    @Test
    fun sanitize_handlesAlreadyNullSecrets() {
        val host = Host(id = 1, name = "h", host = "example.com", username = "u")
        val sanitized = host.sanitizeForDatabase()
        assertNull(sanitized.password)
        assertNull(sanitized.privateKey)
        assertNull(sanitized.passphrase)
    }
}
