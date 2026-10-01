package com.sshtool.data.repository

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.sshtool.R
import com.sshtool.data.crypto.CredentialStore
import com.sshtool.data.crypto.PasswordStore
import com.sshtool.data.crypto.StoredCredentials
import com.sshtool.data.model.Host
import com.sshtool.data.model.PortForwardDirection
import com.sshtool.data.model.PortForwardRule
import com.sshtool.data.model.portForwardBindAddressesConflict
import com.sshtool.ssh.HostKeyTrustStore
import com.sshtool.ssh.HostTrustSnapshot
import com.sshtool.ssh.HostTrustStore
import com.sshtool.util.UserArgumentException
import com.sshtool.util.UserMessage
import com.sshtool.util.UserStateException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One immutable, same-revision input for an SSH transport handshake. */
internal data class ConnectionProfile(
    val host: Host,
    val credentials: StoredCredentials,
    val portForwardRules: List<PortForwardRule> = emptyList()
)

/** One editor snapshot containing secrets and every configured forward rule. */
data class EditableHostProfile(
    val host: Host,
    val portForwardRules: List<PortForwardRule>
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

@Dao
interface PortForwardRuleDao {
    @Query(
        "SELECT * FROM port_forward_rules WHERE hostId = :hostId " +
            "ORDER BY sortOrder ASC, id ASC"
    )
    suspend fun getRulesForHost(hostId: Long): List<PortForwardRule>

    @Query(
        "SELECT * FROM port_forward_rules WHERE hostId = :hostId AND enabled = 1 " +
            "ORDER BY sortOrder ASC, id ASC"
    )
    suspend fun getEnabledRulesForHost(hostId: Long): List<PortForwardRule>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRules(rules: List<PortForwardRule>)

    @Query("DELETE FROM port_forward_rules WHERE hostId = :hostId")
    suspend fun deleteRulesForHost(hostId: Long): Int
}

class AppDatabaseConverters {
    @TypeConverter
    fun portForwardDirectionToString(value: PortForwardDirection): String = value.name

    @TypeConverter
    fun stringToPortForwardDirection(value: String): PortForwardDirection =
        PortForwardDirection.valueOf(value)
}

@Database(entities = [Host::class, PortForwardRule::class], version = 2, exportSchema = true)
@TypeConverters(AppDatabaseConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun hostDao(): HostDao
    abstract fun portForwardRuleDao(): PortForwardRuleDao

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
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateFrom1To2 { sql -> db.execSQL(sql) }
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
    }
}

/**
 * Keep migration SQL observable by a pure JVM contract test without mocking
 * the large [SupportSQLiteDatabase] interface. Production passes execSQL
 * directly, so the test records the exact statements the migration executes.
 */
