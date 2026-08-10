package com.sshtool.data.repository

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import com.sshtool.data.crypto.CredentialStore
import com.sshtool.data.crypto.PasswordStore
import com.sshtool.data.crypto.StoredCredentials
import com.sshtool.data.model.Host
import com.sshtool.ssh.HostKeyTrustStore
import com.sshtool.ssh.HostTrustSnapshot
import com.sshtool.ssh.HostTrustStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One immutable, same-revision input for an SSH transport handshake. */
internal data class ConnectionProfile(
    val host: Host,
    val credentials: StoredCredentials
)

@Dao
interface HostDao {
    @Query("SELECT * FROM hosts ORDER BY lastConnected DESC, name ASC")
    fun getAllHosts(): Flow<List<Host>>

    @Query("SELECT * FROM hosts WHERE id = :id")
    suspend fun getHostById(id: Long): Host?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertHost(host: Host): Long

    @Update
    suspend fun updateHost(host: Host): Int

    @Delete
    suspend fun deleteHost(host: Host): Int

    @Query("UPDATE hosts SET lastConnected = :timestamp WHERE id = :hostId")
    suspend fun updateLastConnected(hostId: Long, timestamp: Long)

    @Query(
        "SELECT COUNT(*) FROM hosts " +
            "WHERE host = :host AND port = :port AND id != :excludedHostId"
    )
    suspend fun countHostsAtEndpointExcluding(host: String, port: Int, excludedHostId: Long): Int
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

class HostRepository {

    // Keep the Room instance strongly reachable for as long as its DAO is used.
    private val database: AppDatabase?
    private val hostDao: HostDao
    private val passwordStore: CredentialStore
    private val trustStore: HostTrustStore
    private val mutationMutex = Mutex()

    constructor(context: Context) {
        database = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "ssh_tool_db"
        )
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        hostDao = database.hostDao()
        passwordStore = PasswordStore(context.applicationContext)
        trustStore = HostKeyTrustStore(context.applicationContext)
    }

    /** Injectable constructor for failure/rollback unit tests. */
    internal constructor(
        hostDao: HostDao,
        passwordStore: CredentialStore,
        trustStore: HostTrustStore
    ) {
        database = null
        this.hostDao = hostDao
        this.passwordStore = passwordStore
        this.trustStore = trustStore
    }

    fun getAllHosts(): Flow<List<Host>> = hostDao.getAllHosts()

