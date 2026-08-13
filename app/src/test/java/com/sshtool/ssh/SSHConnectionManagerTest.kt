package com.sshtool.ssh

import com.sshtool.data.crypto.CredentialStore
import com.sshtool.data.crypto.StoredCredentials
import com.sshtool.data.model.Host
import com.sshtool.data.model.PortForwardDirection
import com.sshtool.data.model.PortForwardRule
import com.sshtool.data.repository.ConnectionProfile
import com.sshtool.data.repository.HostDao
import com.sshtool.data.repository.HostRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.io.ByteArrayInputStream

class SSHConnectionManagerTest {

    @Test
    fun connectedGenerationStartsEachConfiguredForwardAndPublishesIsolatedStates() = runBlocking {
        val host = host(70, "forward.example")
        val rules = listOf(
            forwardRule(1, host.id, 18080),
            forwardRule(2, host.id, 18081)
        )
        val repository = HostRepository(
            FakeHostDao(mutableMapOf(host.id to host)),
            FakeCredentialStore(mapOf(host.id to StoredCredentials(password = "secret"))),
            EmptyTrustStore,
            FixedPortForwardRuleDao(rules)
        )
        val factory = FakeTransportFactory()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = SSHConnectionManager(factory, scope)
        val listener = RecordingListener()

        try {
            manager.connect("forwarded", host, repository, listener)
            val transport = factory.await(host.host)
            transport.failedForwardIds += 2L
            transport.releaseConnect.complete(Unit)
            listener.awaitState { it is SSHConnectionState.Connected }
            withTimeout(5_000) {
                while (manager.portForwardStates.value["forwarded"]
                        ?.all { it.status == PortForwardStatus.ACTIVE ||
                            it.status == PortForwardStatus.FAILED } != true) yield()
            }

            assertEquals(listOf(1L, 2L), transport.startedForwardIds)
            val states = checkNotNull(manager.portForwardStates.value["forwarded"])
            assertEquals(PortForwardStatus.ACTIVE, states.single { it.rule.id == 1L }.status)
            assertEquals(PortForwardStatus.FAILED, states.single { it.rule.id == 2L }.status)
            assertTrue(transport.isConnected)
        } finally {
            manager.disconnectAll()
            scope.cancel()
        }
    }

