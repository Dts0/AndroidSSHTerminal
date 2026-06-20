package com.sshtool.ssh

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.Base64

class HostKeyTrustStore(context: Context) : HostKeyStoreBackend(File(context.filesDir, "trusted_host_keys.tsv"))

open class HostKeyStoreBackend(
    private val storeFile: File
) {

    init {
        if (!storeFile.exists()) {
            storeFile.parentFile?.mkdirs()
            storeFile.createNewFile()
        }
    }

    fun isTrusted(host: String, port: Int, algorithm: String, key: String): Boolean {
        val expected = fingerprint(key)
        val hostId = hashedHostId(host, port)
        return loadEntries().any {
            it.hostId == hostId &&
                it.port == port &&
                it.algorithm == algorithm &&
                it.fingerprint == expected
        }
    }

    /**
     * Whether [host]:[port] has *any* trusted key pinned, regardless of algorithm.
     * Used to distinguish "first connect" (no pin yet) from "key changed" (pin exists
     * but the presented key differs) — the latter must surface a warning, never be
     * silently accepted (host-key MITM defense).
     */
    fun hasTrustedHost(host: String, port: Int): Boolean {
        val hostId = hashedHostId(host, port)
        return loadEntries().any { it.hostId == hostId && it.port == port }
    }

    fun trust(host: String, port: Int, algorithm: String, key: String) {
        val fingerprint = fingerprint(key)
        val hostId = hashedHostId(host, port)
        val entries = loadEntries().filterNot {
            it.hostId == hostId && it.port == port
        }.toMutableList()
        entries += Entry(hostId, port, algorithm, fingerprint)
        storeFile.writeText(entries.joinToString(separator = "\n") { "${it.hostId}\t${it.port}\t${it.algorithm}\t${it.fingerprint}" } + "\n")
    }

    fun fingerprint(key: String): String {
        val normalized = try {
            Base64.getDecoder().decode(key)
        } catch (_: IllegalArgumentException) {
            key.toByteArray(Charsets.UTF_8)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized)
        return digest.joinToString(separator = ":") { "%02X".format(it) }
    }

    /**
     * Stable, non-reversible identifier for a [host]:[port] pair. The hostname is
     * hashed (not stored in cleartext) so that anyone with read access to this file
     * cannot enumerate the user's SSH hosts — consistent with JSch's HashKnownHosts
     * treatment of `known_hosts` (m1). The salt is fixed at the app level; this is
     * obfuscation against casual file access, not a secret. The port is folded into
     * the hash so different ports on the same host get distinct ids.
     */
    private fun hashedHostId(host: String, port: Int): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(SALT.toByteArray(Charsets.UTF_8))
        md.update(0)
        md.update(host.toByteArray(Charsets.UTF_8))
        md.update(0)
        // Encode the full port (not a single byte — ports exceed 127) so
        // different ports on the same host produce distinct ids.
        md.update("$port".toByteArray(Charsets.UTF_8))
        return "h:" + md.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    private fun loadEntries(): List<Entry> {
        if (!storeFile.exists()) return emptyList()
        val raw = storeFile.readLines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 4) return@mapNotNull null
            val port = parts[1].toIntOrNull() ?: return@mapNotNull null
            StoredEntry(parts[0], port, parts[2], parts[3])
        }
        // Migrate legacy entries whose host column is still a cleartext hostname
        // (pre-m1 format) to the hashed form. Detected by the absence of the
        // "h:" prefix. If any are migrated, rewrite the file once.
        if (raw.any { !it.hostId.startsWith("h:") }) {
            val migrated = raw.map { e ->
                if (e.hostId.startsWith("h:")) {
                    e
                } else {
                    // Re-derive the hash from the cleartext host + port.
                    e.copy(hostId = hashedHostId(e.hostId, e.port))
                }
            }
            storeFile.writeText(migrated.joinToString(separator = "\n") { "${it.hostId}\t${it.port}\t${it.algorithm}\t${it.fingerprint}" } + "\n")
            return migrated.map { Entry(it.hostId, it.port, it.algorithm, it.fingerprint) }
        }
        return raw.map { Entry(it.hostId, it.port, it.algorithm, it.fingerprint) }
    }

    private data class StoredEntry(
        val hostId: String,
        val port: Int,
        val algorithm: String,
        val fingerprint: String
    )

    data class Entry(
        val hostId: String,
        val port: Int,
        val algorithm: String,
        val fingerprint: String
    )

    private companion object {
        // App-level salt for hostname hashing. Not a secret — it only prevents
        // trivial rainbow-table reuse against this specific file.
        const val SALT = "sshtool-trusted-hosts-v1"
    }
}