    suspend fun getHostById(id: Long): Host? = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            hostDao.getHostById(id)?.withSecrets()
        }
    }

    /** Read Room-backed display/connection metadata without decrypting secrets. */
    internal suspend fun getHostMetadataById(id: Long): Host? = withContext(Dispatchers.IO) {
        mutationMutex.withLock { hostDao.getHostById(id) }
    }

    /**
     * Read metadata and secrets while repository mutations are excluded. An
     * edit writes credentials and Room metadata in separate stores, so reading
     * either side without this mutex can pair an old endpoint/user with a new
     * password or key.
     */
    internal suspend fun getConnectionProfile(id: Long): ConnectionProfile? =
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                val host = hostDao.getHostById(id)?.copy() ?: return@withLock null
                ConnectionProfile(host, passwordStore.getCredentials(id))
            }
        }

    /**
     * Linearize a TOFU acceptance with host edits/deletion. The transport may
     * only persist its rejected key while the same row still names the exact
     * endpoint that produced the prompt. If trust wins first, a following
     * endpoint edit/delete observes and removes that pin under this same mutex.
     */
    internal suspend fun trustHostKeyIfCurrent(
        hostId: Long,
        expectedHost: String,
        expectedPort: Int,
        trustAction: () -> Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val current = hostDao.getHostById(hostId) ?: return@withLock false
            if (current.host != expectedHost || current.port != expectedPort) {
                return@withLock false
            }
            trustAction()
        }
    }

    suspend fun saveHost(host: Host): Long = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            // Once this mutation owns the repository lock, finish either the
            // complete cross-store commit or its compensation. A Room suspend
            // call may commit and only then resume into a cancelled coroutine;
            // making just the catch block NonCancellable cannot detect that
            // state and can leave metadata without matching credentials.
            withContext(NonCancellable) {
                // Creation must never overwrite an existing row. With REPLACE,
                // a non-zero id could replace the old host and compensation
                // would then delete the wrong revision.
                require(host.id == 0L) { "新增主机不能包含已有 ID" }
                passwordStore.requireAvailable()
                val sanitizedHost = host.sanitizedForDatabase()
                val hostId = hostDao.insertHost(sanitizedHost)
                try {
                    passwordStore.replaceCredentials(hostId, host.credentialsForStorage())
                    hostId
                } catch (failure: Throwable) {
                    rollbackInto(failure) {
                        hostDao.deleteHost(sanitizedHost.copy(id = hostId))
                    }
                    rollbackInto(failure) {
                        passwordStore.replaceCredentials(hostId, StoredCredentials())
                    }
                    throw failure
                }
            }
        }
    }

    suspend fun updateHost(host: Host) = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            withContext(NonCancellable) {
                passwordStore.requireAvailable()
                val current = hostDao.getHostById(host.id)
                    ?: throw IllegalArgumentException("主机不存在")
                val oldCredentials = passwordStore.getCredentials(host.id)
                val newCredentials = host.credentialsForStorage()
                // lastConnected can advance from a retained background terminal
                // while an editor is open. createdAt is immutable. Never let a
                // stale UI snapshot overwrite either system-owned field.
                val sanitizedUpdate = host.sanitizedForDatabase().copy(
                    lastConnected = current.lastConnected,
                    createdAt = current.createdAt
                )
                val endpointChanged =
                    current.host != sanitizedUpdate.host || current.port != sanitizedUpdate.port
                val shouldRemoveOldPin = endpointChanged &&
                    hostDao.countHostsAtEndpointExcluding(current.host, current.port, current.id) == 0
                val oldTrust = if (shouldRemoveOldPin) {
                    trustStore.snapshotHost(current.host, current.port)
                } else {
                    null
                }

                var databaseUpdated = false
                try {
                    passwordStore.replaceCredentials(host.id, newCredentials)
                    if (hostDao.updateHost(sanitizedUpdate) != 1) {
                        throw IllegalStateException("主机更新失败")
                    }
                    databaseUpdated = true
                    if (shouldRemoveOldPin) {
                        trustStore.removeHost(current.host, current.port)
                    }
                } catch (failure: Throwable) {
                    if (oldTrust != null) {
                        rollbackInto(failure) {
                            trustStore.restoreHost(current.host, current.port, oldTrust)
                        }
                    }
                    if (databaseUpdated) {
                        rollbackInto(failure) {
                            if (hostDao.updateHost(current.sanitizedForDatabase()) != 1) {
                                throw IllegalStateException("无法回滚主机更新")
                            }
                        }
                    }
                    rollbackInto(failure) {
                        passwordStore.replaceCredentials(host.id, oldCredentials)
                    }
                    throw failure
                }
            }
        }
    }

    suspend fun deleteHost(host: Host) = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            withContext(NonCancellable) mutation@{
                passwordStore.requireAvailable()
                // Always resolve the current row: the list item passed by the UI
                // may be stale after an edit.
                val current = hostDao.getHostById(host.id) ?: return@mutation
                val oldCredentials = passwordStore.getCredentials(current.id)
                val shouldRemovePin =
                    hostDao.countHostsAtEndpointExcluding(current.host, current.port, current.id) == 0
                val oldTrust = if (shouldRemovePin) {
                    trustStore.snapshotHost(current.host, current.port)
                } else {
                    null
                }

                try {
                    passwordStore.replaceCredentials(current.id, StoredCredentials())
                    if (shouldRemovePin) {
                        trustStore.removeHost(current.host, current.port)
                    }
                    if (hostDao.deleteHost(current.sanitizedForDatabase()) != 1) {
                        throw IllegalStateException("主机删除失败")
                    }
                } catch (failure: Throwable) {
                    if (oldTrust != null) {
                        rollbackInto(failure) {
                            trustStore.restoreHost(current.host, current.port, oldTrust)
                        }
                    }
                    rollbackInto(failure) {
                        passwordStore.replaceCredentials(current.id, oldCredentials)
                    }
                    throw failure
                }
            }
        }
    }

    suspend fun updateLastConnected(hostId: Long) = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            hostDao.updateLastConnected(hostId, System.currentTimeMillis())
        }
    }

    fun getPassword(hostId: Long): String? = passwordStore.getCredentials(hostId).password

    fun getPrivateKey(hostId: Long): String? = passwordStore.getCredentials(hostId).privateKey

    fun getPassphrase(hostId: Long): String? = passwordStore.getCredentials(hostId).passphrase

    private fun Host.sanitizedForDatabase(): Host = sanitizeForDatabase()

    private fun Host.withSecrets(): Host = this.also {
        val credentials = passwordStore.getCredentials(it.id)
        it.password = credentials.password
        it.privateKey = credentials.privateKey
        it.passphrase = credentials.passphrase
    }

    private fun Host.credentialsForStorage(): StoredCredentials = if (useKeyAuth) {
        StoredCredentials(privateKey = privateKey, passphrase = passphrase)
    } else {
        StoredCredentials(password = password)
    }

    private suspend fun rollbackInto(failure: Throwable, action: suspend () -> Unit) {
        try {
            action()
        } catch (rollbackFailure: Throwable) {
            failure.addSuppressed(rollbackFailure)
        }
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
