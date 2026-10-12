package com.sshtool.ui.terminal

import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sshtool.R
import com.sshtool.SSHToolApp
import com.sshtool.background.needsSshKeepAlive
import com.sshtool.data.model.Host
import com.sshtool.data.repository.HostRepository
import com.sshtool.ssh.FileTransferDirection
import com.sshtool.ssh.FileTransferState
import com.sshtool.ssh.PortForwardRuntimeState
import com.sshtool.ssh.RemoteDirectoryListing
import com.sshtool.ssh.SSHConnectionListener
import com.sshtool.ssh.SSHConnectionManager
import com.sshtool.ssh.SSHConnectionState
import com.sshtool.util.UserArgumentException
import com.sshtool.util.UserIOException
import com.sshtool.util.UserMessage
import com.termux.terminal.TerminalSession
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class TerminalTabState(
    val sessionId: String,
    val host: Host,
    val bridge: SshTerminalSession,
    val terminalSession: TerminalSession,
    val connectionState: SSHConnectionState,
    val portForwardStates: List<PortForwardRuntimeState> = emptyList(),
    val fileTransferState: FileTransferState = FileTransferState.Idle,
    val configurationStale: Boolean = false
)

data class TerminalUiState(
    val sessions: List<TerminalTabState> = emptyList(),
    val activeSessionId: String? = null
)

sealed interface TerminalUiEvent {
    data class SendRejected(val sessionId: String, val hostName: String) : TerminalUiEvent
}

/** Owns SSH transports and terminal scrollback for the lifetime of MainActivity. */
class TerminalViewModel(private val repository: HostRepository) : ViewModel() {

    private data class ManagedSession(
        val id: String,
        var host: Host,
        val bridge: SshTerminalSession,
        val terminalSession: TerminalSession,
        var connectionState: SSHConnectionState = SSHConnectionState.Connecting,
        var configurationStale: Boolean = false,
        val configurationGeneration: AtomicLong = AtomicLong(0),
        val operationGeneration: AtomicLong = AtomicLong(0),
        var lastSendRejectionNanos: Long? = null
    )

    private data class TransferStateOverride(
        val state: FileTransferState,
        /** Preserve the local state only until the manager advances this session. */
        val managerStateAtCreation: FileTransferState
    )

    private val manager = SSHConnectionManager()
    private val keepAliveController = SSHToolApp.instance.keepAliveController
    private val keepAliveOwnerToken = Any()
    private val sessions = linkedMapOf<String, ManagedSession>()
    private val sessionMutex = Mutex()
    private var activeSessionId: String? = null
    private var portForwardStates: Map<String, List<PortForwardRuntimeState>> = emptyMap()
    private var managerFileTransferStates: Map<String, FileTransferState> = emptyMap()
    private val transferStateOverrides = mutableMapOf<String, TransferStateOverride>()
    /** Masks a transport generation's terminal/progress state after it is retired. */
    private val resetFileTransferSessions = mutableSetOf<String>()
    private val transferRequestJobs = mutableMapOf<String, Job>()

    private val _uiState = MutableStateFlow(TerminalUiState())
    val uiState: StateFlow<TerminalUiState> = _uiState.asStateFlow()
    private val _events = MutableSharedFlow<TerminalUiEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<TerminalUiEvent> = _events.asSharedFlow()

