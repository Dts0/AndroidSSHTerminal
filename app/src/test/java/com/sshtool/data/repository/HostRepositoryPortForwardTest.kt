package com.sshtool.data.repository

import com.sshtool.data.crypto.CredentialStore
import com.sshtool.data.crypto.StoredCredentials
import com.sshtool.data.model.Host
import com.sshtool.data.model.PortForwardDirection
import com.sshtool.data.model.PortForwardRule
import com.sshtool.ssh.HostTrustSnapshot
import com.sshtool.ssh.HostTrustStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class HostRepositoryPortForwardTest {

    @Test
    fun saveHost_normalizesAndStoresRulesWithGeneratedHostId() = runBlocking {
        val hostDao = FakeHostDao()
        val ruleDao = FakePortForwardRuleDao()
        val repository = repository(hostDao, ruleDao)

        val hostId = repository.saveHost(
            host(password = "secret"),
            listOf(
                rule(
                    id = 99,
                    hostId = 88,
                    bindAddress = " 0.0.0.0 ",
                    targetHost = " internal.example.com ",
                    sortOrder = 42
                )
            )
        )

        assertEquals(1L, hostId)
        assertEquals(
            listOf(
                rule(
                    id = 1,
                    hostId = hostId,
                    bindAddress = "0.0.0.0",
                    targetHost = "internal.example.com",
                    sortOrder = 0
                )
            ),
            ruleDao.getRulesForHost(hostId)
        )
    }

    @Test
    fun connectionProfile_containsOnlyEnabledRulesInStableOrder() = runBlocking {
        val host = host(id = 7)
        val hostDao = FakeHostDao().apply { rows[host.id] = host }
        val ruleDao = FakePortForwardRuleDao().apply {
            rows += rule(id = 3, hostId = host.id, enabled = true, sortOrder = 2)
            rows += rule(id = 2, hostId = host.id, enabled = false, sortOrder = 0)
            rows += rule(id = 1, hostId = host.id, enabled = true, sortOrder = 1)
        }
        val repository = repository(hostDao, ruleDao)

        val profile = repository.getConnectionProfile(host.id)!!

        assertEquals(listOf(1L, 3L), profile.portForwardRules.map { it.id })
    }

    @Test
    fun editableProfile_containsSecretsAndDisabledRules() = runBlocking {
        val host = host(id = 8)
        val hostDao = FakeHostDao().apply { rows[host.id] = host }
        val ruleDao = FakePortForwardRuleDao().apply {
            rows += rule(id = 1, hostId = host.id, enabled = false)
        }
        val credentials = FakeCredentialStore().apply {
            values[host.id] = StoredCredentials(password = "saved-secret")
        }
        val repository = HostRepository(hostDao, credentials, EmptyTrustStore, ruleDao)

        val profile = repository.getEditableHostProfile(host.id)!!

        assertEquals("saved-secret", profile.host.password)
        assertEquals(listOf(false), profile.portForwardRules.map { it.enabled })
    }

    @Test
    fun updateHost_ruleFailureRestoresMetadataCredentialsAndOldRules() {
        val current = host(id = 9, address = "old.example.com")
        val hostDao = FakeHostDao().apply { rows[current.id] = current }
        val oldRule = rule(id = 4, hostId = current.id, listenPort = 8000)
        val ruleDao = FakePortForwardRuleDao().apply {
            rows += oldRule
            failNextInsert = IOException("rule insert failed")
        }
        val credentials = FakeCredentialStore().apply {
            values[current.id] = StoredCredentials(password = "old-secret")
        }
        val repository = HostRepository(hostDao, credentials, EmptyTrustStore, ruleDao)
        val updated = current.copy(name = "Updated").apply { password = "new-secret" }

        assertThrows(IOException::class.java) {
            runBlocking {
                repository.updateHost(
                    updated,
                    listOf(rule(listenPort = 9000, targetPort = 9001))
                )
            }
        }

        assertEquals(current, hostDao.rows[current.id])
        assertEquals(StoredCredentials(password = "old-secret"), credentials.values[current.id])
        assertEquals(listOf(oldRule), runBlocking { ruleDao.getRulesForHost(current.id) })
    }

    @Test
    fun saveHost_ruleFailureRemovesInsertedHostAndDoesNotLeaveCredentials() {
        val hostDao = FakeHostDao()
        val ruleDao = FakePortForwardRuleDao().apply {
            failNextInsert = IOException("rule insert failed")
        }
        val credentials = FakeCredentialStore()
        val repository = HostRepository(hostDao, credentials, EmptyTrustStore, ruleDao)

        assertThrows(IOException::class.java) {
            runBlocking {
                repository.saveHost(host(password = "new-secret"), listOf(rule()))
            }
        }

        assertTrue(hostDao.rows.isEmpty())
        assertTrue(ruleDao.rows.isEmpty())
        assertEquals(StoredCredentials(), credentials.getCredentials(1))
    }

    @Test
    fun saveHost_credentialFailureAfterDatabaseCommitRemovesHostAndRules() {
        val hostDao = FakeHostDao()
        val ruleDao = FakePortForwardRuleDao()
        val credentials = FakeCredentialStore().apply {
            failNextReplace = IOException("credential write failed")
        }
        val repository = HostRepository(hostDao, credentials, EmptyTrustStore, ruleDao)

        assertThrows(IOException::class.java) {
            runBlocking {
                repository.saveHost(host(password = "new-secret"), listOf(rule()))
            }
        }

        assertTrue(hostDao.rows.isEmpty())
        assertTrue(ruleDao.rows.isEmpty())
        assertEquals(StoredCredentials(), credentials.getCredentials(1))
    }

    @Test
    fun legacyUpdateHost_preservesExistingRules() = runBlocking {
        val current = host(id = 10)
        val hostDao = FakeHostDao().apply { rows[current.id] = current }
        val oldRule = rule(id = 5, hostId = current.id)
        val ruleDao = FakePortForwardRuleDao().apply { rows += oldRule }
        val repository = repository(hostDao, ruleDao)

        repository.updateHost(current.copy(name = "Renamed"))

        assertEquals(listOf(oldRule), ruleDao.getRulesForHost(current.id))
    }

    @Test
    fun compoundUpdateWithEmptyRuleListRemovesExistingRules() = runBlocking {
        val current = host(id = 13)
        val hostDao = FakeHostDao().apply { rows[current.id] = current }
        val ruleDao = FakePortForwardRuleDao().apply {
            rows += rule(id = 8, hostId = current.id)
        }
        val repository = repository(hostDao, ruleDao)

        repository.updateHost(current.copy(name = "No forwards"), emptyList())

        assertEquals("No forwards", hostDao.rows.getValue(current.id).name)
        assertTrue(ruleDao.getRulesForHost(current.id).isEmpty())
    }

    @Test
    fun deleteHost_failureRestoresRulesInInjectedStore() {
        val current = host(id = 11)
        val hostDao = FakeHostDao().apply {
            rows[current.id] = current
            nextDeleteResult = 0
        }
        val oldRule = rule(id = 6, hostId = current.id)
        val ruleDao = FakePortForwardRuleDao().apply { rows += oldRule }
        val repository = repository(hostDao, ruleDao)

        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.deleteHost(current) }
        }

        assertEquals(listOf(oldRule), runBlocking { ruleDao.getRulesForHost(current.id) })
    }

    @Test
    fun deleteHost_successRemovesRulesInInjectedStore() = runBlocking {
        val current = host(id = 12)
        val hostDao = FakeHostDao().apply { rows[current.id] = current }
        val ruleDao = FakePortForwardRuleDao().apply {
            rows += rule(id = 7, hostId = current.id)
        }
        val repository = repository(hostDao, ruleDao)

        repository.deleteHost(current)

        assertTrue(hostDao.rows.isEmpty())
        assertTrue(ruleDao.getRulesForHost(current.id).isEmpty())
    }

    @Test
    fun invalidRuleIsRejectedBeforeAnyStoreIsMutated() {
        val hostDao = FakeHostDao()
        val ruleDao = FakePortForwardRuleDao()
        val credentials = FakeCredentialStore()
        val repository = HostRepository(hostDao, credentials, EmptyTrustStore, ruleDao)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.saveHost(host(), listOf(rule(listenPort = 0)))
            }
        }

        assertTrue(hostDao.rows.isEmpty())
        assertTrue(ruleDao.rows.isEmpty())
        assertEquals(0, credentials.requireAvailableCalls)
    }

    @Test
    fun conflictingListenerRulesAreRejectedBeforeAnyStoreIsMutated() {
        val hostDao = FakeHostDao()
        val ruleDao = FakePortForwardRuleDao()
        val credentials = FakeCredentialStore()
        val repository = HostRepository(hostDao, credentials, EmptyTrustStore, ruleDao)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.saveHost(
                    host(),
                    listOf(
                        rule(bindAddress = "0.0.0.0", listenPort = 8080),
                        rule(bindAddress = "127.0.0.1", listenPort = 8080)
                    )
                )
            }
        }

        assertTrue(hostDao.rows.isEmpty())
        assertTrue(ruleDao.rows.isEmpty())
        assertEquals(0, credentials.requireAvailableCalls)
    }

    @Test
    fun samePortOnDifferentForwardDirectionsIsAllowed() = runBlocking {
        val hostDao = FakeHostDao()
        val ruleDao = FakePortForwardRuleDao()
        val repository = repository(hostDao, ruleDao)

        val hostId = repository.saveHost(
            host(),
            listOf(
                rule(direction = PortForwardDirection.LOCAL, listenPort = 8080),
                rule(direction = PortForwardDirection.REMOTE, listenPort = 8080)
            )
        )

        assertEquals(2, ruleDao.getRulesForHost(hostId).size)
    }

    @Test
    fun remoteListenersDoNotApplyLocalWildcardOrLoopbackAliases() = runBlocking {
        val hostDao = FakeHostDao()
        val ruleDao = FakePortForwardRuleDao()
        val repository = repository(hostDao, ruleDao)

        val hostId = repository.saveHost(
            host(),
            listOf(
                rule(
                    direction = PortForwardDirection.REMOTE,
                    bindAddress = "*",
                    listenPort = 8080
                ),
                rule(
                    direction = PortForwardDirection.REMOTE,
                    bindAddress = "0.0.0.0",
                    listenPort = 8080
                ),
                rule(
                    direction = PortForwardDirection.REMOTE,
                    bindAddress = "localhost",
                    listenPort = 8080
                ),
                rule(
                    direction = PortForwardDirection.REMOTE,
                    bindAddress = "127.0.0.1",
                    listenPort = 8080
                )
            )
        )

        assertEquals(4, ruleDao.getRulesForHost(hostId).size)
    }

    @Test
    fun equivalentRemoteListenerNamesAreRejected() {
        val hostDao = FakeHostDao()
        val ruleDao = FakePortForwardRuleDao()
        val credentials = FakeCredentialStore()
        val repository = HostRepository(hostDao, credentials, EmptyTrustStore, ruleDao)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.saveHost(
                    host(),
                    listOf(
                        rule(
                            direction = PortForwardDirection.REMOTE,
                            bindAddress = " Example.COM ",
                            listenPort = 8080
                        ),
                        rule(
                            direction = PortForwardDirection.REMOTE,
                            bindAddress = "example.com",
                            listenPort = 8080
                        )
                    )
                )
            }
        }

        assertTrue(hostDao.rows.isEmpty())
        assertTrue(ruleDao.rows.isEmpty())
        assertEquals(0, credentials.requireAvailableCalls)
    }

    private fun repository(
        hostDao: FakeHostDao,
        ruleDao: FakePortForwardRuleDao
    ): HostRepository = HostRepository(
        hostDao,
        FakeCredentialStore(),
        EmptyTrustStore,
        ruleDao
    )

    private fun host(
        id: Long = 0,
        address: String = "example.com",
        password: String? = null
    ): Host = Host(
        id = id,
        name = "Test host",
        host = address,
        username = "root"
    ).apply {
        this.password = password
    }

    private fun rule(
        id: Long = 0,
        hostId: Long = 0,
        direction: PortForwardDirection = PortForwardDirection.LOCAL,
        bindAddress: String = "127.0.0.1",
        listenPort: Int = 8080,
        targetHost: String = "127.0.0.1",
        targetPort: Int = 80,
        enabled: Boolean = true,
        sortOrder: Int = 0
    ) = PortForwardRule(
        id = id,
        hostId = hostId,
        direction = direction,
        bindAddress = bindAddress,
        listenPort = listenPort,
        targetHost = targetHost,
        targetPort = targetPort,
        enabled = enabled,
        sortOrder = sortOrder
    )

    private class FakeHostDao : HostDao {
        val rows = linkedMapOf<Long, Host>()
        var nextDeleteResult: Int? = null
        private var nextId = 1L

        override fun getAllHosts(): Flow<List<Host>> = flowOf(rows.values.toList())
        override suspend fun getHostById(id: Long): Host? = rows[id]

        override suspend fun insertHost(host: Host): Long {
            val id = nextId++
            rows[id] = host.copy(id = id)
            return id
        }

        override suspend fun updateHost(host: Host): Int {
            if (host.id !in rows) return 0
            rows[host.id] = host
            return 1
        }

        override suspend fun deleteHost(host: Host): Int {
            nextDeleteResult?.let { result ->
                nextDeleteResult = null
                return result
            }
            return if (rows.remove(host.id) == null) 0 else 1
        }

        override suspend fun updateLastConnected(hostId: Long, timestamp: Long) {
            rows[hostId]?.let { rows[hostId] = it.copy(lastConnected = timestamp) }
        }

        override suspend fun countHostsAtEndpointExcluding(
            host: String,
            port: Int,
            excludedHostId: Long
        ): Int = rows.values.count {
            it.id != excludedHostId && it.host == host && it.port == port
        }
    }

    private class FakePortForwardRuleDao : PortForwardRuleDao {
        val rows = mutableListOf<PortForwardRule>()
        var failNextInsert: Throwable? = null
        private var nextId = 1L

        override suspend fun getRulesForHost(hostId: Long): List<PortForwardRule> =
            rows.filter { it.hostId == hostId }.sortedWith(ruleOrdering)

        override suspend fun getEnabledRulesForHost(hostId: Long): List<PortForwardRule> =
            getRulesForHost(hostId).filter { it.enabled }

        override suspend fun insertRules(rules: List<PortForwardRule>) {
            failNextInsert?.let { failure ->
                failNextInsert = null
                throw failure
            }
            rows += rules.map { rule ->
                rule.copy(id = rule.id.takeIf { it != 0L } ?: nextId++)
            }
        }

        override suspend fun deleteRulesForHost(hostId: Long): Int {
            val oldSize = rows.size
            rows.removeAll { it.hostId == hostId }
            return oldSize - rows.size
        }

        private companion object {
            val ruleOrdering = compareBy<PortForwardRule> { it.sortOrder }.thenBy { it.id }
        }
    }

    private class FakeCredentialStore : CredentialStore {
        val values = mutableMapOf<Long, StoredCredentials>()
        var requireAvailableCalls = 0
        var failNextReplace: Throwable? = null

        override fun requireAvailable() {
            requireAvailableCalls++
        }

        override fun getCredentials(hostId: Long): StoredCredentials =
            values[hostId] ?: StoredCredentials()

        override fun replaceCredentials(hostId: Long, credentials: StoredCredentials) {
            failNextReplace?.let { failure ->
                failNextReplace = null
                throw failure
            }
            values[hostId] = credentials
        }
    }

    private object EmptyTrustStore : HostTrustStore {
        override fun snapshotHost(host: String, port: Int) = HostTrustSnapshot(emptyList())
        override fun removeHost(host: String, port: Int) = Unit
        override fun restoreHost(host: String, port: Int, snapshot: HostTrustSnapshot) = Unit
    }
}
