package com.sshtool.ssh

import android.util.Log
import com.sshtool.R
import com.sshtool.SSHToolApp
import com.sshtool.data.model.Host
import com.sshtool.data.repository.ConnectionProfile
import com.sshtool.data.repository.HostRepository
import com.sshtool.util.UserArgumentException
import com.sshtool.util.UserMessage
import com.sshtool.util.UserStateException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Thread-safe registry of SSH transports.
 *
 * Session IDs are supplied/returned before any network work begins and remain
 * stable across reconnects. Every transport operation is explicitly addressed
 * by session ID so a background tab can never receive another tab's I/O.
 */
class SSHConnectionManager internal constructor(
    private val connectionFactory: SSHTransportFactory,
    private val scope: CoroutineScope
) {

    constructor() : this(DEFAULT_CONNECTION_FACTORY, newScope())

    private data class SessionInfo(
        val id: String,
        val hostId: Long,
        internal val repository: HostRepository,
        @Volatile var listener: SSHConnectionListener,
        @Volatile var connection: SSHTransport? = null,
        @Volatile var connectionEndpoint: ConnectionEndpoint? = null,
        internal var generation: Long = 0,
        internal var desiredPtyColumns: Int? = null,
        internal var desiredPtyRows: Int? = null,
        internal var generationJob: Job? = null,
        internal var portForwardJob: Job? = null,
        internal var transferJob: Job? = null,
        internal var cleanupJob: Job? = null
    )

    private data class CleanupHandle(
        val job: Job,
        val participants: Set<Job>
    )

    private data class ConnectionEndpoint(
        val hostId: Long,
        val host: String,
        val port: Int
    )

    private data class BuiltTransport(
        val transport: SSHTransport,
        val endpoint: ConnectionEndpoint,
        val portForwardRules: List<com.sshtool.data.model.PortForwardRule>
    )

    private val lock = Any()
    private val sessions = linkedMapOf<String, SessionInfo>()
    // Physical teardown is intentionally independent from the operation scope.
    // Once a session is retired, closing its SFTP/forwarding/SSH resources must
    // still finish even if an Activity-owned operation scope is cancelled.
    private val cleanupScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cleanupJobs = linkedSetOf<Job>()
    private val cleanupParticipants = linkedMapOf<Job, Set<Job>>()
    private var shuttingDown = false
    private val _portForwardStates = MutableStateFlow<Map<String, List<PortForwardRuntimeState>>>(
        emptyMap()
    )
    val portForwardStates: StateFlow<Map<String, List<PortForwardRuntimeState>>> =
        _portForwardStates.asStateFlow()

    private val _fileTransferStates = MutableStateFlow<Map<String, FileTransferState>>(emptyMap())
    val fileTransferStates: StateFlow<Map<String, FileTransferState>> =
        _fileTransferStates.asStateFlow()

    /** Register synchronously, then build/connect the transport on IO. */
    fun connect(
        sessionId: String,
        host: Host,
        repository: HostRepository,
        listener: SSHConnectionListener
    ) {
        // Store only the stable Room id. Every generation resolves a fresh,
        // atomic ConnectionProfile; long-lived manager state holds no secret.
        val info = SessionInfo(sessionId, host.id, repository, listener)
        synchronized(lock) {
            check(!shuttingDown) { "SSH manager is shutting down" }
            check(!sessions.containsKey(sessionId)) { "Duplicate SSH session id: $sessionId" }
            sessions[sessionId] = info
        }
        listener.onStateChanged(SSHConnectionState.Connecting)
        startGeneration(info, generation = 0)
    }

    private fun startGeneration(info: SessionInfo, generation: Long): Job {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val built = try {
                buildConnection(info, generation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val currentListener = synchronized(lock) {
                    sessions[info.id]
                        ?.takeIf { it === info && it.generation == generation }
                        ?.listener
                }
                currentListener?.onStateChanged(
                    SSHConnectionState.Error(UserMessage.fromThrowable(e, R.string.error_read_credentials))
                )
                return@launch
            }
            val connection = built.transport
            val installed = synchronized(lock) {
                val current = sessions[info.id]
                if (current === info && current.generation == generation && current.connection == null) {
                    current.connection = connection
                    current.connectionEndpoint = built.endpoint
                    // A TerminalView resize can arrive while Room/Keystore or
                    // the network is still slow. Seed every new transport with
                    // the latest logical-session geometry before connect(), so
                    // its initial pty-req never falls back to 80x24.
                    val columns = current.desiredPtyColumns
                    val rows = current.desiredPtyRows
                    if (columns != null && rows != null) {
                        connection.updatePtySize(columns, rows)
                    }
                    replacePortForwardStatesLocked(
                        info.id,
                        built.portForwardRules.map {
                            PortForwardRuntimeState(it, PortForwardStatus.PENDING)
                        }
                    )
                    true
                } else {
                    false
                }
            }
            if (!installed) {
                connection.listener = null
                connection.destroy()
                return@launch
            }
            try {
                connection.connect()
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                clearPortForwardStatesIfCurrent(info, generation, connection)
                currentListener(info, generation, connection)
                    ?.onStateChanged(SSHConnectionState.Error(UserMessage(R.string.error_connection)))
                return@launch
            }
            if (!connection.isConnected) {
                // Host-key confirmation intentionally keeps this transport so
                // trustAndReconnect() can act on it, but no mapping has begun.
                clearPortForwardStatesIfCurrent(info, generation, connection)
                return@launch
            }

            // Connected has already been reported by the transport, so a slow
            // or rejected remote-forward request never delays terminal use.
            if (built.portForwardRules.isNotEmpty()) {
                val forwardJob = scope.launch(start = CoroutineStart.LAZY) {
                    connection.startPortForwards(built.portForwardRules) { state ->
                        updatePortForwardStateIfCurrent(
                            info = info,
                            generation = generation,
                            connection = connection,
                            state = state
                        )
                    }
                }
                val retained = synchronized(lock) {
                    val current = sessions[info.id]
                    if (current === info && current.generation == generation &&
                        current.connection === connection && connection.isConnected) {
                        current.portForwardJob = forwardJob
                        true
                    } else {
                        false
                    }
                }
                if (retained) forwardJob.start() else forwardJob.cancel()
            }

            // Strict host-key checks only stage legacy migration/repair. Make
            // persistence linearizable with endpoint edits and deletion, and
            // never let a retired transport resurrect a cleaned-up pin.
            runCatching {
                info.repository.trustHostKeyIfCurrent(
                    built.endpoint.hostId,
                    built.endpoint.host,
                    built.endpoint.port
                ) {
                    synchronized(lock) {
                        val current = sessions[info.id]
                        if (current !== info || current.generation != generation ||
                            current.connection !== connection || !connection.isConnected) {
                            false
                        } else {
                            connection.persistPendingHostKey()
                            true
                        }
                    }
                }
            }.onFailure {
                // Repository/transport failures may embed an SSH endpoint.
                Log.w(LOG_TAG, "Unable to persist accepted host key")
            }
        }
        val installed = synchronized(lock) {
            val current = sessions[info.id]
            if (current === info && current.generation == generation) {
                current.generationJob = job
                true
            } else {
                false
            }
        }
        if (installed) job.start() else job.cancel()
        return job
    }

    private suspend fun buildConnection(info: SessionInfo, generation: Long): BuiltTransport {
        // Resolve metadata and credentials under the repository's mutation
        // mutex. They live in two stores and must belong to the same revision.
        val profile = info.repository.getConnectionProfile(info.hostId)
            ?: throw UserArgumentException(UserMessage(R.string.host_not_found))
        val host = profile.host
        val credentials = profile.credentials
        val password = credentials.password.takeUnless { host.useKeyAuth }
        val privateKey = credentials.privateKey.takeIf { host.useKeyAuth }
        val passphrase = credentials.passphrase.takeIf { host.useKeyAuth }

        lateinit var connection: SSHTransport
        val relay = object : SSHConnectionListener {
            /**
             * Validate this transport generation and capture its target under
             * one lock. If reconnect/disconnect wins immediately afterwards,
             * the old listener still carries the old VM operation token and
             * will reject the now-stale queued event on the main thread.
             */
            private fun currentListener(): SSHConnectionListener? = synchronized(lock) {
                sessions[info.id]?.takeIf {
                    it === info && it.generation == generation && it.connection === connection
                }?.listener
            }

            override fun onStateChanged(state: SSHConnectionState) {
                val target = currentListener() ?: return
                if (state is SSHConnectionState.Connected) {
                    scope.launch {
                        runCatching { info.repository.updateLastConnected(host.id) }
                            .onFailure { Log.w(LOG_TAG, "Unable to update last-connected time") }
                    }
                }
                target.onStateChanged(state)
            }

            override suspend fun onOutput(data: ByteArray) {
                currentListener()?.onOutput(data)
            }

            override fun onDisconnected() {
                retireDisconnectedTransport(info, generation, connection)?.onDisconnected()
            }
        }

        connection = connectionFactory.create(
            ConnectionProfile(
                host = host,
                credentials = credentials.copy(
                    password = password,
                    privateKey = privateKey,
                    passphrase = passphrase
                ),
                portForwardRules = profile.portForwardRules
            )
        ).apply { listener = relay }
        return BuiltTransport(
            transport = connection,
            endpoint = ConnectionEndpoint(host.id, host.host, host.port),
            portForwardRules = profile.portForwardRules
        )
    }

    /** Replace only the transport; the logical session ID and terminal remain stable. */
    fun reconnect(
        sessionId: String,
        updatedHost: Host? = null,
        listener: SSHConnectionListener? = null
    ) {
        val info: SessionInfo
        val oldConnection: SSHTransport?
        val generation: Long
        val stateListener: SSHConnectionListener
        val oldGenerationJob: Job?
        val oldForwardJob: Job?
        val oldTransferJob: Job?
        val cleanupHandle: CleanupHandle
        synchronized(lock) {
            info = sessions[sessionId] ?: return
            require(updatedHost == null || updatedHost.id == info.hostId) {
                "Cannot change the host id of an existing SSH session"
            }
            if (listener != null) info.listener = listener
            info.generation += 1
            generation = info.generation
            oldConnection = info.connection
            oldGenerationJob = info.generationJob
            oldForwardJob = info.portForwardJob
            oldTransferJob = info.transferJob
            info.connection = null
            info.connectionEndpoint = null
            info.generationJob = null
            info.portForwardJob = null
            info.transferJob = null
            removeRuntimeStatesLocked(sessionId)
            stateListener = info.listener
            cleanupHandle = appendCleanupLocked(
                info = info,
                connection = oldConnection,
                generationJob = oldGenerationJob,
                forwardJob = oldForwardJob,
                transferJob = oldTransferJob
            ) {
                startGeneration(info, generation)
            }
        }
        oldGenerationJob?.cancel(CancellationException("SSH 正在重连"))
        oldForwardJob?.cancel()
        oldTransferJob?.cancel(CancellationException("SSH 正在重连"))
        stateListener.onStateChanged(SSHConnectionState.Connecting)
        cleanupHandle.job.start()
    }

    /** Trust the exact rejected key and reconnect under the same logical ID. */
    fun trustAndReconnect(sessionId: String) {
        val info: SessionInfo
        val connection: SSHTransport
        val endpoint: ConnectionEndpoint
        synchronized(lock) {
            info = sessions[sessionId] ?: return
            connection = info.connection ?: return
            endpoint = info.connectionEndpoint ?: return
        }
        scope.launch {
            val trusted = runCatching {
                info.repository.trustHostKeyIfCurrent(
                    endpoint.hostId,
                    endpoint.host,
                    endpoint.port
                ) {
                    // Do not let disconnect/reconnect invalidate this transport
                    // between the final identity check and the trust write.
                    synchronized(lock) {
                        if (sessions[sessionId]?.connection !== connection) false
                        else connection.trustCurrentHostKey()
                    }
                }
            }.getOrDefault(false)
            val currentListener = synchronized(lock) {
                sessions[sessionId]?.takeIf { it.connection === connection }?.listener
            } ?: return@launch
            if (trusted) {
                reconnect(sessionId)
            } else {
                currentListener.onStateChanged(SSHConnectionState.Error(UserMessage(R.string.error_save_fingerprint)))
            }
        }
    }

    fun send(sessionId: String, data: ByteArray, offset: Int = 0, count: Int = data.size - offset): Boolean =
        synchronized(lock) { sessions[sessionId]?.connection }?.send(data, offset, count) == true

    fun send(sessionId: String, data: String): Boolean =
        send(sessionId, data.toByteArray(Charsets.UTF_8))

    fun updatePtySizeForSession(sessionId: String, columns: Int, rows: Int) {
        if (columns <= 0 || rows <= 0) return
        val connection = synchronized(lock) {
            sessions[sessionId]?.let { info ->
                if (info.desiredPtyColumns == columns && info.desiredPtyRows == rows) {
                    return
                }
                info.desiredPtyColumns = columns
                info.desiredPtyRows = rows
                info.connection
            }
        }
        connection?.updatePtySize(columns, rows)
    }

    suspend fun listRemoteDirectory(
        sessionId: String,
        path: String = "."
    ): RemoteDirectoryListing {
        val operation = currentOperation(sessionId)
        val result = operation.connection.listRemoteDirectory(path)
        requireCurrentOperation(sessionId, operation)
        return result
    }

    suspend fun resolveRemoteEntry(sessionId: String, path: String): RemoteFileEntry {
        val operation = currentOperation(sessionId)
        val result = operation.connection.resolveRemoteEntry(path)
        requireCurrentOperation(sessionId, operation)
        return result
    }

    /** Compatibility convenience for callers that do not need the canonical directory path. */
    suspend fun listRemoteFiles(sessionId: String, path: String = "."): List<RemoteFileEntry> =
        listRemoteDirectory(sessionId, path).entries

    suspend fun uploadFile(
        sessionId: String,
        input: InputStream,
        remoteDirectory: String,
        fileName: String,
        overwriteExisting: Boolean = false,
        totalBytes: Long? = null
    ) {
        require(fileName.isNotBlank()) { throw UserArgumentException(UserMessage(R.string.error_file_name_required)) }
        val remotePath = joinRemotePath(remoteDirectory, fileName)
        runTransfer(
            sessionId = sessionId,
            direction = FileTransferDirection.UPLOAD,
            remotePath = remotePath,
            totalBytes = totalBytes
        ) { connection, onProgress ->
            connection.uploadFile(
                input,
                remoteDirectory,
                fileName,
                overwriteExisting,
                totalBytes,
                onProgress
            )
        }
    }

    suspend fun downloadFile(
        sessionId: String,
        remotePath: String,
        output: OutputStream,
        totalBytes: Long? = null
    ) {
        runTransfer(
            sessionId = sessionId,
            direction = FileTransferDirection.DOWNLOAD,
            remotePath = remotePath,
            totalBytes = totalBytes
        ) { connection, onProgress ->
            connection.downloadFile(remotePath, output, totalBytes, onProgress)
        }
    }

    fun cancelFileTransfer(sessionId: String) {
        val operation = synchronized(lock) {
            sessions[sessionId]?.let { it.connection to it.transferJob }
        } ?: return
        // Cancellation must be visible before ChannelSftp.disconnect() makes
        // a blocking JSch call throw an ordinary SftpException.
        operation.second?.cancel(CancellationException("用户取消文件传输"))
        operation.first?.cancelFileTransfer()
    }

    /** Changes the activity-owned observer without replacing the transport relay. */
    fun reattachListener(sessionId: String, listener: SSHConnectionListener) {
        synchronized(lock) { sessions[sessionId]?.listener = listener }
    }

    /** Disconnect transport but retain the logical session for later reconnect. */
    fun disconnect(sessionId: String) {
        val info: SessionInfo
        val oldConnection: SSHTransport?
        val stateListener: SSHConnectionListener
        val oldGenerationJob: Job?
        val oldForwardJob: Job?
        val oldTransferJob: Job?
        val cleanupHandle: CleanupHandle
        synchronized(lock) {
            info = sessions[sessionId] ?: return
            info.generation += 1
            oldConnection = info.connection
            oldGenerationJob = info.generationJob
            oldForwardJob = info.portForwardJob
            oldTransferJob = info.transferJob
            info.connection = null
            info.connectionEndpoint = null
            info.generationJob = null
            info.portForwardJob = null
            info.transferJob = null
            removeRuntimeStatesLocked(sessionId)
            stateListener = info.listener
            cleanupHandle = appendCleanupLocked(
                info = info,
                connection = oldConnection,
                generationJob = oldGenerationJob,
                forwardJob = oldForwardJob,
                transferJob = oldTransferJob
            )
        }
        oldGenerationJob?.cancel(CancellationException("SSH 连接已断开"))
        oldForwardJob?.cancel()
        oldTransferJob?.cancel(CancellationException("SSH 连接已断开"))
        stateListener.onStateChanged(SSHConnectionState.Disconnected)
        stateListener.onDisconnected()
        cleanupHandle.job.start()
    }

    fun removeSession(sessionId: String) {
        val removed: SessionInfo
        val cleanupHandle: CleanupHandle
        synchronized(lock) {
            removed = sessions.remove(sessionId) ?: return
            removeRuntimeStatesLocked(sessionId)
            cleanupHandle = appendCleanupLocked(
                info = removed,
                connection = removed.connection,
                generationJob = removed.generationJob,
                forwardJob = removed.portForwardJob,
                transferJob = removed.transferJob
            )
        }
        removed.generationJob?.cancel(CancellationException("SSH 会话已关闭"))
        removed.portForwardJob?.cancel()
        removed.transferJob?.cancel(CancellationException("SSH 会话已关闭"))
        cleanupHandle.job.start()
    }

    /** Retire routing first, then wait until mappings, SFTP channels and SSH are closed. */
    suspend fun removeSessionAndAwait(sessionId: String) {
        val callerJob = currentCoroutineContext()[Job]
        val removed: SessionInfo
        val cleanupHandle: CleanupHandle
        synchronized(lock) {
            val current = sessions[sessionId] ?: return
            val previousParticipants = current.cleanupJob
                ?.let { cleanupParticipants[it] }
                .orEmpty()
            val currentParticipants = setOfNotNull(
                current.generationJob,
                current.portForwardJob,
                current.transferJob
            )
            check(callerJob == null || callerJob !in previousParticipants + currentParticipants) {
                "Cannot synchronously remove an SSH session from one of its own operation jobs"
            }
            removed = sessions.remove(sessionId) ?: return
            removeRuntimeStatesLocked(sessionId)
            cleanupHandle = appendCleanupLocked(
                info = removed,
                connection = removed.connection,
                generationJob = removed.generationJob,
                forwardJob = removed.portForwardJob,
                transferJob = removed.transferJob
            )
        }
        removed.generationJob?.cancel(CancellationException("SSH 会话已关闭"))
        removed.portForwardJob?.cancel(CancellationException("SSH 会话已关闭"))
        removed.transferJob?.cancel(CancellationException("SSH 会话已关闭"))
        cleanupHandle.job.start()
        withContext(NonCancellable + Dispatchers.IO) {
            cleanupHandle.job.join()
        }
    }

    fun disconnectAll() {
        val cleanups = mutableListOf<CleanupHandle>()
        val removed = synchronized(lock) {
            sessions.values.toList().also {
                it.forEach { info ->
                    cleanups += appendCleanupLocked(
                        info = info,
                        connection = info.connection,
                        generationJob = info.generationJob,
                        forwardJob = info.portForwardJob,
                        transferJob = info.transferJob
                    )
                }
                sessions.clear()
                clearRuntimeStatesLocked()
            }
        }
        removed.forEach {
            it.generationJob?.cancel(CancellationException("SSH 会话已关闭"))
            it.portForwardJob?.cancel()
            it.transferJob?.cancel(CancellationException("SSH 会话已关闭"))
        }
        cleanups.forEach { it.job.start() }
    }

    /** Activity ViewModel teardown: close sockets on IO, then retire the scope. */
    fun shutdown() {
        val cleanups = mutableListOf<CleanupHandle>()
        val cleanupSnapshot: List<Job>
        val removed = synchronized(lock) {
            if (shuttingDown) return
            sessions.values.toList().also {
                it.forEach { info ->
                    cleanups += appendCleanupLocked(
                        info = info,
                        connection = info.connection,
                        generationJob = info.generationJob,
                        forwardJob = info.portForwardJob,
                        transferJob = info.transferJob
                    )
                }
                sessions.clear()
                clearRuntimeStatesLocked()
                // No new session or cleanup registration may race the snapshot.
                shuttingDown = true
                cleanupSnapshot = cleanupJobs.toList()
            }
        }
        removed.forEach {
            it.generationJob?.cancel(CancellationException("SSH 会话已关闭"))
            it.portForwardJob?.cancel()
            it.transferJob?.cancel(CancellationException("SSH 会话已关闭"))
        }
        cleanups.forEach { it.job.start() }
        cleanupScope.launch {
            cleanupSnapshot.forEach { it.join() }
            scope.cancel()
            cleanupScope.cancel()
        }
    }

    /**
     * Retire one physical transport without blocking the caller. Cancelling a
     * blocking SFTP operation requires disconnecting its channel, while an
     * upload already inside its rename/compensation window must be allowed to
     * finish before the SSH session is destroyed. Port-forward jobs are joined
     * only after destroy because closing the Session may be what unblocks JSch.
     */
    /** Caller must hold [lock]. The returned lazy tail must be started after unlocking. */
    private fun appendCleanupLocked(
        info: SessionInfo,
        connection: SSHTransport?,
        generationJob: Job?,
        forwardJob: Job?,
        transferJob: Job?,
        afterCleanup: (() -> Unit)? = null
    ): CleanupHandle {
        check(!shuttingDown) { "SSH manager is shutting down" }
        val previous = info.cleanupJob
        val job = cleanupScope.launch(start = CoroutineStart.LAZY) {
            previous?.takeUnless { it === currentCoroutineContext()[Job] }?.join()
            val cleanupSucceeded = try {
                cleanupTransportNow(connection, generationJob, forwardJob, transferJob)
                true
            } catch (_: Exception) {
                // Teardown failures can embed endpoint details and must not
                // break the per-session cleanup chain.
                Log.w(LOG_TAG, "SSH transport cleanup failed")
                false
            }
            if (cleanupSucceeded) {
                afterCleanup?.invoke()
            } else if (afterCleanup != null) {
                val listener = synchronized(lock) {
                    sessions[info.id]?.takeIf { it === info }
                        ?.listener
                }
                listener?.onStateChanged(SSHConnectionState.Error(UserMessage(R.string.error_connection_cleanup)))
            }
        }
        info.cleanupJob = job
        // Carry the full transitive participant set forward. A later cleanup
        // tail first joins [previous], so awaiting that tail from any older
        // generation/forward/transfer job would otherwise self-deadlock.
        val participants = cleanupParticipants[previous].orEmpty() +
            setOfNotNull(generationJob, forwardJob, transferJob)
        cleanupJobs += job
        cleanupParticipants[job] = participants
        job.invokeOnCompletion {
            synchronized(lock) {
                cleanupJobs.remove(job)
                cleanupParticipants.remove(job)
            }
        }
        return CleanupHandle(job, participants)
    }

    private suspend fun cleanupTransportNow(
        connection: SSHTransport?,
        generationJob: Job?,
        forwardJob: Job?,
        transferJob: Job?
    ) {
        var cleanupFailure: Throwable? = null
        runCatching { connection?.listener = null }
            .onFailure { cleanupFailure = it }
        runCatching { connection?.cancelFileTransfer() }
            .onFailure { if (cleanupFailure == null) cleanupFailure = it }
        transferJob?.join()
        runCatching { connection?.destroy() }
            .onFailure { if (cleanupFailure == null) cleanupFailure = it }
        generationJob?.join()
        forwardJob?.join()
        cleanupFailure?.let { throw it }
    }

    private data class CurrentOperation(
        val info: SessionInfo,
        val generation: Long,
        val connection: SSHTransport
    )

    private fun currentOperation(sessionId: String): CurrentOperation = synchronized(lock) {
        val info = sessions[sessionId] ?: throw UserStateException(UserMessage(R.string.error_session_missing))
        val connection = info.connection?.takeIf { it.isConnected }
            ?: throw UserStateException(UserMessage(R.string.error_connection_not_ready))
        CurrentOperation(info, info.generation, connection)
    }

    private fun requireCurrentOperation(sessionId: String, operation: CurrentOperation) {
        val current = synchronized(lock) {
            sessions[sessionId]?.let {
                it === operation.info && it.generation == operation.generation &&
                    it.connection === operation.connection && operation.connection.isConnected
            } == true
        }
        if (!current) throw CancellationException("SSH 会话已发生变化")
    }

    private suspend fun runTransfer(
        sessionId: String,
        direction: FileTransferDirection,
        remotePath: String,
        totalBytes: Long?,
        operationBlock: suspend (SSHTransport, (Long) -> Unit) -> Unit
    ) {
        val operation = currentOperation(sessionId)
        val job = currentCoroutineContext()[Job]
            ?: throw UserStateException(UserMessage(R.string.error_transfer_context))
        val registered = synchronized(lock) {
            val current = sessions[sessionId]
            if (current === operation.info && current.generation == operation.generation &&
                current.connection === operation.connection && operation.connection.isConnected &&
                current.transferJob == null) {
                current.transferJob = job
                true
            } else {
                false
            }
        }
        check(registered) { throw UserStateException(UserMessage(R.string.file_transfer_in_progress)) }

        var transferredBytes = 0L
        setFileTransferStateIfCurrent(
            sessionId,
            operation,
            FileTransferState.Running(direction, remotePath, 0, totalBytes)
        )
        try {
            operationBlock(operation.connection) { bytes ->
                transferredBytes = maxOf(transferredBytes, bytes)
                setFileTransferStateIfCurrent(
                    sessionId,
                    operation,
                    FileTransferState.Running(
                        direction,
                        remotePath,
                        transferredBytes,
                        totalBytes
                    )
                )
            }
            requireCurrentOperation(sessionId, operation)
            setFileTransferStateIfCurrent(
                sessionId,
                operation,
                FileTransferState.Completed(
                    direction,
                    remotePath,
                    transferredBytes,
                    totalBytes
                )
            )
        } catch (failure: CancellationException) {
            setFileTransferStateIfCurrent(
                sessionId,
                operation,
                FileTransferState.Cancelled(
                    direction,
                    remotePath,
                    transferredBytes,
                    totalBytes
                )
            )
            throw failure
        } catch (failure: Exception) {
            // Disconnecting a ChannelSftp is how cancel interrupts blocking
            // JSch I/O, so it may surface as SftpException rather than the
            // coroutine's CancellationException. Classify from Job state.
            if (!currentCoroutineContext().isActive ||
                !isCurrentOperation(sessionId, operation)) {
                setFileTransferStateIfCurrent(
                    sessionId,
                    operation,
                    FileTransferState.Cancelled(
                        direction,
                        remotePath,
                        transferredBytes,
                        totalBytes
                    )
                )
                throw CancellationException("文件传输已取消").also {
                    it.initCause(failure)
                }
            } else {
                setFileTransferStateIfCurrent(
                    sessionId,
                    operation,
                    FileTransferState.Failed(
                        direction,
                        remotePath,
                        transferredBytes,
                        totalBytes,
                        "File transfer failed",
                        userMessage = UserMessage(R.string.file_transfer_failed)
                    )
                )
                throw failure
            }
        } finally {
            synchronized(lock) {
                sessions[sessionId]?.takeIf { it === operation.info && it.transferJob === job }
                    ?.transferJob = null
            }
        }
    }

    private fun updatePortForwardStateIfCurrent(
        info: SessionInfo,
        generation: Long,
        connection: SSHTransport,
        state: PortForwardRuntimeState
    ) {
        synchronized(lock) {
            val current = sessions[info.id]?.let {
                it === info && it.generation == generation && it.connection === connection &&
                    connection.isConnected
            } == true
            if (!current) return
            _portForwardStates.value = _portForwardStates.value.toMutableMap().apply {
                val existing = get(info.id).orEmpty().toMutableList()
                val index = existing.indexOfFirst { sameRule(it.rule, state.rule) }
                if (index >= 0) existing[index] = state else existing += state
                put(info.id, existing.toList())
            }.toMap()
        }
    }

    private fun setFileTransferStateIfCurrent(
        sessionId: String,
        operation: CurrentOperation,
        state: FileTransferState
    ) {
        synchronized(lock) {
            val current = sessions[sessionId]?.let {
                it === operation.info && it.generation == operation.generation &&
                    it.connection === operation.connection
            } == true
            if (!current) return
            _fileTransferStates.value = _fileTransferStates.value.toMutableMap().apply {
                put(sessionId, state)
            }.toMap()
        }
    }

    private fun removeRuntimeStatesLocked(sessionId: String) {
        replacePortForwardStatesLocked(sessionId, emptyList())
        _fileTransferStates.value = _fileTransferStates.value.toMutableMap().apply {
            remove(sessionId)
        }.toMap()
    }

    private fun clearRuntimeStatesLocked() {
        _portForwardStates.value = emptyMap()
        _fileTransferStates.value = emptyMap()
    }

    private fun replacePortForwardStatesLocked(
        sessionId: String,
        states: List<PortForwardRuntimeState>
    ) {
        _portForwardStates.value = _portForwardStates.value.toMutableMap().apply {
            if (states.isEmpty()) remove(sessionId) else put(sessionId, states.toList())
        }.toMap()
    }

    private fun clearPortForwardStatesIfCurrent(
        info: SessionInfo,
        generation: Long,
        connection: SSHTransport
    ) {
        synchronized(lock) {
            val current = sessions[info.id]
            if (current === info && current.generation == generation &&
                current.connection === connection) {
                replacePortForwardStatesLocked(info.id, emptyList())
            }
        }
    }

    private fun currentListener(
        info: SessionInfo,
        generation: Long,
        connection: SSHTransport
    ): SSHConnectionListener? = synchronized(lock) {
        sessions[info.id]?.takeIf {
            it === info && it.generation == generation && it.connection === connection
        }?.listener
    }

    private fun isCurrentOperation(sessionId: String, operation: CurrentOperation): Boolean =
        synchronized(lock) {
            sessions[sessionId]?.let {
                it === operation.info && it.generation == operation.generation &&
                    it.connection === operation.connection && operation.connection.isConnected
            } == true
        }

    /** Atomically retire a transport that closed itself (EOF/network loss). */
    private fun retireDisconnectedTransport(
        info: SessionInfo,
        generation: Long,
        connection: SSHTransport
    ): SSHConnectionListener? {
        val listener: SSHConnectionListener
        val generationJob: Job?
        val forwardJob: Job?
        val transferJob: Job?
        val cleanupHandle: CleanupHandle
        synchronized(lock) {
            val current = sessions[info.id]
            if (current !== info || current.generation != generation ||
                current.connection !== connection) return null
            listener = current.listener
            generationJob = current.generationJob
            forwardJob = current.portForwardJob
            transferJob = current.transferJob
            current.generation += 1
            current.connection = null
            current.connectionEndpoint = null
            current.generationJob = null
            current.portForwardJob = null
            current.transferJob = null
            removeRuntimeStatesLocked(info.id)
            cleanupHandle = appendCleanupLocked(
                info = current,
                connection = connection,
                generationJob = generationJob,
                forwardJob = forwardJob,
                transferJob = transferJob
            )
        }
        forwardJob?.cancel(CancellationException("SSH 连接已断开"))
        transferJob?.cancel(CancellationException("SSH 连接已断开"))
        cleanupHandle.job.start()
        return listener
    }

    private fun sameRule(
        left: com.sshtool.data.model.PortForwardRule,
        right: com.sshtool.data.model.PortForwardRule
    ): Boolean = if (left.id != 0L && right.id != 0L) left.id == right.id else left == right

    private fun joinRemotePath(directory: String, name: String): String =
        if (directory == "/") "/$name" else "${directory.trimEnd('/')}/$name"

    private companion object {
        private const val LOG_TAG = "SSHConnectionManager"

        fun newScope() = CoroutineScope(
            Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, _ ->
                Log.e(LOG_TAG, "Managed SSH operation failed")
            }
        )

        val DEFAULT_CONNECTION_FACTORY = SSHTransportFactory { profile ->
            val host = profile.host
            val credentials = profile.credentials
            SSHConnection(
                context = SSHToolApp.instance.applicationContext,
                hostAddress = host.host,
                hostPort = host.port,
                username = host.username,
                useKeyAuth = host.useKeyAuth,
                password = credentials.password,
                privateKey = credentials.privateKey,
                passphrase = credentials.passphrase
            )
        }
    }
}
