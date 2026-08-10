package com.sshtool.ssh

import android.content.Context
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class HostKeyTrustStore internal constructor(
    trustedHostsFile: File,
    legacyKnownHostsFile: File
) : HostKeyStoreBackend(trustedHostsFile) {

    constructor(context: Context) : this(
        File(context.filesDir, "trusted_host_keys.tsv"),
        File(context.filesDir, "known_hosts")
    )

    internal val legacyRepository: LegacyHostKeyRepository =
        KnownHostsFileStore(legacyKnownHostsFile.absoluteFile)
    private val knownHostsFileStore: KnownHostsFileStore
        get() = legacyRepository as KnownHostsFileStore

    override fun snapshotHost(host: String, port: Int): HostTrustSnapshot {
        val appSnapshot = super.snapshotHost(host, port)
        val legacyEntries = knownHostsFileStore.snapshot(aliasFor(host, port))
        return appSnapshot.copy(legacyEntries = legacyEntries)
    }

    override fun removeHost(host: String, port: Int) {
        super.removeHost(host, port)
        knownHostsFileStore.remove(aliasFor(host, port))
    }

    override fun restoreHost(host: String, port: Int, snapshot: HostTrustSnapshot) {
        super.restoreHost(host, port, snapshot)
        knownHostsFileStore.restore(aliasFor(host, port), snapshot.legacyEntries)
    }

    private fun aliasFor(host: String, port: Int): String =
        if (port == 22) host else "[$host]:$port"
}

data class HostTrustSnapshot(
    val entries: List<HostKeyStoreBackend.Entry>,
    /** Exact known_hosts records for this endpoint only, never a whole-file image. */
    val legacyEntries: List<String> = emptyList()
)

internal data class LegacyHostCheck(
    val delegatedResult: Int,
    val hasExactEntries: Boolean,
    val exactKeyMatches: Boolean,
    val hasConflictingExactKey: Boolean,
    val hasRevokedPresentedKey: Boolean
)

/** File-backed production and in-memory test adapters share this strict API. */
internal interface LegacyHostKeyRepository {
    fun inspect(repositoryHost: String, key: ByteArray): LegacyHostCheck
    fun replace(repositoryHost: String, algorithm: String, key: ByteArray)
}

/**
 * Adapter used by pure unit tests which supply an in-memory JSch repository.
 * Production always uses [KnownHostsFileStore] so stale delegates never write.
 */
internal class DelegateLegacyHostKeyRepository(
    private val delegate: HostKeyRepository
) : LegacyHostKeyRepository {
    override fun inspect(repositoryHost: String, key: ByteArray): LegacyHostCheck =
        inspectRepository(delegate, repositoryHost, key)

    override fun replace(repositoryHost: String, algorithm: String, key: ByteArray) {
        delegate.remove(repositoryHost, null)
        delegate.add(HostKey(hashKnownHost(repositoryHost), key), null)
        val check = inspect(repositoryHost, key)
        if (!check.exactKeyMatches || check.hasConflictingExactKey) {
            throw IllegalStateException("known_hosts 同步失败")
        }
    }
}

/**
 * Process-wide, linearizable access to one known_hosts file.
 *
 * Every operation reloads current disk state under a canonical-path lock. A
 * mutation edits exact endpoint tokens only (never wildcard/shared patterns),
 * verifies a same-directory staging file with a fresh JSch parser, and then
 * atomically replaces the real file. This avoids both stale-JSch lost updates
 * and KnownHosts.remove()/add() silently swallowing a sync IOException.
 */
internal class KnownHostsFileStore(private val file: File) : LegacyHostKeyRepository {
    private val absoluteFile = file.absoluteFile
    private val lock = lockFor(absoluteFile)

    override fun inspect(repositoryHost: String, key: ByteArray): LegacyHostCheck =
        synchronized(lock) {
            inspectRepository(loadRepository(absoluteFile), repositoryHost, key)
        }

    override fun replace(repositoryHost: String, algorithm: String, key: ByteArray) {
        synchronized(lock) {
            val retained = removeExactAlias(loadLines(), repositoryHost).toMutableList()
            retained += "${hashKnownHost(repositoryHost)} $algorithm ${Base64.getEncoder().encodeToString(key)}"
            commit(retained) { candidate ->
                val check = inspectRepository(loadRepository(candidate), repositoryHost, key)
                if (!check.exactKeyMatches || check.hasConflictingExactKey) {
                    throw IOException("known_hosts 同步校验失败")
                }
            }
        }
    }