    init {
        keepAliveController.attachOwner(keepAliveOwnerToken) {
            sessions.keys.toList().forEach(::closeSession)
        }
        // Manager flows are transport-owned and can change without an SSH
        // connection-state callback (progress ticks and individual forwarding
        // failures). Fold them into the activity-owned UI snapshot.
        viewModelScope.launch {
            manager.portForwardStates.collect { states ->
                portForwardStates = states
                publish()
            }
        }
        viewModelScope.launch {
            manager.fileTransferStates.collect { states ->
                managerFileTransferStates = states
                transferStateOverrides.entries.removeAll { (sessionId, override) ->
                    (states[sessionId] ?: FileTransferState.Idle) !=
                        override.managerStateAtCreation
                }
                publish()
            }
        }
    }

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
            connectionListener(
                managed,
                managed.operationGeneration.get(),
                managed.configurationGeneration.get()
            )
        )
        return sessionId
    }

    /** Bind callbacks to one VM operation token before manager dispatch. */
    private fun connectionListener(
        managed: ManagedSession,
        operationGeneration: Long,
        connectionConfigurationGeneration: Long
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
                        // A host edit can finish while an old handshake is in
                        // flight. Never let its late Connected callback erase
                        // the explicit "reconnect required" marker.
                        managed.configurationStale =
                            managed.configurationGeneration.get() !=
                                connectionConfigurationGeneration
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
            val now = System.nanoTime()
            val lastRejection = managed.lastSendRejectionNanos
            if (lastRejection == null ||
                now - lastRejection >= SEND_REJECTION_EVENT_INTERVAL_NANOS) {
                managed.lastSendRejectionNanos = now
                _events.tryEmit(TerminalUiEvent.SendRejected(sessionId, managed.host.name))
            }
        }
    }

    fun resize(sessionId: String, columns: Int, rows: Int) {
        manager.updatePtySizeForSession(sessionId, columns, rows)
    }

    fun paste(sessionId: String, text: String) {
        sessions[sessionId]?.bridge?.paste(text)
    }

    fun disconnect(sessionId: String) {
        cancelTransferForTransportChange(sessionId)
        sessions[sessionId]?.let {
            it.operationGeneration.incrementAndGet()
            it.connectionState = SSHConnectionState.Disconnected
        }
        publish()
        manager.disconnect(sessionId)
    }

    /**
     * Called after an existing host (including its forwarding rules) is saved.
     * Connected transports intentionally keep their immutable old profile;
     * attempts that have not connected yet are retired so they cannot finish
     * with an obsolete endpoint or trust prompt.
     */
    fun markHostConfigurationStale(hostId: Long) {
        val matching = sessions.values.filter { it.host.id == hostId }
        val pendingAttempts = mutableListOf<String>()
        matching.forEach { managed ->
            managed.configurationGeneration.incrementAndGet()
            managed.configurationStale = true
            when (managed.connectionState) {
                SSHConnectionState.Connecting,
                is SSHConnectionState.HostKeyConfirmationRequired,
                is SSHConnectionState.HostKeyChanged -> {
                    managed.operationGeneration.incrementAndGet()
                    managed.connectionState = SSHConnectionState.Disconnected
                    pendingAttempts += managed.id
                }
                else -> Unit
            }
        }
        if (matching.isNotEmpty()) {
            publish()
            // Connected sessions keep their immutable old profile and merely
            // show "configuration stale". Only unfinished handshakes/trust
            // prompts are retired, so an old endpoint cannot finish after the
            // editor has committed a new one.
            pendingAttempts.forEach { sessionId ->
                cancelTransferForTransportChange(sessionId)
                manager.disconnect(sessionId)
            }
        }
    }

    fun hasSessionsForHost(hostId: Long): Boolean =
        sessions.values.any { it.host.id == hostId }

    fun closeSession(sessionId: String) {
        val removed = sessions.remove(sessionId) ?: return
        removed.operationGeneration.incrementAndGet()
        cancelTransferForTransportChange(sessionId)
        removed.bridge.close()
        manager.removeSession(sessionId)
        resetFileTransferSessions.remove(sessionId)
        transferStateOverrides.remove(sessionId)
        if (activeSessionId == sessionId) activeSessionId = sessions.keys.lastOrNull()
        publish()
    }

    /**
     * Atomically makes all matching logical sessions unreachable, then waits
     * for their transport cleanup. Host deletion calls this before removing
     * credentials and forwarding rules from persistence.
     */
    suspend fun closeSessionsForHost(hostId: Long) {
        val localTransferJobs = mutableListOf<Job>()
        val removed = withContext(Dispatchers.Main.immediate) {
            val matching = sessions.values.filter { it.host.id == hostId }
            if (matching.isEmpty()) return@withContext emptyList<ManagedSession>()
            matching.forEach { managed ->
                sessions.remove(managed.id)
                managed.operationGeneration.incrementAndGet()
                transferRequestJobs.remove(managed.id)?.let { job ->
                    job.cancel()
                    localTransferJobs += job
                }
                transferStateOverrides.remove(managed.id)
                resetFileTransferSessions.remove(managed.id)
                managed.bridge.close()
            }
            if (activeSessionId !in sessions) activeSessionId = sessions.keys.lastOrNull()
            publish()
            matching
        }
        var cleanupFailure: Throwable? = null
        // Once the logical tabs have disappeared, finish physical cleanup even
        // if the deleting Fragment/Activity is concurrently being destroyed.
        // Persistence is touched only after all SFTP jobs and transports have
        // reached a terminal state.
        withContext(NonCancellable) {
            removed.forEach { managed ->
                runCatching { manager.removeSessionAndAwait(managed.id) }
                    .onFailure { if (cleanupFailure == null) cleanupFailure = it }
            }
            localTransferJobs.forEach { it.join() }
        }
        cleanupFailure?.let { throw it }
    }

    fun reconnect(sessionId: String) {
        val managed = sessions[sessionId] ?: return
        cancelTransferForTransportChange(sessionId)
        val operationGeneration = managed.operationGeneration.incrementAndGet()
        val connectionConfigurationGeneration = managed.configurationGeneration.get()
        managed.connectionState = SSHConnectionState.Connecting
        publish()
        viewModelScope.launch {
            try {
                val refreshed = repository.getHostMetadataById(managed.host.id)
                if (sessions[sessionId] !== managed ||
                    managed.operationGeneration.get() != operationGeneration) return@launch
                if (refreshed == null) {
                    managed.connectionState = SSHConnectionState.Error(UserMessage(R.string.host_not_found))
                    publish()
                    manager.disconnect(sessionId)
                    return@launch
                }
                managed.host = refreshed.copy()
                publish()
                manager.reconnect(
                    sessionId,
                    refreshed.copy(),
                    connectionListener(
                        managed,
                        operationGeneration,
                        connectionConfigurationGeneration
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (sessions[sessionId] === managed &&
                    managed.operationGeneration.get() == operationGeneration) {
                    managed.connectionState = SSHConnectionState.Error(
                        UserMessage.fromThrowable(e, R.string.error_load_host)
                    )
                    publish()
                    manager.disconnect(sessionId)
                }
            }
        }
    }

    fun trustAndReconnect(sessionId: String) {
        val managed = sessions[sessionId] ?: return
        cancelTransferForTransportChange(sessionId)
        val operationGeneration = managed.operationGeneration.incrementAndGet()
        val connectionConfigurationGeneration = managed.configurationGeneration.get()
        manager.reattachListener(
            sessionId,
            connectionListener(
                managed,
                operationGeneration,
                connectionConfigurationGeneration
            )
        )
        managed.connectionState = SSHConnectionState.Connecting
        publish()
        manager.trustAndReconnect(sessionId)
    }

    suspend fun listRemoteDirectory(
        sessionId: String,
        path: String = "."
    ): RemoteDirectoryListing {
        require(sessions.containsKey(sessionId)) { throw UserArgumentException(UserMessage(R.string.file_transfer_session_unavailable)) }
        return manager.listRemoteDirectory(sessionId, path)
    }

    suspend fun resolveRemoteEntry(sessionId: String, path: String): com.sshtool.ssh.RemoteFileEntry {
        require(sessions.containsKey(sessionId)) {
            throw UserArgumentException(UserMessage(R.string.file_transfer_session_unavailable))
        }
        return manager.resolveRemoteEntry(sessionId, path)
    }

    /** Start an upload owned by this activity ViewModel, not by the file page. */
    fun uploadFile(
        sessionId: String,
        source: Uri,
        remoteDirectory: String,
        fileName: String,
        overwriteExisting: Boolean = false
    ) {
        if (!canStartTransfer(sessionId)) return
        val managerStateBeforeRequest =
            managerFileTransferStates[sessionId] ?: FileTransferState.Idle
        beginTransferRequest(
            sessionId,
            FileTransferState.Running(
                direction = FileTransferDirection.UPLOAD,
                remotePath = remotePath(remoteDirectory, fileName),
                transferredBytes = 0
            ),
            managerStateBeforeRequest
        )
        val job = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val resolver = repositoryContext().contentResolver
                    val totalBytes = queryContentSize(source)
                    val input = resolver.openInputStream(source)
                        ?: throw UserIOException(UserMessage(R.string.error_open_document))
                    input.use {
                        manager.uploadFile(
                            sessionId = sessionId,
                            input = it,
                            remoteDirectory = remoteDirectory,
                            fileName = fileName,
                            overwriteExisting = overwriteExisting,
                            totalBytes = totalBytes
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recordPreflightTransferFailure(
                    sessionId,
                    FileTransferDirection.UPLOAD,
                    remotePath(remoteDirectory, fileName),
                    managerStateBeforeRequest
                )
                // Provider exceptions can include a sensitive content URI.
                Log.w(LOG_TAG, "Upload request failed")
            }
        }
        trackTransferRequest(sessionId, job)
    }

    /** Start a download and remove its SAF document when it stays incomplete. */
    fun downloadFile(
        sessionId: String,
        remotePath: String,
        destination: Uri,
        totalBytes: Long? = null
    ) {
        if (!canStartTransfer(sessionId)) return
        val managerStateBeforeRequest =
            managerFileTransferStates[sessionId] ?: FileTransferState.Idle
        beginTransferRequest(
            sessionId,
            FileTransferState.Running(
                direction = FileTransferDirection.DOWNLOAD,
                remotePath = remotePath,
                transferredBytes = 0,
                totalBytes = totalBytes
            ),
            managerStateBeforeRequest
        )
        val job = viewModelScope.launch {
            var completed = false
            try {
                withContext(Dispatchers.IO) {
                    val resolver = repositoryContext().contentResolver
                    val output = resolver.openOutputStream(destination, "w")
                        ?: throw UserIOException(UserMessage(R.string.error_create_document))
                    output.use {
                        manager.downloadFile(
                            sessionId = sessionId,
                            remotePath = remotePath,
                            output = it,
                            totalBytes = totalBytes
                        )
                    }
                    completed = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recordPreflightTransferFailure(
                    sessionId,
                    FileTransferDirection.DOWNLOAD,
                    remotePath,
                    managerStateBeforeRequest
                )
                // Provider exceptions can include a sensitive content URI.
                Log.w(LOG_TAG, "Download request failed")
            } finally {
                if (!completed) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        runCatching {
                            repositoryContext().contentResolver.delete(destination, null, null)
                        }.onFailure {
                            Log.w(LOG_TAG, "Unable to delete incomplete download")
                        }
                    }
                }
            }
        }
        trackTransferRequest(sessionId, job)
    }

    fun cancelFileTransfer(sessionId: String) {
        val current = effectiveFileTransferState(sessionId)
        if (current is FileTransferState.Running) {
            val managerState = managerFileTransferStates[sessionId] ?: FileTransferState.Idle
            transferStateOverrides[sessionId] = TransferStateOverride(
                FileTransferState.Cancelled(
                    direction = current.direction,
                    remotePath = current.remotePath,
                    transferredBytes = current.transferredBytes,
                    totalBytes = current.totalBytes
                ),
                managerState
            )
            publish()
        }
        transferRequestJobs[sessionId]?.cancel()
        manager.cancelFileTransfer(sessionId)
    }

    private fun cancelTransferForTransportChange(sessionId: String) {
        // Keep the cancelling job registered until its finally block closes
        // streams and removes an incomplete SAF destination. This prevents a
        // new transfer from starting in that cleanup window.
        transferRequestJobs[sessionId]?.cancel()
        transferStateOverrides.remove(sessionId)
        resetFileTransferSessions += sessionId
        // Also interrupt a blocking JSch SFTP call immediately. A local job
        // can still be in URI/provider setup and absent from the manager, so
        // both cancellation paths are required.
        manager.cancelFileTransfer(sessionId)
    }

    private fun canStartTransfer(sessionId: String): Boolean {
        val managed = sessions[sessionId] ?: return false
        return managed.connectionState is SSHConnectionState.Connected &&
            transferRequestJobs[sessionId]?.isCompleted != false &&
            (sessionId in resetFileTransferSessions ||
                managerFileTransferStates[sessionId] !is FileTransferState.Running)
    }

    private fun beginTransferRequest(
        sessionId: String,
        state: FileTransferState.Running,
        managerStateBeforeRequest: FileTransferState
    ) {
        resetFileTransferSessions.remove(sessionId)
        // URI/provider preparation can take time before the manager emits its
        // first Running state. Publish a local request state immediately so a
        // second tap cannot race into another transfer.
        transferStateOverrides[sessionId] = TransferStateOverride(
            state,
            managerStateBeforeRequest
        )
        publish()
    }

    private fun trackTransferRequest(sessionId: String, job: Job) {
        transferRequestJobs[sessionId] = job
        job.invokeOnCompletion {
            onMain {
                if (transferRequestJobs[sessionId] === job) {
                    transferRequestJobs.remove(sessionId)
                }
            }
        }
    }

    private fun queryContentSize(uri: Uri): Long? {
        val resolver = repositoryContext().contentResolver
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) {
                    cursor.getLong(column).takeIf { it >= 0 }
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private fun recordPreflightTransferFailure(
        sessionId: String,
        direction: FileTransferDirection,
        remotePath: String,
        managerStateBeforeRequest: FileTransferState
    ) {
        onMain {
            if (!sessions.containsKey(sessionId)) return@onMain
            val managerState = managerFileTransferStates[sessionId] ?: FileTransferState.Idle
            // Manager failures already publish a richer state with byte
            // progress. Only synthesize a state when URI/provider setup failed
            // before the request reached the manager.
            if (managerState is FileTransferState.Failed &&
                managerState != managerStateBeforeRequest) return@onMain
            // File-provider messages may include a content URI or local file
            // name. Keep the durable UI state generic just like manager-side
            // SFTP failures.
            transferStateOverrides[sessionId] = TransferStateOverride(
                state = FileTransferState.Failed(
                    direction = direction,
                    remotePath = remotePath,
                    transferredBytes = 0,
                    error = "Unable to prepare file transfer",
                    userMessage = UserMessage(R.string.error_prepare_transfer)
                ),
                managerStateAtCreation = managerState
            )
            publish()
        }
    }

    private fun remotePath(directory: String, fileName: String): String =
        when {
            directory == "/" -> "/$fileName"
            directory.endsWith('/') -> directory + fileName
            else -> "$directory/$fileName"
        }

    private fun publish() {
        keepAliveController.updateSessionCount(
            keepAliveOwnerToken, sessions.values.count { needsSshKeepAlive(it.connectionState) }
        )
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
        connectionState = session.connectionState,
        portForwardStates = portForwardStates[session.id].orEmpty(),
        fileTransferState = effectiveFileTransferState(session.id),
        configurationStale = session.configurationStale
    )

    private fun effectiveFileTransferState(sessionId: String): FileTransferState =
        if (sessionId in resetFileTransferSessions) {
            FileTransferState.Idle
        } else {
            transferStateOverrides[sessionId]?.state
                ?: managerFileTransferStates[sessionId]
                ?: FileTransferState.Idle
        }

    override fun onCleared() {
        keepAliveController.detachOwner(keepAliveOwnerToken)
        sessions.keys.forEach(manager::cancelFileTransfer)
        transferRequestJobs.values.forEach { it.cancel() }
        transferRequestJobs.clear()
        resetFileTransferSessions.clear()
        transferStateOverrides.clear()
        sessions.values.forEach { it.bridge.close() }
        sessions.clear()
        _uiState.value = TerminalUiState()
        manager.shutdown()
        super.onCleared()
    }

    private companion object {
        const val LOG_TAG = "TerminalViewModel"
        const val SEND_REJECTION_EVENT_INTERVAL_NANOS = 2_000_000_000L
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