    @Test
    fun sftpOperationsRouteBySessionAndTransferFailureDoesNotDisconnectShell() = runBlocking {
        val host = host(71, "sftp.example")
        val factory = FakeTransportFactory()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = SSHConnectionManager(factory, scope)
        val listener = RecordingListener()

        try {
            manager.connect("sftp", host, repositoryFor(host), listener)
            val transport = factory.await(host.host)
            transport.releaseConnect.complete(Unit)
            listener.awaitState { it is SSHConnectionState.Connected }

            val listing = manager.listRemoteDirectory("sftp", ".")
            assertEquals("/home/user", listing.path)
            assertEquals("hello.txt", listing.entries.single().name)

            transport.transferFailure = IllegalStateException("permission denied")
            val failure = runCatching {
                manager.uploadFile(
                    "sftp",
                    ByteArrayInputStream("body".toByteArray()),
                    "/home/user",
                    "hello.txt",
                    overwriteExisting = true,
                    totalBytes = 4
                )
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(manager.fileTransferStates.value["sftp"] is FileTransferState.Failed)
            assertTrue(transport.isConnected)
            assertTrue(manager.send("sftp", "shell-still-works"))
        } finally {
            manager.disconnectAll()
            scope.cancel()
        }
    }

    @Test
    fun reconnectWaitsForCancelledTransferCleanupBeforeStartingNewGeneration() = runBlocking {
        val host = host(72, "commit.example")
        val factory = FakeTransportFactory()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = SSHConnectionManager(factory, scope)
        val listener = RecordingListener()

        try {
            manager.connect("commit", host, repositoryFor(host), listener)
            val first = factory.await(host.host)
            first.releaseConnect.complete(Unit)
            listener.awaitState { it is SSHConnectionState.Connected }

            first.blockUpload = true
            val transferCompleted = CompletableDeferred<Unit>()
            scope.launch {
                try {
                    manager.uploadFile(
                        "commit",
                        ByteArrayInputStream("body".toByteArray()),
                        "/home/user",
                        "hello.txt",
                        overwriteExisting = true,
                        totalBytes = 4
                    )
                } catch (_: CancellationException) {
                    // Expected: manager retires the logical generation while
                    // the transport finishes its protected commit window.
                } finally {
                    transferCompleted.complete(Unit)
                }
            }
            first.uploadStarted.await()

            manager.reconnect("commit")
            first.cancelTransferCalled.await()
            yield()
            assertEquals(1, factory.count(host.host))
            assertFalse(first.destroyed)

            first.releaseUpload.complete(Unit)
            transferCompleted.await()
            val second = factory.await(host.host, occurrence = 1)
            second.connectStarted.await()

            assertTrue(first.destroyed)
        } finally {
            factory.createdTransports().forEach { it.releaseUpload.complete(Unit) }
            factory.releaseAll()
            manager.disconnectAll()
            scope.cancel()
        }
    }

    @Test
    fun removeSessionAndAwaitJoinsTransferBeforeDestroyingTransport() = runBlocking {
        val host = host(73, "delete-transfer.example")
        val factory = FakeTransportFactory()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = SSHConnectionManager(factory, scope)
        val listener = RecordingListener()

        try {
            manager.connect("delete-transfer", host, repositoryFor(host), listener)
            val transport = factory.await(host.host)
            transport.releaseConnect.complete(Unit)
            listener.awaitState { it is SSHConnectionState.Connected }

            transport.blockUpload = true
            val transferCompleted = CompletableDeferred<Unit>()
            scope.launch {
                try {
                    manager.uploadFile(
                        "delete-transfer",
                        ByteArrayInputStream("body".toByteArray()),
                        "/home/user",
                        "hello.txt",
                        overwriteExisting = true,
                        totalBytes = 4
                    )
                } catch (_: CancellationException) {
                    // Expected for session removal.
                } finally {
                    transferCompleted.complete(Unit)
                }
            }
            transport.uploadStarted.await()

            val removal = async { manager.removeSessionAndAwait("delete-transfer") }
            transport.cancelTransferCalled.await()
            yield()
            assertFalse(removal.isCompleted)
            assertFalse(transport.destroyed)

            transport.releaseUpload.complete(Unit)
            transferCompleted.await()
            removal.await()
            assertTrue(transport.destroyed)
        } finally {
            factory.createdTransports().forEach { it.releaseUpload.complete(Unit) }
            factory.releaseAll()
            manager.disconnectAll()
            scope.cancel()
        }
    }

    @Test
    fun consecutiveReconnectsSerializeCleanupAndStartOnlyLatestGeneration() = runBlocking {
        val host = host(74, "rapid-reconnect.example")
        val factory = FakeTransportFactory()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = SSHConnectionManager(factory, scope)
        val listener = RecordingListener()

        try {
            manager.connect("rapid", host, repositoryFor(host), listener)
            val first = factory.await(host.host)
            first.releaseConnect.complete(Unit)
            listener.awaitState { it is SSHConnectionState.Connected }

            first.blockUpload = true
            val transferCompleted = CompletableDeferred<Unit>()
            scope.launch {
                try {
                    manager.uploadFile(
                        "rapid",
                        ByteArrayInputStream("body".toByteArray()),
                        "/home/user",
                        "hello.txt",
                        overwriteExisting = true,
                        totalBytes = 4
                    )
                } catch (_: CancellationException) {
                    // Expected while retiring the first generation.
                } finally {
                    transferCompleted.complete(Unit)
                }
            }
            first.uploadStarted.await()

            manager.reconnect("rapid")
            manager.reconnect("rapid")
            first.cancelTransferCalled.await()
            yield()
            assertEquals(1, factory.count(host.host))

            first.releaseUpload.complete(Unit)
            transferCompleted.await()
            val latest = factory.await(host.host, occurrence = 1)
            latest.connectStarted.await()
            assertEquals(2, factory.count(host.host))
            assertTrue(first.destroyed)
        } finally {
            factory.createdTransports().forEach { it.releaseUpload.complete(Unit) }
            factory.releaseAll()
            manager.disconnectAll()
            scope.cancel()
        }
    }

    @Test
    fun slowSessionsRouteSendAndResizeOnlyByExplicitSessionId() = runBlocking {
        val factory = FakeTransportFactory()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = SSHConnectionManager(factory, scope)
        val repository = repositoryFor(host(1, "a.example"), host(2, "b.example"))
        val listenerA = RecordingListener()
        val listenerB = RecordingListener()

        try {
            manager.connect("session-a", host(1, "a.example"), repository, listenerA)
            manager.connect("session-b", host(2, "b.example"), repository, listenerB)
            val transportA = factory.await("a.example")
            val transportB = factory.await("b.example")
            transportA.connectStarted.await()
            transportB.connectStarted.await()
            assertEquals("secret-1", transportA.credentials.password)
            assertEquals("secret-2", transportB.credentials.password)

            assertTrue(manager.send("session-a", "only-a"))
            assertTrue(manager.send("session-b", "only-b"))
            assertFalse(manager.send("missing", "nobody"))
            manager.updatePtySizeForSession("session-a", 101, 31)
            manager.updatePtySizeForSession("session-b", 202, 42)

            transportB.releaseConnect.complete(Unit)
            listenerB.awaitState { it is SSHConnectionState.Connected }
            assertFalse(listenerA.states.any { it is SSHConnectionState.Connected })
            transportA.releaseConnect.complete(Unit)
            listenerA.awaitState { it is SSHConnectionState.Connected }
            transportA.persistCompleted.await()
            transportB.persistCompleted.await()
            transportA.emitOutput("from-a")
            transportB.emitOutput("from-b")

            assertEquals(listOf("only-a"), transportA.sent.map(::utf8))
            assertEquals(listOf("only-b"), transportB.sent.map(::utf8))
            assertEquals(listOf(101 to 31), transportA.sizes)
            assertEquals(listOf(202 to 42), transportB.sizes)
            assertEquals(1, listenerA.states.count { it is SSHConnectionState.Connected })
            assertEquals(1, listenerB.states.count { it is SSHConnectionState.Connected })
            assertEquals(1, transportA.persistCalls.get())
            assertEquals(1, transportB.persistCalls.get())
            assertEquals(listOf("from-a"), listenerA.outputs.map(::utf8))
            assertEquals(listOf("from-b"), listenerB.outputs.map(::utf8))
        } finally {
            factory.releaseAll()
            manager.disconnectAll()
            scope.cancel()
        }
    }

    @Test
    fun resizeBeforeTransportCreationSeedsInitialPtyAndSurvivesReconnect() = runBlocking {
        val host = host(3, "sized.example")
        val dao = FakeHostDao(mutableMapOf(host.id to host)).apply {
            getStarted = CompletableDeferred()
            releaseGet = CompletableDeferred()
        }
        val repository = HostRepository(
            dao,
            FakeCredentialStore(mapOf(host.id to StoredCredentials(password = "secret"))),
            EmptyTrustStore
        )
        val factory = FakeTransportFactory()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = SSHConnectionManager(factory, scope)
        val listener = RecordingListener()

        try {
            manager.connect("sized", host, repository, listener)
            checkNotNull(dao.getStarted).await()
            manager.updatePtySizeForSession("sized", 132, 47)
            checkNotNull(dao.releaseGet).complete(Unit)

            val first = factory.await(host.host)
            first.connectStarted.await()
            assertEquals(listOf(132 to 47), first.sizes)
            first.releaseConnect.complete(Unit)
            listener.awaitState { it is SSHConnectionState.Connected }

            manager.reconnect("sized")
            val second = factory.await(host.host, occurrence = 1)
            second.connectStarted.await()
            assertEquals(listOf(132 to 47), second.sizes)
        } finally {
            checkNotNull(dao.releaseGet).complete(Unit)
            factory.releaseAll()
            manager.disconnectAll()
            scope.cancel()
        }
    }

    @Test
    fun repeatedIdenticalResizeIsForwardedOnlyOncePerTransport() = runBlocking {
        val host = host(4, "dedupe.example")
        val factory = FakeTransportFactory()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = SSHConnectionManager(factory, scope)
        val repository = repositoryFor(host)
        val listener = RecordingListener()

        try {
            manager.connect("dedupe", host, repository, listener)
            val first = factory.await(host.host)
            first.connectStarted.await()

            repeat(20) { manager.updatePtySizeForSession("dedupe", 120, 40) }
            assertEquals(listOf(120 to 40), first.sizes)

            first.releaseConnect.complete(Unit)
            listener.awaitState { it is SSHConnectionState.Connected }
            repeat(20) { manager.updatePtySizeForSession("dedupe", 120, 40) }
            assertEquals(listOf(120 to 40), first.sizes)

            manager.reconnect("dedupe")
            val second = factory.await(host.host, occurrence = 1)
            second.connectStarted.await()
            assertEquals(listOf(120 to 40), second.sizes)
        } finally {
            factory.releaseAll()
            manager.disconnectAll()
            scope.cancel()
        }
    }

    @Test
    fun reconnectAndDisconnectSuppressEveryOldGenerationCallback() = runBlocking {
        val factory = FakeTransportFactory()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = SSHConnectionManager(factory, scope)
        val firstHost = host(7, "first.example")
        val secondHost = firstHost.copy(host = "second.example")
        val thirdHost = firstHost.copy(host = "third.example")
        val fixture = repositoryFixture(firstHost)
        val repository = fixture.repository
        val firstListener = RecordingListener()
        val secondListener = RecordingListener()
        val thirdListener = RecordingListener()

        try {
            manager.connect("stable-id", firstHost, repository, firstListener)
            val first = factory.await("first.example")
            first.connectStarted.await()
            val generationZeroRelay = checkNotNull(first.listener)

            fixture.dao.replace(secondHost)
            manager.reconnect("stable-id", secondHost, secondListener)
            val second = factory.await("second.example")
            second.connectStarted.await()
            val generationOneRelay = checkNotNull(second.listener)

            generationZeroRelay.onStateChanged(SSHConnectionState.Error("stale generation zero"))
            generationZeroRelay.onOutput("stale-zero".toByteArray())
            generationZeroRelay.onDisconnected()
            first.releaseConnect.complete(Unit)

            second.releaseConnect.complete(Unit)
            secondListener.awaitState { it is SSHConnectionState.Connected }
            assertEquals(
                listOf(SSHConnectionState.Connecting),
                firstListener.states
            )
            assertTrue(firstListener.outputs.isEmpty())
            assertEquals(0, firstListener.disconnectedCount)
            assertFalse(secondListener.states.any {
                it is SSHConnectionState.Error && it.message.contains("stale")
            })
            assertTrue(secondListener.outputs.isEmpty())

            assertTrue(manager.send("stable-id", "generation-one"))
            assertEquals(listOf("generation-one"), second.sent.map(::utf8))
            assertTrue(first.sent.isEmpty())

            manager.disconnect("stable-id")
            secondListener.awaitState { it is SSHConnectionState.Disconnected }
            generationOneRelay.onStateChanged(SSHConnectionState.Error("late after disconnect"))
            generationOneRelay.onOutput("late-output".toByteArray())
            generationOneRelay.onDisconnected()
            assertFalse(manager.send("stable-id", "must-not-send"))
            assertFalse(secondListener.states.any {
                it is SSHConnectionState.Error && it.message.contains("late")
            })
            assertTrue(secondListener.outputs.isEmpty())
            assertEquals(1, secondListener.disconnectedCount)

            fixture.dao.replace(thirdHost)
            manager.reconnect("stable-id", thirdHost, thirdListener)
            val third = factory.await("third.example")
            third.connectStarted.await()
            generationZeroRelay.onOutput("older-still".toByteArray())
            generationOneRelay.onOutput("old-generation-one".toByteArray())
            third.releaseConnect.complete(Unit)
            thirdListener.awaitState { it is SSHConnectionState.Connected }

            assertTrue(thirdListener.outputs.isEmpty())
            assertTrue(manager.send("stable-id", "generation-two"))
            assertEquals(listOf("generation-two"), third.sent.map(::utf8))
            assertTrue(second.destroyed)
        } finally {
            factory.releaseAll()
            manager.disconnectAll()
            scope.cancel()
        }
    }

    @Test
    fun outputRelayBackpressuresTransportUntilConsumerFinishes() = runBlocking {
        val factory = FakeTransportFactory()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = SSHConnectionManager(factory, scope)
        val outputRelease = CompletableDeferred<Unit>()
        val listener = RecordingListener(outputRelease)

        try {
            manager.connect("backpressured", host(20, "output.example"), repositoryFor(
                host(20, "output.example")
            ), listener)
            val transport = factory.await("output.example")
            transport.connectStarted.await()
            transport.releaseConnect.complete(Unit)
            listener.awaitState { it is SSHConnectionState.Connected }

            val delivery = async(Dispatchers.Default) { transport.emitOutput("wait-for-consumer") }
            listener.outputStarted.await()
            assertFalse(delivery.isCompleted)
            outputRelease.complete(Unit)
            delivery.await()

            assertEquals(listOf("wait-for-consumer"), listener.outputs.map(::utf8))
        } finally {
            outputRelease.complete(Unit)
            factory.releaseAll()
            manager.disconnectAll()
            scope.cancel()
        }
    }

    @Test
    fun concurrentEditCannotMixOldMetadataWithNewCredentials() = runBlocking {
        val oldHost = host(30, "old.example")
        val updatedHost = oldHost.copy(host = "new.example").apply {
            password = "new-secret"
        }
        val dao = PausingUpdateHostDao(oldHost)
        val credentials = FakeCredentialStore(
            mapOf(oldHost.id to StoredCredentials(password = "old-secret"))
        )
        val repository = HostRepository(dao, credentials, EmptyTrustStore)
        val factory = FakeTransportFactory()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val manager = SSHConnectionManager(factory, scope)
        val listener = RecordingListener()

        try {
            val update = async(Dispatchers.Default) { repository.updateHost(updatedHost) }
            dao.updateStarted.await()

            // Credentials have already changed, while the DAO intentionally
            // still exposes the prior endpoint. A split read would now create
            // old.example + new-secret. The atomic profile read must wait.
            manager.connect("profile", oldHost, repository, listener)
            assertEquals(0, factory.createdCount)

            dao.releaseUpdate.countDown()
            update.await()
            val transport = factory.await("new.example")
            transport.connectStarted.await()

            assertEquals("new.example", transport.host.host)
            assertEquals("new-secret", transport.credentials.password)
            assertEquals(0, factory.count("old.example"))
        } finally {
            dao.releaseUpdate.countDown()
            factory.releaseAll()
            manager.disconnectAll()
            scope.cancel()
        }
    }

    @Test
    fun deletingHostBeforeTrustPreventsOldEndpointPinFromBeingWritten() = runBlocking {
        val host = host(40, "deleted.example")
        val dao = FakeHostDao(mutableMapOf(host.id to host))
        val credentials = FakeCredentialStore(
            mapOf(host.id to StoredCredentials(password = "secret"))
        )
        val trustStore = RecordingTrustStore()
        val repository = HostRepository(dao, credentials, trustStore)
        val factory = FakeTransportFactory { rejectedHost ->
            trustStore.pin(rejectedHost.host, rejectedHost.port)
            true
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = SSHConnectionManager(factory, scope)
        val listener = RecordingListener()

        try {
            manager.connect("deleted", host, repository, listener)
            val transport = factory.await(host.host)
            transport.connectStarted.await()

            repository.deleteHost(host)
            manager.trustAndReconnect("deleted")
            listener.awaitState { it is SSHConnectionState.Error }

            assertEquals(0, transport.trustCalls.get())
            assertFalse(trustStore.isPinned(host.host, host.port))
        } finally {
            factory.releaseAll()
            manager.disconnectAll()
            scope.cancel()
        }
    }

    @Test
    fun changingEndpointBeforeTrustPreventsRejectedOldEndpointPin() = runBlocking {
        val host = host(42, "old-endpoint.example")
        val updated = host.copy(host = "new-endpoint.example").apply {
            password = "new-secret"
        }
        val dao = FakeHostDao(mutableMapOf(host.id to host))
        val credentials = FakeCredentialStore(
            mapOf(host.id to StoredCredentials(password = "old-secret"))
        )
        val trustStore = RecordingTrustStore()
        val repository = HostRepository(dao, credentials, trustStore)
        val factory = FakeTransportFactory { rejectedHost ->
            trustStore.pin(rejectedHost.host, rejectedHost.port)
            true
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = SSHConnectionManager(factory, scope)
        val listener = RecordingListener()

        try {
            manager.connect("changed", host, repository, listener)
            val transport = factory.await(host.host)
            transport.connectStarted.await()

            repository.updateHost(updated)
            manager.trustAndReconnect("changed")
            listener.awaitState { it is SSHConnectionState.Error }

            assertEquals(0, transport.trustCalls.get())
            assertFalse(trustStore.isPinned(host.host, host.port))
        } finally {
            factory.releaseAll()
            manager.disconnectAll()
            scope.cancel()
        }
    }

    @Test
    fun trustWinningRaceIsFollowedByDeleteRemovingItsPin() = runBlocking {
        val host = host(41, "raced.example")
        val dao = FakeHostDao(mutableMapOf(host.id to host))
        val credentials = FakeCredentialStore(
            mapOf(host.id to StoredCredentials(password = "secret"))
        )
        val trustStore = RecordingTrustStore()
        val trustStarted = CountDownLatch(1)
        val releaseTrust = CountDownLatch(1)
        val repository = HostRepository(dao, credentials, trustStore)
        val factory = FakeTransportFactory { rejectedHost ->
            trustStore.pin(rejectedHost.host, rejectedHost.port)
            trustStarted.countDown()
            check(releaseTrust.await(5, TimeUnit.SECONDS)) { "test trust was not released" }
            true
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = SSHConnectionManager(factory, scope)

        try {
            manager.connect("raced", host, repository, RecordingListener())
            val transport = factory.await(host.host)
            transport.connectStarted.await()
            manager.trustAndReconnect("raced")
            assertTrue(trustStarted.await(5, TimeUnit.SECONDS))
            assertTrue(trustStore.isPinned(host.host, host.port))

            val deletion = async(Dispatchers.Default) { repository.deleteHost(host) }
            releaseTrust.countDown()
            deletion.await()

            assertEquals(1, transport.trustCalls.get())
            assertFalse(trustStore.isPinned(host.host, host.port))
        } finally {
            releaseTrust.countDown()
            factory.releaseAll()
            manager.disconnectAll()
            scope.cancel()
        }
    }

    @Test
    fun acceptedKeyPersistenceIsSkippedIfHostIsDeletedAfterCheck() = runBlocking {
        val host = host(50, "pending-delete.example")
        val dao = FakeHostDao(mutableMapOf(host.id to host))
        val repository = HostRepository(
            dao,
            FakeCredentialStore(mapOf(host.id to StoredCredentials(password = "secret"))),
            EmptyTrustStore
        )
        val persisted = RecordingTrustStore()
        val factory = FakeTransportFactory(
            persistAction = { persisted.pin(it.host, it.port) }
        )
        val connectedStarted = CountDownLatch(1)
        val releaseConnected = CountDownLatch(1)
        val listener = RecordingListener(
            connectedStarted = connectedStarted,
            releaseConnected = releaseConnected
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = SSHConnectionManager(factory, scope)

        try {
            manager.connect("pending-delete", host, repository, listener)
            val transport = factory.await(host.host)
            transport.connectStarted.await()
            transport.releaseConnect.complete(Unit)
            assertTrue(connectedStarted.await(5, TimeUnit.SECONDS))

            repository.deleteHost(host)
            val readsAfterDelete = dao.getCalls.get()
            releaseConnected.countDown()
            dao.awaitReadAfter(readsAfterDelete)

            assertEquals(0, transport.persistCalls.get())
            assertFalse(persisted.isPinned(host.host, host.port))
        } finally {
            releaseConnected.countDown()
            factory.releaseAll()
            manager.disconnectAll()
            scope.cancel()
        }
    }

    @Test
    fun acceptedKeyPersistenceIsSkippedIfEndpointChangesAfterCheck() = runBlocking {
        val host = host(51, "pending-old.example")
        val updated = host.copy(host = "pending-new.example").apply {
            password = "new-secret"
        }
        val dao = FakeHostDao(mutableMapOf(host.id to host))
        val repository = HostRepository(
            dao,
            FakeCredentialStore(mapOf(host.id to StoredCredentials(password = "old-secret"))),
            EmptyTrustStore
        )
        val persisted = RecordingTrustStore()
        val factory = FakeTransportFactory(
            persistAction = { persisted.pin(it.host, it.port) }
        )
        val connectedStarted = CountDownLatch(1)
        val releaseConnected = CountDownLatch(1)
        val listener = RecordingListener(
            connectedStarted = connectedStarted,
            releaseConnected = releaseConnected
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = SSHConnectionManager(factory, scope)

        try {
            manager.connect("pending-edit", host, repository, listener)
            val transport = factory.await(host.host)
            transport.connectStarted.await()
            transport.releaseConnect.complete(Unit)
            assertTrue(connectedStarted.await(5, TimeUnit.SECONDS))

            repository.updateHost(updated)
            val readsAfterEdit = dao.getCalls.get()
            releaseConnected.countDown()
            dao.awaitReadAfter(readsAfterEdit)

            assertEquals(0, transport.persistCalls.get())
            assertFalse(persisted.isPinned(host.host, host.port))
        } finally {
            releaseConnected.countDown()
            factory.releaseAll()
            manager.disconnectAll()
            scope.cancel()
        }
    }

    private fun repositoryFor(vararg hosts: Host): HostRepository =
        repositoryFixture(*hosts).repository

    private fun repositoryFixture(vararg hosts: Host): RepositoryFixture {
        val dao = FakeHostDao(hosts.associateBy { it.id }.toMutableMap())
        val credentials = FakeCredentialStore(
            hosts.associate { it.id to StoredCredentials(password = "secret-${it.id}") }
        )
        return RepositoryFixture(
            HostRepository(dao, credentials, EmptyTrustStore),
            dao
        )
    }

    private data class RepositoryFixture(
        val repository: HostRepository,
        val dao: FakeHostDao
    )

    private fun host(id: Long, address: String) = Host(
        id = id,
        name = address,
        host = address,
        username = "user"
    )

    private fun forwardRule(id: Long, hostId: Long, port: Int) = PortForwardRule(
        id = id,
        hostId = hostId,
        direction = PortForwardDirection.LOCAL,
        listenPort = port,
        targetHost = "127.0.0.1",
        targetPort = 80,
        sortOrder = id.toInt()
    )

    private fun utf8(bytes: ByteArray): String = bytes.toString(Charsets.UTF_8)

    private class FakeTransportFactory(
        private val persistAction: (Host) -> Unit = {},
        private val trustAction: (Host) -> Boolean = { true }
    ) : SSHTransportFactory {
        private val created = CopyOnWriteArrayList<FakeTransport>()
        private val creationSignal = Channel<Unit>(Channel.UNLIMITED)

        override fun create(profile: ConnectionProfile): SSHTransport =
            FakeTransport(
                profile.host.copy(),
                profile.credentials,
                trustAction,
                persistAction
            ).also {
                created += it
                creationSignal.trySend(Unit)
            }

        val createdCount: Int
            get() = created.size

        fun count(address: String): Int = created.count { it.host.host == address }

        suspend fun await(address: String, occurrence: Int = 0): FakeTransport = withTimeout(5_000) {
            var match = created.filter { it.host.host == address }.getOrNull(occurrence)
            while (match == null) {
                creationSignal.receive()
                match = created.filter { it.host.host == address }.getOrNull(occurrence)
            }
            match
        }

        fun releaseAll() {
            created.forEach { it.releaseConnect.complete(Unit) }
        }

        fun createdTransports(): List<FakeTransport> = created.toList()
    }

    private class FakeTransport(
        val host: Host,
        val credentials: StoredCredentials,
        private val trustAction: (Host) -> Boolean,
        private val persistAction: (Host) -> Unit
    ) : SSHTransport {
        @Volatile override var listener: SSHConnectionListener? = null
        @Volatile override var isConnected = false
            private set
        val connectStarted = CompletableDeferred<Unit>()
        val releaseConnect = CompletableDeferred<Unit>()
        val sent = CopyOnWriteArrayList<ByteArray>()
        val sizes = CopyOnWriteArrayList<Pair<Int, Int>>()
        val trustCalls = AtomicInteger()
        val persistCalls = AtomicInteger()
        val persistCompleted = CompletableDeferred<Unit>()
        val startedForwardIds = CopyOnWriteArrayList<Long>()
        val failedForwardIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        @Volatile var transferFailure: Exception? = null
        @Volatile var blockUpload = false
        val uploadStarted = CompletableDeferred<Unit>()
        val releaseUpload = CompletableDeferred<Unit>()
        val cancelTransferCalled = CompletableDeferred<Unit>()
        @Volatile var destroyed = false

        override suspend fun connect() {
            connectStarted.complete(Unit)
            releaseConnect.await()
            if (!destroyed) {
                isConnected = true
                listener?.onStateChanged(SSHConnectionState.Connected)
            }
        }

        suspend fun emitOutput(text: String) {
            listener?.onOutput(text.toByteArray())
        }

        override fun trustCurrentHostKey(): Boolean {
            trustCalls.incrementAndGet()
            return trustAction(host)
        }

        override fun persistPendingHostKey() {
            persistCalls.incrementAndGet()
            persistAction(host)
            persistCompleted.complete(Unit)
        }

        override fun send(data: ByteArray, offset: Int, count: Int): Boolean {
            if (destroyed || offset < 0 || count <= 0 || count > data.size - offset) return false
            sent += data.copyOfRange(offset, offset + count)
            return true
        }

        override fun updatePtySize(columns: Int, rows: Int) {
            if (!destroyed) sizes += columns to rows
        }

        override suspend fun startPortForwards(
            rules: List<PortForwardRule>,
            onStateChanged: (PortForwardRuntimeState) -> Unit
        ) {
            rules.forEach { rule ->
                startedForwardIds += rule.id
                onStateChanged(PortForwardRuntimeState(rule, PortForwardStatus.STARTING))
                onStateChanged(
                    if (rule.id in failedForwardIds) {
                        PortForwardRuntimeState(rule, PortForwardStatus.FAILED, error = "rejected")
                    } else {
                        PortForwardRuntimeState(rule, PortForwardStatus.ACTIVE, rule.listenPort)
                    }
                )
            }
        }

        override suspend fun listRemoteDirectory(path: String) = RemoteDirectoryListing(
            "/home/user",
            listOf(RemoteFileEntry("hello.txt", "/home/user/hello.txt", false, size = 4))
        )

        override suspend fun uploadFile(
            input: java.io.InputStream,
            remoteDirectory: String,
            fileName: String,
            overwriteExisting: Boolean,
            totalBytes: Long?,
            onProgress: (Long) -> Unit
        ) {
            transferFailure?.let { throw it }
            if (blockUpload) {
                uploadStarted.complete(Unit)
                // Model the real SSHConnection's non-suspending remote rename
                // transaction: coroutine cancellation is recorded, but the
                // short commit/compensation window still runs to completion.
                withContext(NonCancellable) { releaseUpload.await() }
            }
            onProgress(input.readBytes().size.toLong())
        }

        override suspend fun downloadFile(
            remotePath: String,
            output: java.io.OutputStream,
            totalBytes: Long?,
            onProgress: (Long) -> Unit
        ) {
            transferFailure?.let { throw it }
            output.write("body".toByteArray())
            onProgress(4)
        }

        override fun destroy() {
            destroyed = true
            isConnected = false
        }

        override fun cancelFileTransfer() {
            cancelTransferCalled.complete(Unit)
        }
    }

    private class RecordingListener(
        private val outputRelease: CompletableDeferred<Unit>? = null,
        private val connectedStarted: CountDownLatch? = null,
        private val releaseConnected: CountDownLatch? = null
    ) : SSHConnectionListener {
        val states = CopyOnWriteArrayList<SSHConnectionState>()
        val outputs = CopyOnWriteArrayList<ByteArray>()
        val outputStarted = CompletableDeferred<Unit>()
        private val stateSignal = Channel<Unit>(Channel.UNLIMITED)
        @Volatile var disconnectedCount = 0

        override fun onStateChanged(state: SSHConnectionState) {
            states += state
            stateSignal.trySend(Unit)
            if (state is SSHConnectionState.Connected && connectedStarted != null) {
                connectedStarted.countDown()
                check(releaseConnected?.await(5, TimeUnit.SECONDS) == true) {
                    "test Connected callback was not released"
                }
            }
        }

        override suspend fun onOutput(data: ByteArray) {
            outputStarted.complete(Unit)
            outputRelease?.await()
            outputs += data.copyOf()
        }

        override fun onDisconnected() {
            disconnectedCount++
        }

        suspend fun awaitState(predicate: (SSHConnectionState) -> Boolean) = withTimeout(5_000) {
            while (states.none(predicate)) stateSignal.receive()
        }
    }

    private class FakeCredentialStore(initial: Map<Long, StoredCredentials>) : CredentialStore {
        private val values = initial.toMutableMap()

        override fun requireAvailable() = Unit
        override fun getCredentials(hostId: Long): StoredCredentials =
            values[hostId] ?: StoredCredentials()

        override fun replaceCredentials(hostId: Long, credentials: StoredCredentials) {
            values[hostId] = credentials
        }
    }

    private class FakeHostDao(private val hosts: MutableMap<Long, Host>) : HostDao {
        val getCalls = AtomicInteger()
        private val getSignal = Channel<Unit>(Channel.UNLIMITED)
        var getStarted: CompletableDeferred<Unit>? = null
        var releaseGet: CompletableDeferred<Unit>? = null

        fun replace(host: Host) {
            hosts[host.id] = host
        }

        suspend fun awaitReadAfter(previousCalls: Int) = withTimeout(5_000) {
            while (getCalls.get() <= previousCalls) getSignal.receive()
        }

        override fun getAllHosts(): Flow<List<Host>> = flowOf(hosts.values.toList())
        override suspend fun getHostById(id: Long): Host? {
            getCalls.incrementAndGet()
            getSignal.trySend(Unit)
            getStarted?.complete(Unit)
            releaseGet?.await()
            return hosts[id]
        }
        override suspend fun insertHost(host: Host): Long {
            hosts[host.id] = host
            return host.id
        }

        override suspend fun updateHost(host: Host): Int {
            if (host.id !in hosts) return 0
            hosts[host.id] = host
            return 1
        }

        override suspend fun deleteHost(host: Host): Int =
            if (hosts.remove(host.id) == null) 0 else 1

        override suspend fun countHostsAtEndpointExcluding(
            host: String,
            port: Int,
            excludedHostId: Long
        ): Int = hosts.values.count {
            it.id != excludedHostId && it.host == host && it.port == port
        }

        override suspend fun updateLastConnected(hostId: Long, timestamp: Long) {
            hosts[hostId]?.let { hosts[hostId] = it.copy(lastConnected = timestamp) }
        }
    }

    private class PausingUpdateHostDao(initial: Host) : HostDao {
        private val hosts = mutableMapOf(initial.id to initial)
        val updateStarted = CompletableDeferred<Unit>()
        val releaseUpdate = CountDownLatch(1)

        override fun getAllHosts(): Flow<List<Host>> = flowOf(hosts.values.toList())
        override suspend fun getHostById(id: Long): Host? = hosts[id]
        override suspend fun insertHost(host: Host): Long = error("not used")

        override suspend fun updateHost(host: Host): Int {
            updateStarted.complete(Unit)
            check(releaseUpdate.await(5, TimeUnit.SECONDS)) { "test update was not released" }
            hosts[host.id] = host
            return 1
        }

        override suspend fun deleteHost(host: Host): Int = error("not used")

        override suspend fun countHostsAtEndpointExcluding(
            host: String,
            port: Int,
            excludedHostId: Long
        ): Int = 0

        override suspend fun updateLastConnected(hostId: Long, timestamp: Long) {
            hosts[hostId]?.let { hosts[hostId] = it.copy(lastConnected = timestamp) }
        }
    }

    private class FixedPortForwardRuleDao(
        private val rules: List<PortForwardRule>
    ) : com.sshtool.data.repository.PortForwardRuleDao {
        override suspend fun getRulesForHost(hostId: Long): List<PortForwardRule> =
            rules.filter { it.hostId == hostId }

        override suspend fun getEnabledRulesForHost(hostId: Long): List<PortForwardRule> =
            getRulesForHost(hostId).filter { it.enabled }

        override suspend fun insertRules(rules: List<PortForwardRule>) = error("not used")

        override suspend fun deleteRulesForHost(hostId: Long): Int = error("not used")
    }

    private object EmptyTrustStore : HostTrustStore {
        override fun snapshotHost(host: String, port: Int) = HostTrustSnapshot(emptyList())
        override fun removeHost(host: String, port: Int) = Unit
        override fun restoreHost(host: String, port: Int, snapshot: HostTrustSnapshot) = Unit
    }

    private class RecordingTrustStore : HostTrustStore {
        private val pins = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<String, Int>>()

        fun pin(host: String, port: Int) {
            pins += host to port
        }

        fun isPinned(host: String, port: Int): Boolean = host to port in pins

        override fun snapshotHost(host: String, port: Int): HostTrustSnapshot =
            HostTrustSnapshot(
                if (isPinned(host, port)) {
                    listOf(HostKeyStoreBackend.Entry("test", port, "ssh-ed25519", "test"))
                } else {
                    emptyList()
                }
            )

        override fun removeHost(host: String, port: Int) {
            pins -= host to port
        }

        override fun restoreHost(host: String, port: Int, snapshot: HostTrustSnapshot) {
            if (snapshot.entries.isEmpty()) pins -= host to port else pins += host to port
        }
    }
}