internal fun migrateFrom1To2(execSql: (String) -> Unit) {
    execSql(
        """
        CREATE TABLE IF NOT EXISTS `port_forward_rules` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `hostId` INTEGER NOT NULL,
            `direction` TEXT NOT NULL,
            `bindAddress` TEXT NOT NULL,
            `listenPort` INTEGER NOT NULL,
            `targetHost` TEXT NOT NULL,
            `targetPort` INTEGER NOT NULL,
            `enabled` INTEGER NOT NULL,
            `sortOrder` INTEGER NOT NULL,
            FOREIGN KEY(`hostId`) REFERENCES `hosts`(`id`)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent()
    )
    execSql(
        "CREATE INDEX IF NOT EXISTS `index_port_forward_rules_hostId` " +
            "ON `port_forward_rules` (`hostId`)"
    )
}

class HostRepository {

    // Keep the Room instance strongly reachable for as long as its DAO is used.
    private val database: AppDatabase?
    private val hostDao: HostDao
    private val portForwardRuleDao: PortForwardRuleDao
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
        portForwardRuleDao = database.portForwardRuleDao()
        passwordStore = PasswordStore(context.applicationContext)
        trustStore = HostKeyTrustStore(context.applicationContext)
    }

    /** Injectable constructor for failure/rollback unit tests. */
    internal constructor(
        hostDao: HostDao,
        passwordStore: CredentialStore,
        trustStore: HostTrustStore,
        portForwardRuleDao: PortForwardRuleDao = EmptyPortForwardRuleDao
    ) {
        database = null
        this.hostDao = hostDao
        this.portForwardRuleDao = portForwardRuleDao
        this.passwordStore = passwordStore
        this.trustStore = trustStore
    }

    fun getAllHosts(): Flow<List<Host>> = hostDao.getAllHosts()

    suspend fun getHostById(id: Long): Host? = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            hostDao.getHostById(id)?.withSecrets()
        }
    }

    /** Read the editor's host, secrets, and rules as one repository revision. */
    suspend fun getEditableHostProfile(id: Long): EditableHostProfile? =
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                val host = hostDao.getHostById(id)?.copy()?.withSecrets()
                    ?: return@withLock null
                EditableHostProfile(
                    host = host,
                    portForwardRules = portForwardRuleDao.getRulesForHost(id).snapshot()
                )
            }
        }

    suspend fun getPortForwardRules(hostId: Long): List<PortForwardRule> =
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                portForwardRuleDao.getRulesForHost(hostId).snapshot()
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
                ConnectionProfile(
                    host = host,
                    credentials = passwordStore.getCredentials(id),
                    portForwardRules = portForwardRuleDao.getEnabledRulesForHost(id).snapshot()
                )
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

    suspend fun saveHost(host: Host): Long = saveHost(host, emptyList())

    suspend fun saveHost(host: Host, portForwardRules: List<PortForwardRule>): Long =
        withContext(Dispatchers.IO) {
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
                    require(host.id == 0L) { throw UserArgumentException(UserMessage(R.string.error_new_host_id)) }
                    validatePortForwardRules(portForwardRules)
                    passwordStore.requireAvailable()
                    val sanitizedHost = host.sanitizedForDatabase()
                    var hostId: Long? = null
                    try {
                        withDatabaseTransaction {
                            val insertedHostId = hostDao.insertHost(sanitizedHost)
                            hostId = insertedHostId
                            val normalizedRules = normalizeRules(portForwardRules, insertedHostId)
                            if (normalizedRules.isNotEmpty()) {
                                portForwardRuleDao.insertRules(normalizedRules)
                            }
                        }
                        val insertedHostId = checkNotNull(hostId)
                        passwordStore.replaceCredentials(
                            insertedHostId,
                            host.credentialsForStorage()
                        )
                        insertedHostId
                    } catch (failure: Throwable) {
                        hostId?.let { insertedHostId ->
                            rollbackInto(failure) {
                                withDatabaseTransaction {
                                    portForwardRuleDao.deleteRulesForHost(insertedHostId)
                                    hostDao.deleteHost(sanitizedHost.copy(id = insertedHostId))
                                }
                            }
                            rollbackInto(failure) {
                                passwordStore.replaceCredentials(
                                    insertedHostId,
                                    StoredCredentials()
                                )
                            }
                        }
                        throw failure
                    }
                }
            }
        }

    suspend fun updateHost(host: Host) = updateHostInternal(host, null)

    suspend fun updateHost(host: Host, portForwardRules: List<PortForwardRule>) =
        updateHostInternal(host, portForwardRules)

    private suspend fun updateHostInternal(
        host: Host,
        replacementRules: List<PortForwardRule>?
    ) = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            withContext(NonCancellable) {
                replacementRules?.let(::validatePortForwardRules)
                passwordStore.requireAvailable()
                val current = hostDao.getHostById(host.id)
                    ?: throw UserArgumentException(UserMessage(R.string.host_not_found))
                val oldRules = replacementRules?.let {
                    portForwardRuleDao.getRulesForHost(host.id).snapshot()
                }
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

                var databaseMutationAttempted = false
                var databaseUpdated = false
                try {
                    passwordStore.replaceCredentials(host.id, newCredentials)
                    databaseMutationAttempted = true
                    withDatabaseTransaction {
                        if (hostDao.updateHost(sanitizedUpdate) != 1) {
                            throw UserStateException(UserMessage(R.string.error_update_host))
                        }
                        if (replacementRules != null) {
                            portForwardRuleDao.deleteRulesForHost(host.id)
                            val normalizedRules = normalizeRules(replacementRules, host.id)
                            if (normalizedRules.isNotEmpty()) {
                                portForwardRuleDao.insertRules(normalizedRules)
                            }
                        }
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
                    // A real Room transaction rolls back on failure. Injected
                    // DAOs have no transaction boundary, so compensate even
                    // when the compound update failed part-way through.
                    if (databaseUpdated || (database == null && databaseMutationAttempted)) {
                        rollbackInto(failure) {
                            withDatabaseTransaction {
                                if (hostDao.updateHost(current.sanitizedForDatabase()) != 1) {
                                    throw UserStateException(UserMessage(R.string.error_rollback_host))
                                }
                                if (oldRules != null) {
                                    portForwardRuleDao.deleteRulesForHost(host.id)
                                    if (oldRules.isNotEmpty()) {
                                        portForwardRuleDao.insertRules(oldRules)
                                    }
                                }
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
                val oldRules = portForwardRuleDao.getRulesForHost(current.id).snapshot()
                val oldCredentials = passwordStore.getCredentials(current.id)
                val shouldRemovePin =
                    hostDao.countHostsAtEndpointExcluding(current.host, current.port, current.id) == 0
                val oldTrust = if (shouldRemovePin) {
                    trustStore.snapshotHost(current.host, current.port)
                } else {
                    null
                }

                var databaseMutationAttempted = false
                try {
                    passwordStore.replaceCredentials(current.id, StoredCredentials())
                    if (shouldRemovePin) {
                        trustStore.removeHost(current.host, current.port)
                    }
                    databaseMutationAttempted = true
                    withDatabaseTransaction {
                        portForwardRuleDao.deleteRulesForHost(current.id)
                        if (hostDao.deleteHost(current.sanitizedForDatabase()) != 1) {
                            throw UserStateException(UserMessage(R.string.error_delete_host))
                        }
                    }
                } catch (failure: Throwable) {
                    if (oldTrust != null) {
                        rollbackInto(failure) {
                            trustStore.restoreHost(current.host, current.port, oldTrust)
                        }
                    }
                    if (database == null && databaseMutationAttempted) {
                        rollbackInto(failure) {
                            portForwardRuleDao.deleteRulesForHost(current.id)
                            if (oldRules.isNotEmpty()) {
                                portForwardRuleDao.insertRules(oldRules)
                            }
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

    private suspend fun <T> withDatabaseTransaction(block: suspend () -> T): T =
        database?.withTransaction { block() } ?: block()

    private fun validatePortForwardRules(rules: List<PortForwardRule>) {
        rules.forEach { rule ->
            require(rule.bindAddress.isNotBlank()) { throw UserArgumentException(UserMessage(R.string.port_forward_bind_required)) }
            require(rule.targetHost.isNotBlank()) { throw UserArgumentException(UserMessage(R.string.port_forward_target_required)) }
            require(rule.listenPort in 1..65535) { throw UserArgumentException(UserMessage(R.string.port_forward_invalid_listen_port)) }
            require(rule.targetPort in 1..65535) { throw UserArgumentException(UserMessage(R.string.port_forward_invalid_target_port)) }
        }
        rules.forEachIndexed { index, rule ->
            val conflictingRule = rules.subList(0, index).any { previous ->
                previous.direction == rule.direction &&
                    previous.listenPort == rule.listenPort &&
                    portForwardBindAddressesConflict(
                        rule.direction,
                        previous.bindAddress,
                        rule.bindAddress
                    )
            }
            require(!conflictingRule) { throw UserArgumentException(UserMessage(R.string.port_forward_duplicate)) }
        }
    }

    private fun normalizeRules(
        rules: List<PortForwardRule>,
        hostId: Long
    ): List<PortForwardRule> = rules.mapIndexed { index, rule ->
        rule.copy(
            id = 0,
            hostId = hostId,
            bindAddress = rule.bindAddress.trim(),
            targetHost = rule.targetHost.trim(),
            sortOrder = index
        )
    }

    private suspend fun rollbackInto(failure: Throwable, action: suspend () -> Unit) {
        try {
            action()
        } catch (rollbackFailure: Throwable) {
            failure.addSuppressed(rollbackFailure)
        }
    }
}

private fun List<PortForwardRule>.snapshot(): List<PortForwardRule> = map { it.copy() }

private object EmptyPortForwardRuleDao : PortForwardRuleDao {
    override suspend fun getRulesForHost(hostId: Long): List<PortForwardRule> = emptyList()

    override suspend fun getEnabledRulesForHost(hostId: Long): List<PortForwardRule> = emptyList()

    override suspend fun insertRules(rules: List<PortForwardRule>) = Unit

    override suspend fun deleteRulesForHost(hostId: Long): Int = 0
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
