package com.sshtool.data.repository

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
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

@Database(entities = [Host::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun hostDao(): HostDao

    companion object {
        /**
         * Room migrations for [AppDatabase]. Add a `Migration(N, N+1)` here for
         * every schema change and bump the @Database version. Without an
         * explicit migration Room would crash on upgrade for existing users.
         *
         * Schema JSON is exported (`exportSchema = true`) to
         * `app/schemas/.../N.json`; commit those files — they are what
         * migration authoring is validated against.
         */
        val MIGRATIONS: Array<Migration> = arrayOf(
            // e.g. object MIGRATION_1_2 : Migration(1, 2) { override fun migrate(db: SupportSQLiteDatabase) { ... } }
        )
    }
}

class HostRepository(context: Context) {

    private val database = Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        "ssh_tool_db"
    )
        .addMigrations(*AppDatabase.MIGRATIONS)
        // Safety net: if a schema change ships without an explicit migration
        // (or the installed DB is newer than the code knows about), recreate
        // the DB instead of crashing on launch and bricking the app. Host
        // metadata is lost in that case, but secrets live in PasswordStore and
        // survive. Always prefer adding a real migration above.
        .fallbackToDestructiveMigration()
        .build()

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

    private fun Host.sanitizedForDatabase(): Host = sanitizeForDatabase()

    private fun Host.withSecrets(): Host = this.also {
        it.password = passwordStore.getPassword(it.id)
        it.privateKey = passwordStore.getPrivateKey(it.id)
        it.passphrase = passwordStore.getPassphrase(it.id)
    }
}

/**
 * Return a copy of this [Host] with all secret fields (@Ignore password /
 * privateKey / passphrase) cleared, so they are never written to Room.
 * Internal so it can be unit-tested — secrets must never reach the DB.
 */
internal fun Host.sanitizeForDatabase(): Host = copy().also {
    it.password = null
    it.privateKey = null
    it.passphrase = null
}

