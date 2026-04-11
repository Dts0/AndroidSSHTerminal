package com.sshtool.data.repository

import android.content.Context
import androidx.room.*
import com.sshtool.data.crypto.PasswordStore
import com.sshtool.data.model.Host
import kotlinx.coroutines.flow.Flow

@Dao
interface HostDao {
    @Query("SELECT * FROM hosts ORDER BY lastConnected DESC, name ASC")
    fun getAllHosts(): Flow<List<Host>>

    @Query("SELECT * FROM hosts WHERE id = :id")
    suspend fun getHostById(id: Long): Host?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHost(host: Host): Long

    @Update
    suspend fun updateHost(host: Host)

    @Delete
    suspend fun deleteHost(host: Host)

    @Query("UPDATE hosts SET lastConnected = :timestamp WHERE id = :hostId")
    suspend fun updateLastConnected(hostId: Long, timestamp: Long)
}

@Database(entities = [Host::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun hostDao(): HostDao
}

class HostRepository(context: Context) {

    private val database = Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        "ssh_tool_db"
    ).build()

    private val hostDao = database.hostDao()
    private val passwordStore = PasswordStore(context)

    fun getAllHosts(): Flow<List<Host>> = hostDao.getAllHosts()

    suspend fun getHostById(id: Long): Host? = hostDao.getHostById(id)?.withSecrets()

    suspend fun saveHost(host: Host): Long {
        val sanitizedHost = host.sanitizedForDatabase()
        val hostId = hostDao.insertHost(sanitizedHost)

        host.password?.let { passwordStore.savePassword(hostId, it) }
        host.privateKey?.let { passwordStore.savePrivateKey(hostId, it) }
        host.passphrase?.let { passwordStore.savePassphrase(hostId, it) }

        return hostId
    }

    suspend fun updateHost(host: Host) {
        hostDao.updateHost(host.sanitizedForDatabase())

        if (host.useKeyAuth) {
            passwordStore.deletePassword(host.id)
            host.privateKey?.let { passwordStore.savePrivateKey(host.id, it) }
                ?: passwordStore.deletePrivateKey(host.id)
            host.passphrase?.let { passwordStore.savePassphrase(host.id, it) }
                ?: passwordStore.deletePassphrase(host.id)
        } else {
            host.password?.let { passwordStore.savePassword(host.id, it) }
                ?: passwordStore.deletePassword(host.id)
            passwordStore.deletePrivateKey(host.id)
            passwordStore.deletePassphrase(host.id)
        }
    }

    suspend fun deleteHost(host: Host) {
        passwordStore.deletePassword(host.id)
        passwordStore.deletePrivateKey(host.id)
        passwordStore.deletePassphrase(host.id)
        hostDao.deleteHost(host.sanitizedForDatabase())
    }

    suspend fun updateLastConnected(hostId: Long) {
        hostDao.updateLastConnected(hostId, System.currentTimeMillis())
    }

    fun getPassword(hostId: Long): String? = passwordStore.getPassword(hostId)

    fun getPrivateKey(hostId: Long): String? = passwordStore.getPrivateKey(hostId)

    fun getPassphrase(hostId: Long): String? = passwordStore.getPassphrase(hostId)

    private fun Host.sanitizedForDatabase(): Host = copy().also {
        it.password = null
        it.privateKey = null
        it.passphrase = null
    }

    private fun Host.withSecrets(): Host = this.also {
        it.password = passwordStore.getPassword(it.id)
        it.privateKey = passwordStore.getPrivateKey(it.id)
        it.passphrase = passwordStore.getPassphrase(it.id)
    }
}

