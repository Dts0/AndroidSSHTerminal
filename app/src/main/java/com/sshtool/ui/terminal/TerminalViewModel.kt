package com.sshtool.ui.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sshtool.data.model.Host
import com.sshtool.data.repository.HostRepository
import com.sshtool.ssh.SSHConnectionListener
import com.sshtool.ssh.SSHConnectionManager
import com.sshtool.ssh.SSHConnectionState
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

data class TerminalTabState(
    val sessionId: String,
    val host: Host,
    val bridge: SshTerminalSession,
    val terminalSession: TerminalSession,
    val connectionState: SSHConnectionState
)

data class TerminalUiState(
    val sessions: List<TerminalTabState> = emptyList(),
    val activeSessionId: String? = null
)

/** Owns SSH transports and terminal scrollback for the lifetime of MainActivity. */
class TerminalViewModel(private val repository: HostRepository) : ViewModel() {

    private data class ManagedSession(
        val id: String,
        var host: Host,
        val bridge: SshTerminalSession,
        val terminalSession: TerminalSession,
        var connectionState: SSHConnectionState = SSHConnectionState.Connecting,
        val operationGeneration: AtomicLong = AtomicLong(0)
    )

    private val manager = SSHConnectionManager()
    private val sessions = linkedMapOf<String, ManagedSession>()
    private val sessionMutex = Mutex()
    private var activeSessionId: String? = null

    private val _uiState = MutableStateFlow(TerminalUiState())
    val uiState: StateFlow<TerminalUiState> = _uiState.asStateFlow()

    /** Reuse an existing session for list navigation; otherwise create one. */
    suspend fun ensureSessionForHost(hostId: Long): String? = sessionMutex.withLock {
        sessions.values.firstOrNull { it.host.id == hostId }?.let {
            selectSession(it.id)
            return@withLock it.id
        }
        // A null row means "not found". Room failures stay distinguishable so
        // the Fragment can report them; credentials are only read by manager.
        val host = repository.getHostMetadataById(hostId) ?: return@withLock null
        createSession(host)
    }

    /** The in-terminal host picker intentionally opens an independent tab. */
    suspend fun addSessionForHost(hostId: Long): String? = sessionMutex.withLock {
        val host = repository.getHostMetadataById(hostId) ?: return@withLock null
        createSession(host)
    }

    private fun createSession(host: Host): String {
        val sessionId = UUID.randomUUID().toString()
        // copy() carries only Room fields; @Ignore credentials remain null and
        // never enter the public StateFlow or long-lived managed session.
        val safeHost = host.copy()
        val bridge = SshTerminalSession(
            repositoryContext(),
            outputWriter = { data, offset, count ->
                manager.send(sessionId, data, offset, count)
            },
            onWriteRejected = { handleSendRejected(sessionId) }
        )
        val terminalSession = bridge.create()
        val managed = ManagedSession(sessionId, safeHost, bridge, terminalSession)
        sessions[sessionId] = managed
        activeSessionId = sessionId
        publish()

        manager.connect(
            sessionId,
            safeHost,
            repository,
            connectionListener(managed, managed.operationGeneration.get())
        )
        return sessionId
    }

    /** Bind callbacks to one VM operation token before manager dispatch. */
    private fun connectionListener(
        managed: ManagedSession,
        operationGeneration: Long
    ): SSHConnectionListener = object : SSHConnectionListener {
        override fun onStateChanged(state: SSHConnectionState) {
            onMain {
                if (sessions[managed.id] === managed &&
                    managed.operationGeneration.get() == operationGeneration) {
                    managed.connectionState = state
                    // A resize attempted while the SSH shell is still opening
                    // is intentionally ignored by the transport. Re-send this
                    // session's current emulator geometry at its own Connected
                    // transition, even when another tab is active.
                    if (state is SSHConnectionState.Connected) {
                        managed.terminalSession.emulator?.let { emulator ->
                            manager.updatePtySizeForSession(
                                managed.id,
                                emulator.mColumns,
                                emulator.mRows
                            )
                        }
                    }
                    publish()
                }
            }
        }

        override suspend fun onOutput(data: ByteArray) {
            withContext(Dispatchers.Main.immediate) {
                if (sessions[managed.id] === managed &&
                    managed.operationGeneration.get() == operationGeneration) {
                    managed.bridge.appendOutput(data)
                }
            }
        }

        override fun onDisconnected() = Unit
    }

