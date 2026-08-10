package com.sshtool.ssh

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

class HostKeyVerifier(
    private val host: String,
    private val port: Int,
    private val trustStore: HostKeyStoreBackend
) {
    fun verify(serverHostKey: HostKey): VerificationResult {
        return verify(serverHostKey.type, serverHostKey.key)
    }

    fun verify(algorithm: String, rawKey: ByteArray): VerificationResult {
        val encodedKey = Base64.getEncoder().encodeToString(rawKey)
        return verify(algorithm, encodedKey)
    }

    private fun verify(algorithm: String, encodedKey: String): VerificationResult {
        return if (trustStore.isTrusted(host, port, algorithm, encodedKey)) {
            VerificationResult.Trusted
        } else {
            VerificationResult.Untrusted(
                algorithm = algorithm,
                fingerprint = trustStore.fingerprint(encodedKey)
            )
        }
    }
}

sealed class VerificationResult {
    data object Trusted : VerificationResult()
    data class Untrusted(
        val algorithm: String,
        val fingerprint: String
    ) : VerificationResult()
}

/**
 * Parses the algorithm name from the leading length-prefixed field of an SSH
 * public key blob (RFC 4253 §6.6). Returns null on any malformation rather
 * than guessing an algorithm — a failed parse must never be coerced into a
 * specific algorithm such as "ssh-rsa", since that could cause a presented
 * key to be verified against the wrong trust entry.
 */
