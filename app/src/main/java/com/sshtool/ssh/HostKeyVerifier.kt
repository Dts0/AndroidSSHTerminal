package com.sshtool.ssh

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import java.util.Base64

class HostKeyVerifier(
    private val host: String,
    private val port: Int,
    private val trustStore: HostKeyTrustStore
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
 * Semantics of [check]:
 *  - If JSch's own known_hosts already accepts the key ([OK]) → OK.
 *  - If the host has a trusted pin in the app's store and the presented key
 *    matches it → OK.
 *  - If JSch reports [CHANGED] (a pinned known_hosts key differs) → we MUST
 *    propagate [CHANGED]. This makes JSch throw, surfacing a host-key-change
 *    warning to the user instead of silently connecting to a possibly
 *    tampered host. Returning OK here would be a MITM bypass.
 *  - Otherwise (first connect, no pin) → NOT_INCLUDED so JSch throws and the
 *    app can show the first-time trust prompt with the fingerprint.
 *
 * Renamed from `TrustingHostKeyRepository`: the old name implied the repo
 * trusts everything, which is the opposite of the intended strict behavior.
 */
class StrictHostKeyRepository(
    private val delegate: HostKeyRepository,
    private val verifier: HostKeyVerifier,
    private val host: String,
    private val port: Int,
    private val trustStore: HostKeyTrustStore
) : HostKeyRepository {

    /** The last rejected key (algorithm + SHA-256 fingerprint), or null if none. */
    @Volatile
    var lastRejectedKey: RejectedKey? = null
        private set

    data class RejectedKey(val algorithm: String, val fingerprint: String, val encodedKey: String)

    override fun check(host: String?, key: ByteArray?): Int {
        if (host == null || key == null) return HostKeyRepository.NOT_INCLUDED

        val delegated = delegate.check(host, key)
        if (delegated == HostKeyRepository.OK) return HostKeyRepository.OK

        // Not in known_hosts. Decide via the app's own trust store.
        val algorithm = detectHostKeyAlgorithm(key)
        if (algorithm == null) {
            // Cannot determine the algorithm — fail closed.
            return HostKeyRepository.NOT_INCLUDED
        }
        val encodedKey = java.util.Base64.getEncoder().encodeToString(key)

        val firstConnect = !trustStore.hasTrustedHost(this.host, this.port)
        // Record the rejected key so the caller can surface it to the user
        // without relying on JSch's exception text or session.hostKey.
        lastRejectedKey = RejectedKey(algorithm, trustStore.fingerprint(encodedKey), encodedKey)

        // A previously pinned (in known_hosts) key has changed. Never silently
        // accept — surface it so the app can warn the user.
        if (delegated == HostKeyRepository.CHANGED) return HostKeyRepository.CHANGED

        // First connect for this host (no pin at all) → let the app prompt.
        if (firstConnect) return HostKeyRepository.NOT_INCLUDED

        // A pin exists for this host but the presented key didn't match → treat
        // as a changed/different key (CHANGED) so the user is warned.
        return when (verifier.verify(algorithm, key)) {
            VerificationResult.Trusted -> HostKeyRepository.OK
            is VerificationResult.Untrusted -> HostKeyRepository.CHANGED
        }
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
