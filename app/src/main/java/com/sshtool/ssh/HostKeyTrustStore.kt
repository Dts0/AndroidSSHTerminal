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
        return loadEntries().any {
            it.host == host && it.port == port && it.algorithm == algorithm && it.fingerprint == expected
        }
    }

    fun trust(host: String, port: Int, algorithm: String, key: String) {
        val fingerprint = fingerprint(key)
        val entries = loadEntries().filterNot {
            it.host == host && it.port == port && it.algorithm == algorithm
        }.toMutableList()
        entries += Entry(host, port, algorithm, fingerprint)
        storeFile.writeText(entries.joinToString(separator = "\n") { "${it.host}\t${it.port}\t${it.algorithm}\t${it.fingerprint}" } + "\n")
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

    private fun loadEntries(): List<Entry> {
        if (!storeFile.exists()) return emptyList()
        return storeFile.readLines()
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size != 4) return@mapNotNull null
                val port = parts[1].toIntOrNull() ?: return@mapNotNull null
                Entry(parts[0], port, parts[2], parts[3])
            }
    }

    data class Entry(
        val host: String,
        val port: Int,
        val algorithm: String,
        val fingerprint: String
    )
}