internal fun detectHostKeyAlgorithm(key: ByteArray): String? {
    return try {
        if (key.size < 4) return null
        val algoLen = ((key[0].toInt() and 0xFF) shl 24) or
            ((key[1].toInt() and 0xFF) shl 16) or
            ((key[2].toInt() and 0xFF) shl 8) or
            (key[3].toInt() and 0xFF)
        if (algoLen <= 0 || algoLen > key.size - 4) return null
        String(key, 4, algoLen, Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }
}

/**
 * Host-key repository that wires the app's TOFU trust store into JSch.
 *
 * The app-owned pin is authoritative. JSch known_hosts is retained only as a
 * legacy migration source: a legacy OK is copied into the app store, while a
 * valid app pin repairs a stale/missing delegate entry before connection.
 *
 * Renamed from `TrustingHostKeyRepository`: the old name implied the repo
 * trusts everything, which is the opposite of the intended strict behavior.
 */
internal class StrictHostKeyRepository(
    private val delegate: HostKeyRepository,
    private val verifier: HostKeyVerifier,
    private val host: String,
    private val port: Int,
    private val trustStore: HostKeyStoreBackend,
    private val legacyRepository: LegacyHostKeyRepository =
        DelegateLegacyHostKeyRepository(delegate)
) : HostKeyRepository {

    private data class PendingHostKeyPersistence(
        val repositoryHost: String,
        val algorithm: String,
        val encodedKey: String,
        val rawKey: ByteArray,
        val trustAppPin: Boolean,
        val repairLegacyEntry: Boolean
    )

    private val pendingPersistence = AtomicReference<PendingHostKeyPersistence?>(null)

    /** The last rejected key (algorithm + SHA-256 fingerprint), or null if none. */
    @Volatile
    var lastRejectedKey: RejectedKey? = null
        private set

    enum class RejectionReason {
        FIRST_USE,
        CHANGED
    }

    data class RejectedKey(
        val algorithm: String,
        val fingerprint: String,
        val encodedKey: String,
        val reason: RejectionReason
    )

    override fun check(host: String?, key: ByteArray?): Int {
        // This repository is reused for the remainder of one connection
        // attempt. Never let a key examined earlier in that attempt make an
        // unrelated authentication/channel exception look like a host-key
        // rejection (for example, a bad password after a valid pinned key).
        lastRejectedKey = null
        pendingPersistence.set(null)
        if (host == null || key == null) return HostKeyRepository.NOT_INCLUDED

        val algorithm = detectHostKeyAlgorithm(key)
        if (algorithm == null) {
            // Cannot determine the algorithm — fail closed.
            return HostKeyRepository.NOT_INCLUDED
        }
        val encodedKey = java.util.Base64.getEncoder().encodeToString(key)

        // Once an app-owned pin exists it is authoritative. In particular, an
        // obsolete known_hosts entry must not (a) block a user-confirmed key or
        // (b) silently re-authorize an older key via delegate.OK.
        if (trustStore.hasTrustedHost(this.host, this.port)) {
            return when (verifier.verify(algorithm, key)) {
                VerificationResult.Trusted -> {
                    // The app pin is sufficient for this handshake. Repairing
                    // legacy known_hosts is deferred until the manager can
                    // verify the Room row/endpoint under HostRepository's
                    // mutation mutex; writing here could race a delete/edit and
                    // resurrect an orphan entry after cleanup.
                    val legacy = runCatching {
                        legacyRepository.inspect(host, key)
                    }.getOrNull()
                    if (legacy == null ||
                        legacy.delegatedResult != HostKeyRepository.OK ||
                        !legacy.exactKeyMatches || legacy.hasConflictingExactKey) {
                        pendingPersistence.set(
                            PendingHostKeyPersistence(
                                repositoryHost = host,
                                algorithm = algorithm,
                                encodedKey = encodedKey,
                                rawKey = key.copyOf(),
                                trustAppPin = false,
                                repairLegacyEntry = true
                            )
                        )
                    }
                    HostKeyRepository.OK
                }
                is VerificationResult.Untrusted -> reject(
                    algorithm,
                    encodedKey,
                    HostKeyRepository.CHANGED
                )
            }
        }

        // No app pin exists. Treat JSch known_hosts only as a legacy fallback
        // for installs that populated it before the app-owned pin store became
        // authoritative.
        val legacy = legacyRepository.inspect(host, key)
        return when {
            legacy.hasRevokedPresentedKey -> reject(
                algorithm,
                encodedKey,
                HostKeyRepository.CHANGED
            )
            legacy.exactKeyMatches ||
                (port == 22 && legacy.delegatedResult == HostKeyRepository.OK &&
                    !legacy.hasExactEntries) -> {
                // One-time migration from legacy known_hosts. Future checks use
                // the app pin as the sole authority, preventing a stale legacy
                // key from being accepted as an alternate key.
                pendingPersistence.set(
                    PendingHostKeyPersistence(
                        repositoryHost = host,
                        algorithm = algorithm,
                        encodedKey = encodedKey,
                        rawKey = key.copyOf(),
                        trustAppPin = true,
                        repairLegacyEntry = false
                    )
                )
                HostKeyRepository.OK
            }
            legacy.hasExactEntries ||
                (port == 22 && legacy.delegatedResult == HostKeyRepository.CHANGED) -> reject(
                algorithm,
                encodedKey,
                HostKeyRepository.CHANGED
            )
            else -> reject(
                algorithm,
                encodedKey,
                HostKeyRepository.NOT_INCLUDED
            )
        }
    }

    /**
     * Persist a key accepted during check(), after the manager has linearized
     * this action with edit/delete and revalidated the transport generation.
     * No pending action is a successful no-op; write failures are propagated.
     */
    fun persistPendingHostKey() {
        val pending = pendingPersistence.getAndSet(null) ?: return
        if (pending.trustAppPin) {
            trustStore.trust(
                this.host,
                this.port,
                pending.algorithm,
                pending.encodedKey
            )
        }
        if (pending.repairLegacyEntry) {
            legacyRepository.replace(
                pending.repositoryHost,
                pending.algorithm,
                pending.rawKey
            )
        }
    }

    private fun reject(algorithm: String, encodedKey: String, result: Int): Int {
        lastRejectedKey = RejectedKey(
            algorithm = algorithm,
            fingerprint = trustStore.fingerprint(encodedKey),
            encodedKey = encodedKey,
            reason = if (result == HostKeyRepository.CHANGED) {
                RejectionReason.CHANGED
            } else {
                RejectionReason.FIRST_USE
            }
        )
        return result
    }

    override fun add(hostkey: HostKey?, ui: com.jcraft.jsch.UserInfo?) {
        delegate.add(hostkey, ui)
    }

    override fun remove(host: String?, type: String?) {
        delegate.remove(host, type)
    }

    override fun remove(host: String?, type: String?, key: ByteArray?) {
        delegate.remove(host, type, key)
    }

    override fun getHostKey(): Array<HostKey> = delegate.hostKey

    override fun getHostKey(host: String?, type: String?): Array<HostKey> = delegate.getHostKey(host, type)

    override fun getKnownHostsRepositoryID(): String = delegate.knownHostsRepositoryID
}
