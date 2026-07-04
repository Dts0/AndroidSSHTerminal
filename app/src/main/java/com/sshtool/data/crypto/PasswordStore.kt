@file:Suppress("DEPRECATION")

package com.sshtool.data.crypto

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 安全的密码存储，使用 Android Keystore 加密。
 *
 * Keystore 可能在某些情况下失效（生物识别变更、OEM 恢复出厂、密钥轮换），
 * 此时构造 EncryptedSharedPreferences 会抛 GeneralSecurityException / AEAD
 * 异常。这里选择 fail closed：应用仍可打开主机列表，但不会把 SSH 凭据
 * 降级保存到明文 SharedPreferences。
 */
class PasswordStore(private val context: Context) {

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
    fun savePassword(hostId: Long, password: String) {
        securePrefs().edit().putString("password_$hostId", password).apply()
    }

    /**
     * 获取密码
     */
    fun getPassword(hostId: Long): String? {
        return prefs?.getString("password_$hostId", null)
    }

    /**
     * 删除密码
     */
    fun deletePassword(hostId: Long) {
        prefs?.edit()?.remove("password_$hostId")?.apply()
    }

    /**
     * 保存私钥
     */
    fun savePrivateKey(hostId: Long, privateKey: String) {
        securePrefs().edit().putString("privateKey_$hostId", privateKey).apply()
    }

    /**
     * 获取私钥
     */
    fun getPrivateKey(hostId: Long): String? {
        return prefs?.getString("privateKey_$hostId", null)
    }

    /**
     * 删除私钥
     */
    fun deletePrivateKey(hostId: Long) {
        prefs?.edit()?.remove("privateKey_$hostId")?.apply()
    }

    /**
     * 保存 passphrase
     */
    fun savePassphrase(hostId: Long, passphrase: String) {
        securePrefs().edit().putString("passphrase_$hostId", passphrase).apply()
    }

    /**
     * 获取 passphrase
     */
    fun getPassphrase(hostId: Long): String? {
        return prefs?.getString("passphrase_$hostId", null)
    }

    /**
     * 删除 passphrase
     */
    fun deletePassphrase(hostId: Long) {
        prefs?.edit()?.remove("passphrase_$hostId")?.apply()
    }

    private fun securePrefs(): SharedPreferences =
        prefs ?: throw IllegalStateException("安全凭据存储不可用，请检查 Android Keystore 状态后重试。")
}
