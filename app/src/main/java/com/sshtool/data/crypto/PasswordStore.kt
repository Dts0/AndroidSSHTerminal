package com.sshtool.data.crypto

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 安全的密码存储，使用 Android Keystore 加密
 */
class PasswordStore(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "ssh_passwords"
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /**
     * 保存密码
     */
    fun savePassword(hostId: Long, password: String) {
        encryptedPrefs.edit().putString("password_$hostId", password).apply()
    }

    /**
     * 获取密码
     */
    fun getPassword(hostId: Long): String? {
        return encryptedPrefs.getString("password_$hostId", null)
    }

    /**
     * 删除密码
     */
    fun deletePassword(hostId: Long) {
        encryptedPrefs.edit().remove("password_$hostId").apply()
    }

    /**
     * 保存私钥
     */
    fun savePrivateKey(hostId: Long, privateKey: String) {
        encryptedPrefs.edit().putString("privateKey_$hostId", privateKey).apply()
    }

    /**
     * 获取私钥
     */
    fun getPrivateKey(hostId: Long): String? {
        return encryptedPrefs.getString("privateKey_$hostId", null)
    }

    /**
     * 删除私钥
     */
    fun deletePrivateKey(hostId: Long) {
        encryptedPrefs.edit().remove("privateKey_$hostId").apply()
    }

    /**
     * 保存 passphrase
     */
    fun savePassphrase(hostId: Long, passphrase: String) {
        encryptedPrefs.edit().putString("passphrase_$hostId", passphrase).apply()
    }

    /**
     * 获取 passphrase
     */
    fun getPassphrase(hostId: Long): String? {
        return encryptedPrefs.getString("passphrase_$hostId", null)
    }

    /**
     * 删除 passphrase
     */
    fun deletePassphrase(hostId: Long) {
        encryptedPrefs.edit().remove("passphrase_$hostId").apply()
    }
}

