package com.sshtool.background

import com.sshtool.ssh.SSHConnectionState
import org.junit.Assert.*
import org.junit.Test

class KeepAlivePolicyTest {
    private class Effects(initialEnabled: Boolean = false) {
        var saved = initialEnabled
        var starts = 0
        var stops = 0
        var failStart = false
        val policy = KeepAlivePolicy(initialEnabled,
            persistEnabled = { saved = it },
            startService = { starts++; if (failStart) throw IllegalStateException("restricted") },
            stopService = { stops++ }
        )
    }

    @Test fun disabledModeNeverStartsAndPreferenceSurvivesReconstruction() {
        val e = Effects()
        e.policy.setForeground(true)
        e.policy.updateSessionCount(2)
        assertEquals(0, e.starts)
        e.policy.setEnabled(true)
        assertTrue(e.saved)
        assertEquals(1, e.starts)
        assertTrue(Effects(e.saved).policy.state.value.enabled)
        assertEquals(0, Effects(e.saved).policy.state.value.sessionCount)
    }

    @Test fun enablingWithoutSessionsDoesNotRunAnIdleService() {
        val e = Effects()
        e.policy.setForeground(true)
        e.policy.setEnabled(true)
        assertEquals(0, e.starts)
        assertFalse(e.policy.state.value.shouldRun)
    }

    @Test fun backgroundAndRecreationKeepTheServiceUntilLastSessionEnds() {
        val e = Effects(true)
        e.policy.setForeground(true)
        e.policy.updateSessionCount(2)
        e.policy.setForeground(false)
        e.policy.updateSessionCount(1)
        e.policy.setForeground(true)
        assertEquals(1, e.starts)
        assertEquals(0, e.stops)
        e.policy.setForeground(false)
        e.policy.updateSessionCount(0)
        assertEquals(1, e.stops)
        assertTrue(e.policy.state.value.enabled)
        assertFalse(e.policy.state.value.shouldRun)
    }

    @Test fun newBackgroundDemandDefersStartUntilForeground() {
        val e = Effects(true)
        e.policy.updateSessionCount(1)
        assertEquals(0, e.starts)
        e.policy.setForeground(true)
        assertEquals(1, e.starts)
    }

    @Test fun startFailureDoesNotLoopAndReturningToForegroundRetries() {
        val e = Effects(true)
        e.failStart = true
        e.policy.setForeground(true)
        e.policy.updateSessionCount(1)
        assertTrue(e.policy.state.value.startFailed)
        assertFalse(e.policy.state.value.serviceRequested)
        e.policy.updateSessionCount(2)
        assertEquals(1, e.starts)
        e.policy.setForeground(false)
        e.failStart = false
        e.policy.setForeground(true)
        assertEquals(2, e.starts)
        assertFalse(e.policy.state.value.startFailed)
    }

    @Test fun notificationDisableAndRepeatedCleanupDoNotAffectSessionCount() {
        val e = Effects(true)
        e.policy.setForeground(true)
        e.policy.updateSessionCount(3)
        e.policy.setEnabled(false)
        e.policy.setEnabled(false)
        e.policy.onServiceStopped()
        e.policy.onServiceStopped()
        assertFalse(e.saved)
        assertEquals(3, e.policy.state.value.sessionCount)
        assertEquals(1, e.stops)
        assertEquals(1, e.starts)
    }

    @Test fun stoppedOrFailedServiceDoesNotResurrectInBackground() {
        val e = Effects(true)
        e.policy.setForeground(true)
        e.policy.updateSessionCount(1)
        e.policy.setForeground(false)
        e.policy.onServiceFailed()
        e.policy.onServiceStopped()
        e.policy.updateSessionCount(2)
        assertEquals(1, e.starts)
        assertTrue(e.policy.state.value.startFailed)
        e.policy.updateSessionCount(0)
        assertFalse(e.policy.state.value.startFailed)
    }

    @Test fun serviceAcknowledgementDoesNotReviveDisabledOrIdleMode() {
        val e = Effects(true)
        e.policy.onServiceStarted()
        assertFalse(e.policy.state.value.serviceRequested)
        e.policy.setForeground(true)
        e.policy.updateSessionCount(1)
        e.policy.onServiceStopped()
        e.policy.onServiceStarted()
        assertTrue(e.policy.state.value.serviceRequested)
        e.policy.setEnabled(false)
        e.policy.onServiceStarted()
        assertFalse(e.policy.state.value.serviceRequested)
    }

    @Test fun onlyLiveOrPendingTrustSessionsCount() {
        assertTrue(needsSshKeepAlive(SSHConnectionState.Connecting))
        assertTrue(needsSshKeepAlive(SSHConnectionState.Connected))
        assertTrue(needsSshKeepAlive(SSHConnectionState.HostKeyConfirmationRequired("h", 22, "a", "f")))
        assertFalse(needsSshKeepAlive(SSHConnectionState.Disconnected))
        assertFalse(needsSshKeepAlive(SSHConnectionState.Error("failure")))
        assertFalse(needsSshKeepAlive(SSHConnectionState.HostKeyChanged("h", 22, "a", "f")))
    }
}
