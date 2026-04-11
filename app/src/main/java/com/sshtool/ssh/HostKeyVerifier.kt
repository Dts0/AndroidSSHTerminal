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

class TrustingHostKeyRepository(
    private val delegate: HostKeyRepository,
    private val verifier: HostKeyVerifier
) : HostKeyRepository {
    private fun detectHostKeyAlgorithm(key: ByteArray): String {
        val encoded = Base64.getEncoder().encodeToString(key)
        return when {
            encoded.contains("c3NoLWVkMjU1MTk") -> "ssh-ed25519"
            encoded.contains("ZWNiMnNoYTItbmlzdHAyNTY") -> "ecdsa-sha2-nistp256"
            encoded.contains("ZWNiMnNoYTItbmlzdHAzODQ") -> "ecdsa-sha2-nistp384"
            encoded.contains("ZWNiMnNoYTItbmlzdHA1MjE") -> "ecdsa-sha2-nistp521"
            encoded.contains("c3NoLXJzYQ") -> "ssh-rsa"
            else -> "ssh-rsa"
        }
    }
    override fun check(host: String?, key: ByteArray?): Int {
        if (host == null || key == null) return HostKeyRepository.NOT_INCLUDED
        val delegated = delegate.check(host, key)
        if (delegated == HostKeyRepository.OK || delegated == HostKeyRepository.CHANGED) {
            return delegated
        }
        return when (verifier.verify(detectHostKeyAlgorithm(key), key)) {
            VerificationResult.Trusted -> HostKeyRepository.OK
            is VerificationResult.Untrusted -> HostKeyRepository.NOT_INCLUDED
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
