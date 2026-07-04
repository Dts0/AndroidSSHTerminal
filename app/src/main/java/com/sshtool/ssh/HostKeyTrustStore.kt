package com.sshtool.ssh

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64

class HostKeyTrustStore(context: Context) : HostKeyStoreBackend(File(context.filesDir, "trusted_host_keys.tsv"))

open class HostKeyStoreBackend(
    private val storeFile: File
) {
    private val lock = lockFor(storeFile.absoluteFile)

    init {
        synchronized(lock) {
            if (!storeFile.exists()) {
                storeFile.parentFile?.mkdirs()
                storeFile.createNewFile()
            }
        }
    }

    fun isTrusted(host: String, port: Int, algorithm: String, key: String): Boolean {
        val expected = fingerprint(key)
        val hostId = hashedHostId(host, port)
        return synchronized(lock) { loadEntriesLocked() }.any {
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
        return synchronized(lock) {
            loadEntriesLocked().any { it.hostId == hostId && it.port == port }
        }
    }

    fun trust(host: String, port: Int, algorithm: String, key: String) {
        val fingerprint = fingerprint(key)
        val hostId = hashedHostId(host, port)
        synchronized(lock) {
            val entries = loadEntriesLocked().filterNot {
                it.hostId == hostId && it.port == port
            }.toMutableList()
            entries += Entry(hostId, port, algorithm, fingerprint)
            writeEntriesLocked(entries)
        }
    }

    /**
     * Drop every trusted pin for [host]:[port]. Called when a host entry is
     * deleted so the store does not accumulate orphan fingerprints — an orphan
     * would otherwise be silently reused if a host of the same name+port were
     * recreated later, defeating the TOFU intent (the new host would inherit
     * the old host's trust without re-prompting). Mirrors the lifecycle of
     * [PasswordStore] cleanup done by HostRepository.deleteHost.
     */
    fun removeHost(host: String, port: Int) {
        val hostId = hashedHostId(host, port)
        synchronized(lock) {
            val remaining = loadEntriesLocked().filterNot {
                it.hostId == hostId && it.port == port
            }
            writeEntriesLocked(remaining)
        }
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

    private fun loadEntriesLocked(): List<Entry> {
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
            writeStoredEntriesLocked(migrated)
            return migrated.map { Entry(it.hostId, it.port, it.algorithm, it.fingerprint) }
        }
        return raw.map { Entry(it.hostId, it.port, it.algorithm, it.fingerprint) }
    }

    private fun writeEntriesLocked(entries: List<Entry>) {
        writeStoredEntriesLocked(entries.map { StoredEntry(it.hostId, it.port, it.algorithm, it.fingerprint) })
    }

    private fun writeStoredEntriesLocked(entries: List<StoredEntry>) {
        val text = entries.joinToString(separator = "\n") {
            "${it.hostId}\t${it.port}\t${it.algorithm}\t${it.fingerprint}"
        }.let { if (it.isEmpty()) "" else "$it\n" }
        val parent = storeFile.parentFile ?: File(".")
        parent.mkdirs()
        val tmp = File(parent, "${storeFile.name}.tmp")
        tmp.writeText(text)
        try {
            Files.move(
                tmp.toPath(),
                storeFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: Exception) {
            Files.move(tmp.toPath(), storeFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
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
        private val LOCKS = mutableMapOf<String, Any>()

        fun lockFor(file: File): Any = synchronized(LOCKS) {
            LOCKS.getOrPut(file.absolutePath) { Any() }
        }

        // App-level salt for hostname hashing. Not a secret — it only prevents
        // trivial rainbow-table reuse against this specific file.
        const val SALT = "sshtool-trusted-hosts-v1"
    }
}
