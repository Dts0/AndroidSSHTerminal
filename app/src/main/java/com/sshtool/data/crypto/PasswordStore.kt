package com.sshtool.data.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 安全的密码存储，使用 Android Keystore 加密。
 *
 * Keystore 可能在某些情况下失效（生物识别变更、OEM 恢复出厂、密钥轮换），
 * 此时构造 EncryptedSharedPreferences 会抛 GeneralSecurityException / AEAD
 * 异常。若不加处理会直接让应用启动崩溃并永久丢失所有已存凭据。这里改为：
 * 加密存储构造失败时降级为普通 SharedPreferences（明文，仅设备本地），
 * 保证应用可用，调用方可通过 [isSecure] 感知是否处于降级模式。
 */
class PasswordStore(private val context: Context) {

    companion object {
        private const val TAG = "PasswordStore"
        private const val PREFS_NAME = "ssh_passwords"
        private const val FALLBACK_PREFS_NAME = "ssh_passwords_insecure"
    }

    /**
     * Whether the backing store is encrypted. False means the Keystore could
     * not be initialized and secrets are being stored in plain SharedPreferences
     * as a degraded fallback so the app stays usable. Callers may surface this
     * to the user.
     */
    val isSecure: Boolean

    private val prefs: SharedPreferences

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
        } catch (e: Exception) {
            // Keystore unavailable/invalidated. Avoid crashing on startup and
            // bricking the app; fall back to plain local storage. Previously
            // stored encrypted entries are unreadable, but new saves will work.
            Log.e(TAG, "EncryptedSharedPreferences unavailable, falling back to plain storage", e)
            isSecure = false
            null
        }
        prefs = secure
            ?: context.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 保存密码
     */
    fun savePassword(hostId: Long, password: String) {
        prefs.edit().putString("password_$hostId", password).apply()
    }

    /**
     * 获取密码
     */
    fun getPassword(hostId: Long): String? {
        return prefs.getString("password_$hostId", null)
    }

    /**
     * 删除密码
     */
    fun deletePassword(hostId: Long) {
        prefs.edit().remove("password_$hostId").apply()
    }

    /**
     * 保存私钥
     */
    fun savePrivateKey(hostId: Long, privateKey: String) {
        prefs.edit().putString("privateKey_$hostId", privateKey).apply()
    }

    /**
     * 获取私钥
     */
    fun getPrivateKey(hostId: Long): String? {
        return prefs.getString("privateKey_$hostId", null)
    }

    /**
     * 删除私钥
     */
    fun deletePrivateKey(hostId: Long) {
        prefs.edit().remove("privateKey_$hostId").apply()
    }

    /**
     * 保存 passphrase
     */
    fun savePassphrase(hostId: Long, passphrase: String) {
        prefs.edit().putString("passphrase_$hostId", passphrase).apply()
    }

    /**
     * 获取 passphrase
     */
    fun getPassphrase(hostId: Long): String? {
        return prefs.getString("passphrase_$hostId", null)
    }

    /**
     * 删除 passphrase
     */
    fun deletePassphrase(hostId: Long) {
        prefs.edit().remove("passphrase_$hostId").apply()
    }
}

