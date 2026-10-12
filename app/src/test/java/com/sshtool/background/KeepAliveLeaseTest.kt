package com.sshtool.background

import org.junit.Assert.*
import org.junit.Test

class KeepAliveLeaseTest {
    private class Effects {
        val timeouts = mutableListOf<Long>()
        var releases = 0
        var cancels = 0
        var failures = 0
        var failAcquire = false
        var failSchedule = false
        var pending: (() -> Unit)? = null
        val lease = KeepAliveLease(
            acquire = { timeouts += it; if (failAcquire) throw IllegalStateException("power unavailable") },
            release = { releases++ },
            schedule = { delay, task ->
                assertEquals(KeepAliveLease.RENEWAL_MS, delay)
                if (failSchedule) throw IllegalStateException("scheduler unavailable")
                pending = task
                val cancel: () -> Unit = { cancels++; pending = null }
                cancel
            },
            onRenewalFailure = { failures++ }
        )
    }

    @Test fun acquisitionIsBoundedRenewableAndReleasedExactlyOnce() {
        val e = Effects()
        e.lease.start()
        e.lease.start()
        assertEquals(listOf(KeepAliveLease.TIMEOUT_MS), e.timeouts)
        e.pending!!.invoke()
        assertEquals(2, e.timeouts.size)
        e.lease.stop()
        e.lease.stop()
        assertEquals(1, e.releases)
        assertEquals(1, e.cancels)
        assertNull(e.pending)
    }

    @Test fun staleRenewalAfterStopCannotReacquireOrInterfereWithNewLease() {
        val e = Effects()
        e.lease.start()
        val stale = e.pending!!
        e.lease.stop()
        e.lease.start()
        stale()
        assertEquals(2, e.timeouts.size)
        assertNotNull(e.pending)
        e.lease.stop()
        assertEquals(2, e.releases)
    }

    @Test fun startAndSchedulingFailureReleaseAnyPartialAcquisition() {
        for (failSchedule in listOf(false, true)) {
            val e = Effects()
            e.failAcquire = !failSchedule
            e.failSchedule = failSchedule
            assertThrows(IllegalStateException::class.java) { e.lease.start() }
            e.lease.stop()
            assertEquals(1, e.releases)
            assertNull(e.pending)
        }
    }

    @Test fun renewalFailureReleasesAndSignalsServiceShutdown() {
        val e = Effects()
        e.lease.start()
        e.failAcquire = true
        e.pending!!.invoke()
        assertEquals(1, e.releases)
        assertEquals(1, e.failures)
        e.lease.stop()
        assertEquals(1, e.releases)
    }
}