    /** HostRepository is application-backed; retrieve the same safe context via the app singleton. */
    private fun repositoryContext() = com.sshtool.SSHToolApp.instance.applicationContext

    private fun onMain(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.Main.immediate) { block() }
    }

    fun selectSession(sessionId: String) {
        if (!sessions.containsKey(sessionId)) return
        activeSessionId = sessionId
        publish()
    }

    fun send(sessionId: String, data: String): Boolean {
        val accepted = manager.send(sessionId, data)
        if (!accepted) handleSendRejected(sessionId)
        return accepted
    }

    private fun handleSendRejected(sessionId: String) {
        onMain {
            val managed = sessions[sessionId] ?: return@onMain
            if (managed.connectionState !is SSHConnectionState.Connected) return@onMain
            managed.operationGeneration.incrementAndGet()
            managed.connectionState = SSHConnectionState.Error(
                "发送缓冲区已满或连接已断开"
            )
            publish()
            // Incrementing the VM token above prevents manager.disconnect's
            // old listener from replacing this actionable error with a generic
            // Disconnected state.
            manager.disconnect(sessionId)
        }
    }

    fun resize(sessionId: String, columns: Int, rows: Int) {
        manager.updatePtySizeForSession(sessionId, columns, rows)
    }

    fun paste(sessionId: String, text: String) {
        sessions[sessionId]?.bridge?.paste(text)
    }

    fun disconnect(sessionId: String) {
        sessions[sessionId]?.let {
            it.operationGeneration.incrementAndGet()
            it.connectionState = SSHConnectionState.Disconnected
        }
        publish()
        manager.disconnect(sessionId)
    }

    fun closeSession(sessionId: String) {
        val removed = sessions.remove(sessionId) ?: return
        removed.operationGeneration.incrementAndGet()
        removed.bridge.close()
        manager.removeSession(sessionId)
        if (activeSessionId == sessionId) activeSessionId = sessions.keys.lastOrNull()
        publish()
    }

    fun reconnect(sessionId: String) {
        val managed = sessions[sessionId] ?: return
        val operationGeneration = managed.operationGeneration.incrementAndGet()
        managed.connectionState = SSHConnectionState.Connecting
        publish()
        viewModelScope.launch {
            try {
                val refreshed = repository.getHostMetadataById(managed.host.id)
                if (sessions[sessionId] !== managed ||
                    managed.operationGeneration.get() != operationGeneration) return@launch
                if (refreshed == null) {
                    managed.connectionState = SSHConnectionState.Error("主机不存在")
                    publish()
                    manager.disconnect(sessionId)
                    return@launch
                }
                managed.host = refreshed.copy()
                publish()
                manager.reconnect(
                    sessionId,
                    refreshed.copy(),
                    connectionListener(managed, operationGeneration)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (sessions[sessionId] === managed &&
                    managed.operationGeneration.get() == operationGeneration) {
                    val detail = e.localizedMessage?.takeIf(String::isNotBlank)
                    managed.connectionState = SSHConnectionState.Error(
                        detail?.let { "无法加载主机：$it" } ?: "无法加载主机"
                    )
                    publish()
                    manager.disconnect(sessionId)
                }
            }
        }
    }

    fun trustAndReconnect(sessionId: String) {
        val managed = sessions[sessionId] ?: return
        val operationGeneration = managed.operationGeneration.incrementAndGet()
        manager.reattachListener(
            sessionId,
            connectionListener(managed, operationGeneration)
        )
        managed.connectionState = SSHConnectionState.Connecting
        publish()
        manager.trustAndReconnect(sessionId)
    }

    private fun publish() {
        _uiState.value = TerminalUiState(
            sessions = sessions.values.map(::snapshot),
            activeSessionId = activeSessionId?.takeIf(sessions::containsKey)
        )
    }

    private fun snapshot(session: ManagedSession) = TerminalTabState(
        sessionId = session.id,
        host = session.host,
        bridge = session.bridge,
        terminalSession = session.terminalSession,
        connectionState = session.connectionState
    )

    override fun onCleared() {
        sessions.values.forEach { it.bridge.close() }
        sessions.clear()
        _uiState.value = TerminalUiState()
        manager.shutdown()
        super.onCleared()
    }
}

class TerminalViewModelFactory(
    private val repository: HostRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TerminalViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TerminalViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
