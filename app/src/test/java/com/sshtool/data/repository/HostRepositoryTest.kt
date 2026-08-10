package com.sshtool.data.repository

import com.sshtool.data.crypto.CredentialStore
import com.sshtool.data.crypto.StoredCredentials
import com.sshtool.data.model.Host
import com.sshtool.ssh.HostKeyStoreBackend
import com.sshtool.ssh.HostTrustSnapshot
import com.sshtool.ssh.HostTrustStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class HostRepositoryTest {

    @Test
    fun saveHost_credentialFailureRemovesInsertedRowAndPartialCredentials() {
        val dao = FakeHostDao()
        val credentials = FakeCredentialStore().apply {
            failNextReplace = IOException("credential write failed")
            writeBeforeReplaceFailure = true
        }
        val repository = HostRepository(dao, credentials, FakeTrustStore())

        assertThrows(IOException::class.java) {
            runBlocking { repository.saveHost(host(password = "new-secret")) }
        }

        assertTrue(dao.rows.isEmpty())
        assertEquals(StoredCredentials(), credentials.getCredentials(1))
        assertEquals(listOf(1L), dao.deletedIds)
    }

    @Test
    fun saveHost_unavailableCredentialStoreDoesNotTouchDatabase() {
        val dao = FakeHostDao()
        val credentials = FakeCredentialStore().apply { available = false }
        val repository = HostRepository(dao, credentials, FakeTrustStore())

        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.saveHost(host(password = "secret")) }
        }

        assertTrue(dao.rows.isEmpty())
        assertEquals(0, dao.insertCalls)
    }

    @Test
    fun saveHost_existingIdIsRejectedBeforeAnyStoreIsTouched() {
        val existing = host(id = 7, address = "old.example.com")
        val oldCredentials = StoredCredentials(password = "old-secret")
        val dao = FakeHostDao().apply { rows[existing.id] = existing }
        val credentials = FakeCredentialStore().apply {
            values[existing.id] = oldCredentials
        }
        val repository = HostRepository(dao, credentials, FakeTrustStore())
        val replacement = existing.copy(host = "new.example.com").apply {
            password = "new-secret"
        }

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.saveHost(replacement) }
        }

        assertEquals(0, dao.insertCalls)
        assertEquals(existing, dao.rows[existing.id])
        assertEquals(oldCredentials, credentials.getCredentials(existing.id))
    }

    @Test
    fun saveHost_cancellationAfterRoomCommitStillWritesMatchingCredentials() = runBlocking {
        val dao = FakeHostDao().apply {
            insertCommitted = CompletableDeferred()
            releaseInsert = CompletableDeferred()
        }
        val credentials = FakeCredentialStore()
        val repository = HostRepository(dao, credentials, FakeTrustStore())

        val saving = launch { repository.saveHost(host(password = "new-secret")) }
        checkNotNull(dao.insertCommitted).await()
        saving.cancel()
        checkNotNull(dao.releaseInsert).complete(Unit)
        saving.join()

        assertEquals("example.com", dao.rows.getValue(1).host)
        assertEquals(
            StoredCredentials(password = "new-secret"),
            credentials.getCredentials(1)
        )
    }

    @Test
    fun updateHost_databaseFailureRestoresOldCredentials() {
        val oldHost = host(id = 7, address = "old.example.com", password = null)
        val dao = FakeHostDao().apply {
            rows[oldHost.id] = oldHost
            nextUpdateResult = 0
        }
        val oldCredentials = StoredCredentials(password = "old-secret")
        val credentials = FakeCredentialStore().apply { values[oldHost.id] = oldCredentials }
        val repository = HostRepository(dao, credentials, FakeTrustStore())
        val updated = oldHost.copy(name = "updated").apply { password = "new-secret" }

        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.updateHost(updated) }
        }

        assertEquals(oldHost, dao.rows[oldHost.id])
        assertEquals(oldCredentials, credentials.getCredentials(oldHost.id))
    }

    @Test
    fun updateHost_pinRemovalFailureRestoresDatabaseCredentialsAndPin() {
        val oldHost = host(id = 8, address = "old.example.com", password = null)
        val dao = FakeHostDao().apply { rows[oldHost.id] = oldHost }
        val oldCredentials = StoredCredentials(password = "old-secret")
        val credentials = FakeCredentialStore().apply { values[oldHost.id] = oldCredentials }
        val trust = FakeTrustStore().apply {
            pin(oldHost.host, oldHost.port)
            failNextRemove = IOException("pin removal failed")
        }
        val repository = HostRepository(dao, credentials, trust)
        val updated = oldHost.copy(host = "new.example.com").apply { password = "new-secret" }

        assertThrows(IOException::class.java) {
            runBlocking { repository.updateHost(updated) }
        }

        assertEquals(oldHost, dao.rows[oldHost.id])
        assertEquals(oldCredentials, credentials.getCredentials(oldHost.id))
        assertTrue(trust.isPinned(oldHost.host, oldHost.port))
    }

    @Test
    fun updateHost_preservesCurrentSystemOwnedTimestamps() = runBlocking {
        val current = Host(
            id = 12,
            name = "Old name",
            host = "example.com",
            username = "root",
            lastConnected = 200L,
            createdAt = 10L
        )
        val dao = FakeHostDao().apply { rows[current.id] = current }
        val credentials = FakeCredentialStore().apply {
            values[current.id] = StoredCredentials(password = "old-secret")
        }
        val repository = HostRepository(dao, credentials, FakeTrustStore())
        val staleEditorSnapshot = current.copy(
            name = "New name",
            lastConnected = 100L,
            createdAt = 999L
        ).apply { password = "new-secret" }

        repository.updateHost(staleEditorSnapshot)

        val stored = dao.rows.getValue(current.id)
        assertEquals("New name", stored.name)
        assertEquals(200L, stored.lastConnected)
        assertEquals(10L, stored.createdAt)
        assertEquals(
            StoredCredentials(password = "new-secret"),
            credentials.getCredentials(current.id)
        )
    }

    @Test
    fun updateHost_cancellationAfterRoomCommitKeepsNewMetadataAndCredentialsTogether() = runBlocking {
        val current = host(id = 13, address = "old.example.com")
        val dao = FakeHostDao().apply {
            rows[current.id] = current
            updateCommitted = CompletableDeferred()
            releaseUpdate = CompletableDeferred()
        }
        val credentials = FakeCredentialStore().apply {
            values[current.id] = StoredCredentials(password = "old-secret")
        }
        val repository = HostRepository(dao, credentials, FakeTrustStore())
        val updated = current.copy(host = "new.example.com").apply {
            password = "new-secret"
        }

        val updating = launch { repository.updateHost(updated) }
        checkNotNull(dao.updateCommitted).await()
        updating.cancel()
        checkNotNull(dao.releaseUpdate).complete(Unit)
        updating.join()

        assertEquals("new.example.com", dao.rows.getValue(current.id).host)
        assertEquals(
            StoredCredentials(password = "new-secret"),
            credentials.getCredentials(current.id)
        )
    }

    @Test
    fun deleteHost_databaseFailureRestoresCredentialsAndPinForCurrentRow() {
        val current = host(id = 9, address = "current.example.com", password = null)
        val staleUiHost = current.copy(host = "stale.example.com", port = 2200)
        val dao = FakeHostDao().apply {
            rows[current.id] = current
            nextDeleteResult = 0
        }
        val oldCredentials = StoredCredentials(password = "old-secret")
        val credentials = FakeCredentialStore().apply { values[current.id] = oldCredentials }
        val trust = FakeTrustStore().apply { pin(current.host, current.port) }
        val repository = HostRepository(dao, credentials, trust)

        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.deleteHost(staleUiHost) }
        }

        assertNotNull(dao.rows[current.id])
        assertEquals(oldCredentials, credentials.getCredentials(current.id))
        assertTrue(trust.isPinned(current.host, current.port))
        assertEquals(listOf(current.host to current.port), trust.removedEndpoints)
    }

    @Test
    fun deleteHost_keepsSharedEndpointPinUntilLastReferenceIsDeleted() = runBlocking {
        val first = host(id = 10, address = "shared.example.com", password = null)
        val second = host(id = 11, address = "shared.example.com", password = null)
        val dao = FakeHostDao().apply {
            rows[first.id] = first
            rows[second.id] = second
        }
        val credentials = FakeCredentialStore().apply {
            values[first.id] = StoredCredentials(password = "first")
            values[second.id] = StoredCredentials(password = "second")
        }
        val trust = FakeTrustStore().apply { pin(first.host, first.port) }
        val repository = HostRepository(dao, credentials, trust)

        repository.deleteHost(first)

        assertTrue(trust.isPinned(first.host, first.port))
        assertTrue(trust.removedEndpoints.isEmpty())
        assertNull(dao.rows[first.id])
        assertNotNull(dao.rows[second.id])

        repository.deleteHost(second)

        assertFalse(trust.isPinned(second.host, second.port))
        assertEquals(listOf(second.host to second.port), trust.removedEndpoints)
        assertNull(dao.rows[second.id])
    }

    @Test
    fun deleteHost_cancellationAfterRoomCommitDoesNotRestoreOrphanSecretsOrPin() = runBlocking {
        val current = host(id = 14, address = "deleted.example.com")
        val dao = FakeHostDao().apply {
            rows[current.id] = current
            deleteCommitted = CompletableDeferred()
            releaseDelete = CompletableDeferred()
        }
        val credentials = FakeCredentialStore().apply {
            values[current.id] = StoredCredentials(password = "old-secret")
        }
        val trust = FakeTrustStore().apply { pin(current.host, current.port) }
        val repository = HostRepository(dao, credentials, trust)

        val deleting = launch { repository.deleteHost(current) }
        checkNotNull(dao.deleteCommitted).await()
        deleting.cancel()
        checkNotNull(dao.releaseDelete).complete(Unit)
        deleting.join()

        assertNull(dao.rows[current.id])
        assertEquals(StoredCredentials(), credentials.getCredentials(current.id))
        assertFalse(trust.isPinned(current.host, current.port))
    }

    private fun host(
        id: Long = 0,
        address: String = "example.com",
        port: Int = 22,
        password: String? = null
    ): Host = Host(
        id = id,
        name = "Test host",
        host = address,
        port = port,
        username = "root"
    ).apply {
        this.password = password
    }

    private class FakeHostDao : HostDao {
        val rows = linkedMapOf<Long, Host>()
        val deletedIds = mutableListOf<Long>()
        var insertCalls = 0
        var nextUpdateResult: Int? = null
        var nextDeleteResult: Int? = null
        var insertCommitted: CompletableDeferred<Unit>? = null
        var releaseInsert: CompletableDeferred<Unit>? = null
        var updateCommitted: CompletableDeferred<Unit>? = null
        var releaseUpdate: CompletableDeferred<Unit>? = null
        var deleteCommitted: CompletableDeferred<Unit>? = null
        var releaseDelete: CompletableDeferred<Unit>? = null
        private var nextId = 1L

        override fun getAllHosts(): Flow<List<Host>> = flowOf(rows.values.toList())

        override suspend fun getHostById(id: Long): Host? = rows[id]

        override suspend fun insertHost(host: Host): Long {
            insertCalls++
            val id = host.id.takeIf { it != 0L } ?: nextId++
            rows[id] = host.copy(id = id)
            pauseAfterCommit(insertCommitted, releaseInsert)
            return id
        }

        override suspend fun updateHost(host: Host): Int {
            nextUpdateResult?.let {
                nextUpdateResult = null
                return it
            }
            if (host.id !in rows) return 0
            rows[host.id] = host
            pauseAfterCommit(updateCommitted, releaseUpdate)
            return 1
        }

        override suspend fun deleteHost(host: Host): Int {
            nextDeleteResult?.let {
                nextDeleteResult = null
                return it
            }
            if (rows.remove(host.id) == null) return 0
            deletedIds += host.id
            pauseAfterCommit(deleteCommitted, releaseDelete)
            return 1
        }

        override suspend fun updateLastConnected(hostId: Long, timestamp: Long) {
            val current = rows[hostId] ?: return
            rows[hostId] = current.copy(lastConnected = timestamp)
        }

        override suspend fun countHostsAtEndpointExcluding(
            host: String,
            port: Int,
            excludedHostId: Long
        ): Int = rows.values.count {
            it.id != excludedHostId && it.host == host && it.port == port
        }

        private suspend fun pauseAfterCommit(
            committed: CompletableDeferred<Unit>?,
            release: CompletableDeferred<Unit>?
        ) {
            committed?.complete(Unit)
            release?.await()
            currentCoroutineContext().ensureActive()
        }
    }

    private class FakeCredentialStore : CredentialStore {
        val values = mutableMapOf<Long, StoredCredentials>()
        var available = true
        var failNextReplace: Throwable? = null
        var writeBeforeReplaceFailure = false

        override fun requireAvailable() {
            if (!available) throw IllegalStateException("credential store unavailable")
        }

        override fun getCredentials(hostId: Long): StoredCredentials =
            values[hostId] ?: StoredCredentials()

        override fun replaceCredentials(hostId: Long, credentials: StoredCredentials) {
            val failure = failNextReplace
            failNextReplace = null
            if (failure != null && writeBeforeReplaceFailure) values[hostId] = credentials
            if (failure != null) throw failure
            values[hostId] = credentials
        }
    }

    private class FakeTrustStore : HostTrustStore {
        private val pins = mutableMapOf<Pair<String, Int>, HostTrustSnapshot>()
        val removedEndpoints = mutableListOf<Pair<String, Int>>()
        var failNextRemove: Throwable? = null

        fun pin(host: String, port: Int) {
            pins[host to port] = HostTrustSnapshot(
                entries = listOf(
                    HostKeyStoreBackend.Entry(
                        hostId = "test-pin",
                        port = port,
                        algorithm = "ssh-ed25519",
                        fingerprint = "test-fingerprint"
                    )
                ),
                legacyEntries = listOf("legacy test entry")
            )
        }

        fun isPinned(host: String, port: Int): Boolean = (host to port) in pins

        override fun snapshotHost(host: String, port: Int): HostTrustSnapshot =
            pins[host to port] ?: HostTrustSnapshot(emptyList())

        override fun removeHost(host: String, port: Int) {
            val endpoint = host to port
            removedEndpoints += endpoint
            pins.remove(endpoint)
            val failure = failNextRemove
            failNextRemove = null
            if (failure != null) throw failure
        }

        override fun restoreHost(host: String, port: Int, snapshot: HostTrustSnapshot) {
            val endpoint = host to port
            if (snapshot.entries.isEmpty() && snapshot.legacyEntries.isEmpty()) {
                pins.remove(endpoint)
            } else {
                pins[endpoint] = snapshot
            }
        }
    }
}