    fun snapshot(repositoryHost: String): List<String> = synchronized(lock) {
        loadLines().mapNotNull { line ->
            val parsed = parseKnownHostLine(line) ?: return@mapNotNull null
            val matching = parsed.hosts.filter { hostPatternMatches(it, repositoryHost) }
            matching.takeIf { it.isNotEmpty() }?.let(parsed::render)
        }
    }

    fun remove(repositoryHost: String) {
        synchronized(lock) {
            if (!absoluteFile.exists()) return
            val current = loadLines()
            val retained = removeExactAlias(current, repositoryHost)
            if (retained == current) return
            commit(retained) { candidate ->
                val exact = loadRepository(candidate).hostKey.any {
                    hostKeyMatches(it, repositoryHost)
                }
                if (exact) throw IOException("known_hosts 主机记录删除失败")
            }
        }
    }

    fun restore(repositoryHost: String, snapshot: List<String>) {
        synchronized(lock) {
            val current = loadLines()
            val restored = removeExactAlias(current, repositoryHost).toMutableList()
            restored += snapshot
            if (restored == current) return
            commit(restored) { candidate ->
                val actual = loadRepository(candidate).hostKey
                    .filter { hostKeyMatches(it, repositoryHost) }
                    .map { it.type to it.key }
                    .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
                val expected = repositoryFromLines(snapshot).hostKey
                    .filter { hostKeyMatches(it, repositoryHost) }
                    .map { it.type to it.key }
                    .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
                if (actual != expected) throw IOException("known_hosts 回滚校验失败")
            }
        }
    }

    private fun loadLines(): List<String> =
        if (absoluteFile.exists()) absoluteFile.readLines() else emptyList()

    private fun commit(lines: List<String>, verify: (File) -> Unit) {
        val parent = absoluteFile.parentFile ?: File(".").absoluteFile
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("无法创建 known_hosts 目录")
        }
        val staging = Files.createTempFile(
            parent.toPath(),
            ".${absoluteFile.name}.",
            ".staging"
        ).toFile()
        try {
            staging.writeText(lines.joinToString("\n").let { if (it.isEmpty()) "" else "$it\n" })
            verify(staging)
            try {
                Files.move(
                    staging.toPath(),
                    absoluteFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    staging.toPath(),
                    absoluteFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } finally {
            if (staging.exists()) staging.delete()
        }
    }

    private fun removeExactAlias(lines: List<String>, repositoryHost: String): List<String> =
        lines.mapNotNull { line ->
            val parsed = parseKnownHostLine(line) ?: return@mapNotNull line
            val remaining = parsed.hosts.filterNot { hostPatternMatches(it, repositoryHost) }
            remaining.takeIf { it.isNotEmpty() }?.let(parsed::render)
        }

    private companion object {
        private val LOCKS = mutableMapOf<String, Any>()

        fun lockFor(file: File): Any = synchronized(LOCKS) {
            val path = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
            LOCKS.getOrPut(path) { Any() }
        }
    }
}

private data class ParsedKnownHostLine(
    val leading: String,
    val marker: String?,
    val hosts: List<String>,
    val suffix: String
) {
    fun render(selectedHosts: List<String>): String = buildString {
        append(leading)
        marker?.let { append(it).append(' ') }
        append(selectedHosts.joinToString(","))
        append(suffix)
    }
}

private val KNOWN_HOST_LINE = Regex(
    """^(\s*)(?:(@\S+)\s+)?(\S+)(\s+\S+\s+\S+(?:\s+.*)?)\s*$"""
)

private fun parseKnownHostLine(line: String): ParsedKnownHostLine? {
    if (line.isBlank() || line.trimStart().startsWith('#')) return null
    val match = KNOWN_HOST_LINE.matchEntire(line) ?: return null
    return ParsedKnownHostLine(
        leading = match.groupValues[1],
        marker = match.groupValues[2].takeIf(String::isNotEmpty),
        hosts = match.groupValues[3].split(','),
        suffix = match.groupValues[4]
    )
}

private fun repositoryFromLines(lines: List<String>): HostKeyRepository {
    val jsch = JSch()
    jsch.setKnownHosts(lines.joinToString("\n").byteInputStream())
    return jsch.hostKeyRepository
}

private fun loadRepository(file: File): HostKeyRepository {
    val jsch = JSch()
    if (file.exists()) jsch.setKnownHosts(file.absolutePath)
    return jsch.hostKeyRepository
}

private fun inspectRepository(
    repository: HostKeyRepository,
    repositoryHost: String,
    key: ByteArray
): LegacyHostCheck {
    val exactEntries = repository.hostKey.filter { hostKeyMatches(it, repositoryHost) }
    val matchingEntries = exactEntries.filter { decodeHostKey(it).contentEquals(key) }
    val revokedPresentedKey = repository.getHostKey(repositoryHost, null).any {
        it.marker.equals("@revoked", ignoreCase = true) &&
            decodeHostKey(it).contentEquals(key)
    }
    // JSch 0.2.18 preserves @revoked but its check() still returns OK for the
    // same key. Only an unmarked exact record is eligible for legacy migration.
    val acceptedMatches = matchingEntries.count { it.marker.isNullOrEmpty() }
    return LegacyHostCheck(
        delegatedResult = repository.check(repositoryHost, key),
        hasExactEntries = exactEntries.isNotEmpty(),
        exactKeyMatches = acceptedMatches > 0,
        hasConflictingExactKey = exactEntries.size != acceptedMatches,
        hasRevokedPresentedKey = revokedPresentedKey
    )
}

internal fun decodeHostKey(hostKey: HostKey): ByteArray = try {
    Base64.getDecoder().decode(hostKey.key)
} catch (_: IllegalArgumentException) {
    ByteArray(0)
}

internal fun hostKeyMatches(hostKey: HostKey, repositoryHost: String): Boolean =
    hostKey.host.split(',').any { hostPatternMatches(it, repositoryHost) }

private fun hostPatternMatches(pattern: String, repositoryHost: String): Boolean =
    when {
        pattern.startsWith("|1|") -> hashedHostMatches(pattern, repositoryHost)
        else -> pattern.equals(repositoryHost, ignoreCase = true)
    }

private fun hashedHostMatches(pattern: String, repositoryHost: String): Boolean {
    val parts = pattern.split('|')
    if (parts.size != 4 || parts[1] != "1") return false
    return try {
        val salt = Base64.getDecoder().decode(parts[2])
        val expected = Base64.getDecoder().decode(parts[3])
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(salt, "HmacSHA1"))
        MessageDigest.isEqual(
            expected,
            mac.doFinal(repositoryHost.toByteArray(Charsets.UTF_8))
        )
    } catch (_: Exception) {
        false
    }
}

