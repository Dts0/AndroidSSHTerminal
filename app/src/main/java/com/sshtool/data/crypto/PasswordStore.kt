@file:Suppress("DEPRECATION")

package com.sshtool.data.crypto

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException

data class StoredCredentials(
    val password: String? = null,
    val privateKey: String? = null,
    val passphrase: String? = null
)

interface CredentialStore {
    fun requireAvailable()
    fun getCredentials(hostId: Long): StoredCredentials
    fun replaceCredentials(hostId: Long, credentials: StoredCredentials)
}

/**
 * 安全的密码存储，使用 Android Keystore 加密。
 *
 * Keystore 可能在某些情况下失效（生物识别变更、OEM 恢复出厂、密钥轮换），
 * 此时构造 EncryptedSharedPreferences 会抛 GeneralSecurityException / AEAD
 * 异常。这里选择 fail closed：应用仍可打开主机列表，但不会把 SSH 凭据
 * 降级保存到明文 SharedPreferences。
 */
class PasswordStore(context: Context) : CredentialStore {

    companion object {
        private const val PREFS_NAME = "ssh_passwords"
    }

    /**
     * Whether the backing store is encrypted and writable. False means
     * Keystore-backed storage could not be initialized; writes will fail rather
     * than silently storing credentials in plaintext.
     */
    var isSecure: Boolean = false
        private set

    private val prefs: SharedPreferences?

    init {
        val secure = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ).also {
                isSecure = true
            }
        } catch (_: Exception) {
            isSecure = false
            null
        }
        prefs = secure
    }

    /**
     * 保存密码
     */
    @Synchronized
    fun savePassword(hostId: Long, password: String) {
        replaceCredentials(hostId, getCredentials(hostId).copy(password = password))
    }

    /**
     * 获取密码
     */
    fun getPassword(hostId: Long): String? = readString("password_$hostId")

    /**
     * 删除密码
     */
    @Synchronized
    fun deletePassword(hostId: Long) {
        if (prefs == null) return
        replaceCredentials(hostId, getCredentials(hostId).copy(password = null))
    }

    /**
     * 保存私钥
     */
    @Synchronized
    fun savePrivateKey(hostId: Long, privateKey: String) {
        replaceCredentials(hostId, getCredentials(hostId).copy(privateKey = privateKey))
    }

    /**
     * 获取私钥
     */
    fun getPrivateKey(hostId: Long): String? = readString("privateKey_$hostId")

    /**
     * 删除私钥
     */
    @Synchronized
    fun deletePrivateKey(hostId: Long) {
        if (prefs == null) return
        replaceCredentials(hostId, getCredentials(hostId).copy(privateKey = null))
    }

    /**
     * 保存 passphrase
     */
    @Synchronized
    fun savePassphrase(hostId: Long, passphrase: String) {
        replaceCredentials(hostId, getCredentials(hostId).copy(passphrase = passphrase))
    }

    /**
     * 获取 passphrase
     */
    fun getPassphrase(hostId: Long): String? = readString("passphrase_$hostId")

    /**
     * 删除 passphrase
     */
    @Synchronized
    fun deletePassphrase(hostId: Long) {
        if (prefs == null) return
        replaceCredentials(hostId, getCredentials(hostId).copy(passphrase = null))
    }

    override fun requireAvailable() {
        securePrefs()
    }

    /**
     * Read all credentials as one logical record. Per-value decryption failures
     * are surfaced as an explicit unavailable-store error instead of being
     * mistaken for a user who never saved a credential.
     */
    @Synchronized
    override fun getCredentials(hostId: Long): StoredCredentials {
        // An unavailable Keystore is not equivalent to an account with no
        // saved secret. Surface it so callers do not misreport the failure as
        // an ordinary SSH authentication error.
        requireAvailable()
        return StoredCredentials(
            password = readString("password_$hostId"),
            privateKey = readString("privateKey_$hostId"),
            passphrase = readString("passphrase_$hostId")
        )
    }

    /**
     * Replace all credential fields with one SharedPreferences transaction.
     * commit() is intentional: callers use its result for repository rollback,
     * which is impossible with fire-and-forget apply(). This method is called on
     * Dispatchers.IO by HostRepository.
     */
    @Synchronized
    override fun replaceCredentials(hostId: Long, credentials: StoredCredentials) {
        val editor = securePrefs().edit()
        editor.putOrRemove("password_$hostId", credentials.password)
        editor.putOrRemove("privateKey_$hostId", credentials.privateKey)
        editor.putOrRemove("passphrase_$hostId", credentials.passphrase)
        if (!editor.commit()) {
            throw IOException("安全凭据写入失败")
        }
    }

    private fun readString(key: String): String? {
        val currentPrefs = securePrefs()
        return try {
            currentPrefs.getString(key, null)
        } catch (e: Exception) {
            throw IllegalStateException("安全凭据存储不可用，请检查 Android Keystore 状态后重试。", e)
        }
    }

    private fun SharedPreferences.Editor.putOrRemove(key: String, value: String?) {
        if (value == null) remove(key) else putString(key, value)
    }

    private fun securePrefs(): SharedPreferences =
        prefs ?: throw IllegalStateException("安全凭据存储不可用，请检查 Android Keystore 状态后重试。")
}
