package com.sshtool.ssh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ConflatedPtyResizeDispatcherTest {

    @Test
    fun resizeDuringConnectIsFlushedOnceAfterInitialRequest() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val target = Target()
        val sends = CopyOnWriteArrayList<Pair<Int, Int>>()
        val sent = CompletableDeferred<Unit>()
        val dispatcher = dispatcher(scope, target) { _, columns, rows ->
            sends += columns to rows
            sent.complete(Unit)
        }

        try {
            val initial = dispatcher.snapshotForInitialRequest()
            dispatcher.update(120, 40)
            dispatcher.markInitialRequestApplied(initial)

            withTimeout(5_000) { sent.await() }
            assertEquals(listOf(120 to 40), sends)
        } finally {
            dispatcher.close()
            scope.cancel()
        }
    }

    @Test
    fun blockedWriteDoesNotBlockUpdatesAndConflatesObsoleteSizes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val target = Target()
        val sends = CopyOnWriteArrayList<Pair<Int, Int>>()
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val finalSent = CompletableDeferred<Unit>()
        val dispatcher = dispatcher(scope, target) { _, columns, rows ->
            sends += columns to rows
            if (sends.size == 1) {
                firstStarted.countDown()
                check(releaseFirst.await(5, TimeUnit.SECONDS)) { "first resize was not released" }
            } else {
                finalSent.complete(Unit)
            }
        }

        try {
            val initial = dispatcher.snapshotForInitialRequest()
            dispatcher.markInitialRequestApplied(initial)
            dispatcher.update(100, 30)
            check(firstStarted.await(5, TimeUnit.SECONDS)) { "first resize did not start" }

            dispatcher.update(110, 31)
            dispatcher.update(130, 42)
            dispatcher.update(130, 42)
            releaseFirst.countDown()

            withTimeout(5_000) { finalSent.await() }
            assertEquals(listOf(100 to 30, 130 to 42), sends)
        } finally {
            releaseFirst.countDown()
            dispatcher.close()
            scope.cancel()
        }
    }

    @Test
    fun returningToAlreadyAppliedTupleBeforeWorkerRunsSendsNothing() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val target = Target()
        val sent = CompletableDeferred<Unit>()
        val dispatcher = dispatcher(scope, target) { _, _, _ -> sent.complete(Unit) }

        try {
            val initial = dispatcher.snapshotForInitialRequest()
            dispatcher.update(100, 30)
            dispatcher.update(initial.columns, initial.rows)
            dispatcher.markInitialRequestApplied(initial)

            assertNull(withTimeoutOrNull(300) { sent.await() })
        } finally {
            dispatcher.close()
            scope.cancel()
        }
    }

    @Test
    fun closeSuppressesPendingAndFutureResize() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val target = Target()
        val sent = CompletableDeferred<Unit>()
        val dispatcher = dispatcher(scope, target) { _, _, _ -> sent.complete(Unit) }
        val initial = dispatcher.snapshotForInitialRequest()
        dispatcher.markInitialRequestApplied(initial)
        dispatcher.close()
        dispatcher.update(140, 50)

        try {
            assertNull(withTimeoutOrNull(300) { sent.await() })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun failedBestEffortResizeIsNotMarkedAppliedAndLaterGeometryCanRecover() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val target = Target()
        val expectedFailure = Exception("synthetic resize failure")
        val sends = CopyOnWriteArrayList<Pair<Int, Int>>()
        val failureReported = CompletableDeferred<Exception>()
        val laterSend = CompletableDeferred<Unit>()
        val dispatcher = dispatcher(
            scope = scope,
            target = target,
            send = { _, columns, rows ->
                sends += columns to rows
                if (sends.size == 1) throw expectedFailure
                laterSend.complete(Unit)
            },
            onFailure = { failureReported.complete(it) }
        )

        try {
            val initial = dispatcher.snapshotForInitialRequest()
            dispatcher.markInitialRequestApplied(initial)
            dispatcher.update(100, 30)

            assertSame(expectedFailure, withTimeout(5_000) { failureReported.await() })
            // window-change is best effort: do not risk duplicate SIGWINCH for
            // an uncertain write, and do not retry an identical layout signal.
            dispatcher.update(100, 30)
            // A failed send must not replace the tuple known to be installed
            // by the initial pty-req; returning to it therefore needs no send.
            dispatcher.update(initial.columns, initial.rows)
            assertNull(withTimeoutOrNull(300) { laterSend.await() })

            dispatcher.update(110, 31)
            withTimeout(5_000) { laterSend.await() }
            assertEquals(listOf(100 to 30, 110 to 31), sends)
        } finally {
            dispatcher.close()
            scope.cancel()
        }
    }

    private fun dispatcher(
        scope: CoroutineScope,
        target: Target,
        onFailure: (Exception) -> Unit = {
            throw AssertionError("unexpected resize failure", it)
        },
        send: (Target, Int, Int) -> Unit
    ) = ConflatedPtyResizeDispatcher(
        scope = scope,
        initialColumns = 80,
        initialRows = 24,
        targetProvider = { target },
        isTargetConnected = { it.connected },
        sendResize = send,
        onFailure = onFailure
    )

    private class Target(@Volatile var connected: Boolean = true)
}