internal fun hashKnownHost(repositoryHost: String): String {
    val salt = ByteArray(20).also(SecureRandom()::nextBytes)
    val mac = Mac.getInstance("HmacSHA1")
    mac.init(SecretKeySpec(salt, "HmacSHA1"))
    val digest = mac.doFinal(repositoryHost.toByteArray(Charsets.UTF_8))
    val encoder = Base64.getEncoder()
    return "|1|${encoder.encodeToString(salt)}|${encoder.encodeToString(digest)}"
}

interface HostTrustStore {
    fun snapshotHost(host: String, port: Int): HostTrustSnapshot
    fun removeHost(host: String, port: Int)
    fun restoreHost(host: String, port: Int, snapshot: HostTrustSnapshot)
}

open class HostKeyStoreBackend(
    private val storeFile: File
) : HostTrustStore {
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
    override open fun removeHost(host: String, port: Int) {
        val hostId = hashedHostId(host, port)
        synchronized(lock) {
            val remaining = loadEntriesLocked().filterNot {
                it.hostId == hostId && it.port == port
            }
            writeEntriesLocked(remaining)
        }
    }

    /** Capture the exact app-owned pin state for repository rollback. */
    override open fun snapshotHost(host: String, port: Int): HostTrustSnapshot {
        val hostId = hashedHostId(host, port)
        return synchronized(lock) {
            HostTrustSnapshot(
                loadEntriesLocked().filter { it.hostId == hostId && it.port == port }
            )
        }
    }

    /** Restore a prior endpoint snapshot after a failed cross-store mutation. */
    override open fun restoreHost(host: String, port: Int, snapshot: HostTrustSnapshot) {
        val hostId = hashedHostId(host, port)
        synchronized(lock) {
            val entries = loadEntriesLocked().filterNot {
                it.hostId == hostId && it.port == port
            }.toMutableList()
            entries += snapshot.entries
            writeEntriesLocked(entries)
        }
    }


    fun fingerprint(key: String): String {
        val normalized = try {
            Base64.getDecoder().decode(key)
        } catch (_: IllegalArgumentException) {
            key.toByteArray(Charsets.UTF_8)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized)
        return digest.joinToString(separator = ":") {
            String.format(Locale.ROOT, "%02X", it)
        }
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
        return "h:" + md.digest().joinToString(separator = "") {
            String.format(Locale.ROOT, "%02x", it)
        }
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

    protected fun writeBytesAtomically(file: File, bytes: ByteArray) {
        val parent = file.parentFile ?: File(".")
        parent.mkdirs()
        val tmp = File(parent, "${file.name}.tmp")
        tmp.writeBytes(bytes)
        try {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: Exception) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
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
