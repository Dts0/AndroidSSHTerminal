package com.sshtool.background

import com.sshtool.ssh.SSHConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class KeepAliveState(
    val enabled: Boolean,
    val sessionCount: Int = 0,
    val serviceRequested: Boolean = false,
    val startFailed: Boolean = false
) {
    val shouldRun: Boolean get() = enabled && sessionCount > 0
}

internal fun needsSshKeepAlive(state: SSHConnectionState): Boolean =
    state is SSHConnectionState.Connecting || state is SSHConnectionState.Connected ||
        state is SSHConnectionState.HostKeyConfirmationRequired

/** Main-thread state machine; platform effects are injectable for JVM tests. */
internal class KeepAlivePolicy(
    initialEnabled: Boolean,
    private val persistEnabled: (Boolean) -> Unit,
    private val startService: () -> Unit,
    private val stopService: () -> Unit
) {
    private var foreground = false
    private val _state = MutableStateFlow(KeepAliveState(initialEnabled))
    val state: StateFlow<KeepAliveState> = _state.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        if (_state.value.enabled == enabled) return
        persistEnabled(enabled)
        _state.value = _state.value.copy(enabled = enabled, startFailed = false)
        reconcile()
    }

    fun setForeground(foreground: Boolean) {
        this.foreground = foreground
        if (foreground) _state.value = _state.value.copy(startFailed = false)
        reconcile()
    }

    fun updateSessionCount(count: Int) {
        require(count >= 0)
        if (_state.value.sessionCount == count) return
        _state.value = _state.value.copy(sessionCount = count)
        reconcile()
    }

    fun onServiceStarted() {
        if (_state.value.shouldRun) {
            _state.value = _state.value.copy(serviceRequested = true, startFailed = false)
        }
    }

    fun onServiceStopped() {
        _state.value = _state.value.copy(serviceRequested = false)
        // Never resurrect a service from its destruction callback in the background.
    }

    fun onServiceFailed() {
        _state.value = _state.value.copy(serviceRequested = false, startFailed = true)
    }

    private fun reconcile() {
        val current = _state.value
        if (!current.shouldRun) {
            _state.value = current.copy(serviceRequested = false, startFailed = false)
            if (current.serviceRequested) stopService()
        } else if (foreground && !current.serviceRequested && !current.startFailed) {
            _state.value = current.copy(serviceRequested = true)
            try {
                startService()
            } catch (_: Exception) {
                onServiceFailed()
            }
        }
    }
}
