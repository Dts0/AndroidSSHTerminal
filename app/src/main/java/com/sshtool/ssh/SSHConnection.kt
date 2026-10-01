package com.sshtool.ssh

import android.content.Context
import android.util.Log
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.JSchPtyResizeCompat
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SftpProgressMonitor
import com.sshtool.R
import com.sshtool.data.model.PortForwardDirection
import com.sshtool.data.model.PortForwardRule
import com.sshtool.util.UserArgumentException
import com.sshtool.util.UserMessage
import com.sshtool.util.UserStateException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class SSHConnectionState {
    data object Connecting : SSHConnectionState()
    data object Connected : SSHConnectionState()
    data class HostKeyConfirmationRequired(
        val host: String,
        val port: Int,
        val algorithm: String,
        val fingerprint: String
    ) : SSHConnectionState()
    data class HostKeyChanged(
        val host: String,
        val port: Int,
        val algorithm: String,
        val fingerprint: String
    ) : SSHConnectionState()
    data class Error(
        val message: String,
        val userMessage: UserMessage? = null
    ) : SSHConnectionState() {
        constructor(message: UserMessage) : this("App error (${message.resourceId})", message)
    }
    data object Disconnected : SSHConnectionState()
}

interface SSHConnectionListener {
    fun onStateChanged(state: SSHConnectionState)
    suspend fun onOutput(data: ByteArray)
    fun onDisconnected()
}

/** A single-use SSH transport. Reconnects replace this object in the manager. */
internal class SSHConnection(
    context: Context,
    private val hostAddress: String,
    private val hostPort: Int,
    private val username: String,
    private val useKeyAuth: Boolean,
    private val password: String?,
    private val privateKey: String?,
    private val passphrase: String?
) : SSHTransport {

    private val resourceLock = Any()
    @Volatile private var session: Session? = null
    @Volatile private var channel: ChannelShell? = null
    @Volatile private var inputStream: InputStream? = null
    @Volatile private var outputStream: OutputStream? = null
    private val activePortForwards = linkedSetOf<ActivePortForward>()
    private val activeSftpChannels = linkedSetOf<ChannelSftp>()
    @Volatile private var activeTransferChannel: ChannelSftp? = null
    @Volatile private var activeTransferCommitInProgress = false
    private val transportCloseStarted = AtomicBoolean(false)

    private var readerJob: Job? = null
    private var writerJob: Job? = null
    private val closed = AtomicBoolean(false)
    private val outbound = SSHOutboundQueue()
    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, _ ->
            Log.e(LOG_TAG, "Unhandled SSH coroutine exception")
        }
    )
    private val ptyResizer = ConflatedPtyResizeDispatcher(
        scope = scope,
        initialColumns = DEFAULT_PTY_COLUMNS,
        initialRows = DEFAULT_PTY_ROWS,
        targetProvider = { channel },
        isTargetConnected = { it.isConnected },
        sendResize = { target, columns, rows ->
            // Send exactly one observable window-change. Calling
            // setPtySize() here would hide failures inside JSch and make an
            // additional manual request a protocol duplicate.
            JSchPtyResizeCompat.resize(target, columns, rows, 0, 0)
        },
        onFailure = {
            if (!closed.get()) Log.w(LOG_TAG, "setPtySize failed")
        }
    )

    private val trustStore = HostKeyTrustStore(context.applicationContext)
    private val knownHostsFile = File(context.filesDir, "known_hosts")

    @Volatile private var hostKeyRepository: StrictHostKeyRepository? = null
    @Volatile override var listener: SSHConnectionListener? = null

    override val isConnected: Boolean
        get() = !closed.get() && session?.isConnected == true && channel?.isConnected == true

    /** Establish both the SSH session and shell channel before reporting Connected. */
    override suspend fun connect() = withContext(Dispatchers.IO) {
        if (closed.get()) return@withContext
        listener?.onStateChanged(SSHConnectionState.Connecting)

        var candidateSession: Session? = null
        var candidateChannel: ChannelShell? = null
        var candidateInput: InputStream? = null
        var candidateOutput: OutputStream? = null
        try {
            val jsch = JSch()
            if (!knownHostsFile.exists()) {
                knownHostsFile.parentFile?.mkdirs()
                knownHostsFile.createNewFile()
            }
            jsch.setKnownHosts(knownHostsFile.absolutePath)

            if (useKeyAuth && privateKey != null) {
                jsch.addIdentity(
                    "temp_key",
                    privateKey.toByteArray(),
                    null,
                    passphrase?.toByteArray()
                )
            }

            val createdSession = jsch.getSession(username, hostAddress, hostPort)
            candidateSession = createdSession
            val sessionInstalled = synchronized(resourceLock) {
                if (closed.get()) false else {
                    session = createdSession
                    true
                }
            }
            if (!sessionInstalled) {
                closeCandidateTransport(candidateInput, candidateOutput, candidateChannel, candidateSession)
                return@withContext
            }

            val verifier = HostKeyVerifier(hostAddress, hostPort, trustStore)
            val strictRepository = StrictHostKeyRepository(
                delegate = jsch.hostKeyRepository,
                verifier = verifier,
                host = hostAddress,
                port = hostPort,
                trustStore = trustStore,
                legacyRepository = trustStore.legacyRepository
            )
            hostKeyRepository = strictRepository
            jsch.hostKeyRepository = strictRepository

            if (!useKeyAuth && password != null) createdSession.setPassword(password)
            createdSession.apply {
                setConfig("StrictHostKeyChecking", "yes")
                setConfig(
                    "PreferredAuthentications",
                    if (useKeyAuth) "publickey" else "keyboard-interactive,password"
                )
                setConfig("HashKnownHosts", "yes")
                serverAliveInterval = 15_000
                serverAliveCountMax = 3
                timeout = 30_000
                connect(30_000)
            }
            if (closed.get()) {
                closeTransport()
                closeCandidateTransport(candidateInput, candidateOutput, candidateChannel, candidateSession)
                return@withContext
            }

            val createdChannel = createdSession.openChannel("shell") as ChannelShell
            candidateChannel = createdChannel
            val channelInstalled = synchronized(resourceLock) {
                if (closed.get()) false else {
                    channel = createdChannel
                    true
                }
            }
            if (!channelInstalled) {
                closeTransport()
                closeCandidateTransport(candidateInput, candidateOutput, candidateChannel, candidateSession)
                return@withContext
            }
            createdChannel.apply {
                setPty(true)
                setEnv("TERM", "xterm-256color")
                setPtyType("xterm-256color")
                val configuredSize = ptyResizer.snapshotForInitialRequest()
                // Before connect(), JSch only updates the dimensions included
                // in the initial pty-req and performs no network write.
                setPtySize(configuredSize.columns, configuredSize.rows, 0, 0)
                connect(30_000)
                // A resize may have arrived while connect() was blocking.
                // Wake the single conflated worker so only the latest version
                // is sent after the initial pty-req has completed.
                ptyResizer.markInitialRequestApplied(configuredSize)
            }
            if (closed.get()) {
                closeTransport()
                closeCandidateTransport(candidateInput, candidateOutput, candidateChannel, candidateSession)
                return@withContext
            }

            val createdInput = createdChannel.inputStream
            candidateInput = createdInput
            val createdOutput = createdChannel.outputStream
            candidateOutput = createdOutput
            val streamsInstalled = synchronized(resourceLock) {
                if (closed.get()) false else {
                    inputStream = createdInput
                    outputStream = createdOutput
                    true
                }
            }
            if (!streamsInstalled) {
                closeTransport()
                closeCandidateTransport(candidateInput, candidateOutput, candidateChannel, candidateSession)
                return@withContext
            }

            startWriter(createdOutput)
            startReader(createdInput)
            if (!closed.get() && createdSession.isConnected && createdChannel.isConnected) {
                listener?.onStateChanged(SSHConnectionState.Connected)
            }
        } catch (e: CancellationException) {
            closeTransport()
            closeCandidateTransport(candidateInput, candidateOutput, candidateChannel, candidateSession)
            throw e
        } catch (e: JSchException) {
            closeTransport()
            closeCandidateTransport(candidateInput, candidateOutput, candidateChannel, candidateSession)
            if (!closed.get()) reportJschFailure(e)
        } catch (e: Exception) {
            closeTransport()
            closeCandidateTransport(candidateInput, candidateOutput, candidateChannel, candidateSession)
            if (!closed.get()) {
                listener?.onStateChanged(SSHConnectionState.Error(UserMessage.fromThrowable(e, R.string.error_unknown)))
            }
        }
    }

    private fun reportJschFailure(error: JSchException) {
        val rejected = hostKeyRepository?.lastRejectedKey
        val state = if (rejected != null) {
            if (rejected.reason == StrictHostKeyRepository.RejectionReason.CHANGED) {
                SSHConnectionState.HostKeyChanged(
                    hostAddress, hostPort, rejected.algorithm, rejected.fingerprint
                )
            } else {
                SSHConnectionState.HostKeyConfirmationRequired(
                    hostAddress, hostPort, rejected.algorithm, rejected.fingerprint
                )
            }
        } else {
            SSHConnectionState.Error(UserMessage.fromThrowable(error, R.string.error_connection))
        }
        listener?.onStateChanged(state)
    }

    override fun trustCurrentHostKey(): Boolean {
        val rejected = hostKeyRepository?.lastRejectedKey ?: return false
        trustStore.trust(hostAddress, hostPort, rejected.algorithm, rejected.encodedKey)
        return true
    }

    override fun persistPendingHostKey() {
        hostKeyRepository?.persistPendingHostKey()
    }

    /** Queue a copy for the connection's single writer coroutine. */
    override fun send(data: ByteArray, offset: Int, count: Int): Boolean {
        if (!isConnected) return false
        return outbound.enqueue(data, offset, count)
    }

    fun send(data: String): Boolean = send(data.toByteArray(Charsets.UTF_8))

    private fun startWriter(stream: OutputStream) {
        writerJob = scope.launch {
            try {
                outbound.drainTo(stream)
            } catch (_: Exception) {
                if (!closed.get()) {
                    Log.w(LOG_TAG, "SSH writer ended")
                    listener?.onStateChanged(SSHConnectionState.Error(UserMessage(R.string.error_send)))
                    disconnectInternal(notifyState = false)
                }
            }
        }
    }

    private fun startReader(stream: InputStream) {
        readerJob = scope.launch {
            val buffer = ByteArray(4096)
            try {
                while (isActive && !closed.get()) {
                    val length = stream.read(buffer)
                    if (length < 0) break
                    if (length > 0) listener?.onOutput(buffer.copyOf(length))
                }
            } catch (_: Exception) {
                if (!closed.get()) Log.i(LOG_TAG, "SSH reader ended")
            } finally {
                if (!closed.get()) disconnect()
            }
        }
    }

    override fun updatePtySize(columns: Int, rows: Int) {
        if (closed.get()) return
        ptyResizer.update(columns, rows)
    }

    /**
     * Start rules in order and isolate every failure. The operation-local
     * Session handle is revalidated after JSch returns: disconnect may race a
     * slow remote-forward request, and a late success must then be undone.
     */
    override suspend fun startPortForwards(
        rules: List<PortForwardRule>,
        onStateChanged: (PortForwardRuntimeState) -> Unit
    ) = withContext(Dispatchers.IO) {
        rules.filter { it.enabled }.forEach { rule ->
            currentCoroutineContext().ensureActive()
            onStateChanged(PortForwardRuntimeState(rule, PortForwardStatus.STARTING))
            val sshSession = synchronized(resourceLock) {
                session?.takeIf { !closed.get() && it.isConnected }
            }
            if (sshSession == null) {
                onStateChanged(
                    PortForwardRuntimeState(
                        rule,
                        PortForwardStatus.FAILED,
                        userMessage = UserMessage(R.string.error_ssh_disconnected)
                    )
                )
                return@forEach
            }

            var installed: ActivePortForward? = null
            try {
                validatePortForward(rule)
                val boundPort = when (rule.direction) {
                    PortForwardDirection.LOCAL -> sshSession.setPortForwardingL(
                        rule.bindAddress,
                        rule.listenPort,
                        rule.targetHost,
                        rule.targetPort
                    )
                    PortForwardDirection.REMOTE -> {
                        sshSession.setPortForwardingR(
                            rule.bindAddress,
                            rule.listenPort,
                            rule.targetHost,
                            rule.targetPort
                        )
                        rule.listenPort
                    }
                }
                installed = ActivePortForward(rule, boundPort)
                val retained = synchronized(resourceLock) {
                    if (!closed.get() && session === sshSession && sshSession.isConnected) {
                        activePortForwards += installed
                        true
                    } else {
                        false
                    }
                }
                if (!retained) {
                    removePortForward(sshSession, installed)
                    throw UserStateException(UserMessage(R.string.error_ssh_disconnected))
                }
                onStateChanged(
                    PortForwardRuntimeState(
                        rule = rule,
                        status = PortForwardStatus.ACTIVE,
                        boundPort = boundPort
                    )
                )
            } catch (failure: CancellationException) {
                if (installed != null) {
                    synchronized(resourceLock) { activePortForwards.remove(installed) }
                    removePortForward(sshSession, installed)
                }
                throw failure
            } catch (failure: Exception) {
                // Do not tear down the shell or other successful rules.
                if (installed != null) {
                    synchronized(resourceLock) { activePortForwards.remove(installed) }
                    removePortForward(sshSession, installed)
                }
                onStateChanged(
                    PortForwardRuntimeState(
                        rule = rule,
                        status = PortForwardStatus.FAILED,
                        userMessage = UserMessage.fromThrowable(failure, R.string.error_forward_start)
                    )
                )
            }
        }
    }

    override suspend fun listRemoteDirectory(path: String): RemoteDirectoryListing =
        withSftpChannel(isTransfer = false) { sftp ->
            val canonicalPath = sftp.realpath(path.ifBlank { "." })
            val entries = ArrayList<RemoteFileEntry>()
            var exceededLimit = false
            sftp.ls(canonicalPath) { entry ->
                val rawName = entry.filename
                if (rawName != "." && rawName != "..") {
                    if (entries.size >= MAX_DIRECTORY_ENTRIES) {
                        exceededLimit = true
                        return@ls ChannelSftp.LsEntrySelector.BREAK
                    }
                    val attrs = entry.attrs
                    entries += RemoteFileEntry(
                        // Preserve the exact protocol name for equality and
                        // overwrite checks. UI must escape control characters
                        // before rendering this untrusted string.
                        name = rawName,
                        path = joinRemotePath(canonicalPath, rawName),
                        isDirectory = attrs.isDir,
                        isSymbolicLink = attrs.isLink,
                        size = attrs.size.coerceAtLeast(0),
                        modifiedTimeMillis = attrs.mTime
                            .takeIf { it > 0 }
                            ?.toLong()
                            ?.times(1_000L)
                    )
                }
                ChannelSftp.LsEntrySelector.CONTINUE
            }
            if (exceededLimit) {
                throw UserStateException(UserMessage(R.string.error_remote_directory_limit, MAX_DIRECTORY_ENTRIES))
            }
            RemoteDirectoryListing(
                path = canonicalPath,
                entries = entries.sortedWith(
                    compareByDescending<RemoteFileEntry> { it.isDirectory }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                )
            )
        }

    override suspend fun uploadFile(
        input: InputStream,
        remoteDirectory: String,
        fileName: String,
        overwriteExisting: Boolean,
        totalBytes: Long?,
        onProgress: (Long) -> Unit
    ): Unit = withSftpChannel(isTransfer = true) { sftp ->
        requireValidRemoteFileName(fileName)
        val canonicalDirectory = sftp.realpath(remoteDirectory.ifBlank { "." })
        val targetPath = joinRemotePath(canonicalDirectory, fileName)
        val temporaryPath = joinRemotePath(
            canonicalDirectory,
            ".sshtool-upload-${UUID.randomUUID()}"
        )
        val context = currentCoroutineContext()
        var completed = false
        var backupPath: String? = null
        var backupContainsOriginal = false
        var commitProtected = false
        try {
            sftp.put(
                input,
                temporaryPath,
                progressMonitor(context, totalBytes, onProgress),
                ChannelSftp.OVERWRITE
            )
            context.ensureActive()
            // Once the complete temporary upload exists, keep the short
            // rename/compensation transaction intact. A cancel still marks
            // the coroutine cancelled, but cancelFileTransfer() must not tear
            // down this channel while the original target is parked as a
            // backup.
            commitProtected = synchronized(resourceLock) {
                if (closed.get() || activeTransferChannel !== sftp ||
                    !sftp.isConnected || !context.isActive) {
                    false
                } else {
                    activeTransferCommitInProgress = true
                    true
                }
            }
            if (!commitProtected) {
                context.ensureActive()
                throw UserStateException(UserMessage(R.string.error_sftp_disconnected))
            }
            // UI performs overwrite confirmation. Preserve the old file until
            // the complete temporary upload is present, and retain a backup
            // while committing so a failed rename can be compensated.
            val existingTarget = remotePathAttributesOrNull(sftp, targetPath)
            if (existingTarget != null) {
                if (!overwriteExisting) {
                    throw UserStateException(UserMessage(R.string.error_remote_exists))
                }
                require(existingTarget.isReg) {
                    throw UserArgumentException(UserMessage(R.string.error_overwrite_regular_only))
                }
                backupPath = joinRemotePath(
                    canonicalDirectory,
                    ".sshtool-backup-${UUID.randomUUID()}"
                )
                sftp.rename(targetPath, backupPath)
                backupContainsOriginal = true
                // lstat again after rename. The target can change between the
                // first check and rename; never replace a concurrently-created
                // directory or symlink with the uploaded file.
                val movedTarget = sftp.lstat(backupPath)
                require(movedTarget.isReg && !movedTarget.isLink) {
                    throw UserArgumentException(UserMessage(R.string.error_overwrite_regular_only))
                }
            }
            sftp.rename(temporaryPath, targetPath)
            completed = true
            backupPath?.let { runCatching { sftp.rm(it) } }
        } finally {
            if (!completed) {
                runCatching { sftp.rm(temporaryPath) }
                backupPath?.takeIf { backupContainsOriginal }?.let { backup ->
                    // Compensation must never replace the primary exception.
                    // Restore only into an empty target slot so a concurrent
                    // writer's new target is not overwritten.
                    val targetIsAbsent = runCatching {
                        remotePathAttributesOrNull(sftp, targetPath) == null
                    }.getOrDefault(false)
                    if (targetIsAbsent) runCatching { sftp.rename(backup, targetPath) }
                }
            }
            val closeAfterCommit = if (commitProtected) {
                synchronized(resourceLock) {
                    if (activeTransferChannel === sftp) {
                        activeTransferCommitInProgress = false
                    }
                    closed.get()
                }
            } else {
                false
            }
            // Explicit teardown marks the logical transport closed immediately,
            // but physical channel shutdown is deferred across the small
            // rename/compensation window. Close now that the remote target is
            // either committed or restored. Never wait while holding
            // resourceLock: the teardown path and this finally share it.
            if (closeAfterCommit) closeTransport()
        }
    }

    override suspend fun downloadFile(
        remotePath: String,
        output: OutputStream,
        totalBytes: Long?,
        onProgress: (Long) -> Unit
    ): Unit = withSftpChannel(isTransfer = true) { sftp ->
        // lstat the caller-selected entry before realpath so a symlink cannot
        // be resolved first and thereby bypass the first-version restriction.
        val attrs = sftp.lstat(remotePath)
        require(!attrs.isDir) { throw UserArgumentException(UserMessage(R.string.error_download_directory)) }
        require(!attrs.isLink) { throw UserArgumentException(UserMessage(R.string.file_transfer_symlink_unsupported)) }
        require(attrs.isReg) { throw UserArgumentException(UserMessage(R.string.error_download_regular_only)) }
        val canonicalPath = sftp.realpath(remotePath)
        val expectedBytes = totalBytes ?: attrs.size.takeIf { it >= 0 }
        val context = currentCoroutineContext()
        sftp.get(
            canonicalPath,
            output,
            progressMonitor(context, expectedBytes, onProgress),
            ChannelSftp.OVERWRITE,
            0L
        )
        context.ensureActive()
        output.flush()
    }

    override fun cancelFileTransfer() {
        // Disconnecting just this SFTP channel interrupts its blocking JSch
        // operation without changing the shell or underlying Session.
        val transfer = synchronized(resourceLock) {
            activeTransferChannel.takeUnless { activeTransferCommitInProgress }
        }
        runCatching { transfer?.disconnect() }
            .onFailure { Log.w(LOG_TAG, "cancel SFTP transfer failed") }
    }

    /** Idempotent teardown. Call from an IO context for explicit disconnects. */
    fun disconnect() = disconnectInternal(notifyState = true)

    private fun disconnectInternal(notifyState: Boolean) {
        if (!closed.compareAndSet(false, true)) return
        outbound.close()
        ptyResizer.close()
        readerJob?.cancel()
        writerJob?.cancel()
        val closeImmediately = synchronized(resourceLock) {
            !activeTransferCommitInProgress
        }
        if (closeImmediately) closeTransport()
        val callback = listener
        listener = null
        if (notifyState) callback?.onStateChanged(SSHConnectionState.Disconnected)
        callback?.onDisconnected()
        scope.cancel()
    }

    override fun destroy() = disconnect()

    private fun closeTransport() {
        if (!transportCloseStarted.compareAndSet(false, true)) return
        val input: InputStream?
        val output: OutputStream?
        val shell: ChannelShell?
        val sshSession: Session?
        val portForwards: List<ActivePortForward>
        val sftpChannels: List<ChannelSftp>
        synchronized(resourceLock) {
            input = inputStream
            output = outputStream
            shell = channel
            sshSession = session
            portForwards = activePortForwards.toList()
            sftpChannels = activeSftpChannels.toList()
            activePortForwards.clear()
            activeSftpChannels.clear()
            activeTransferChannel = null
            activeTransferCommitInProgress = false
            inputStream = null
            outputStream = null
            channel = null
            session = null
        }
        runCatching { input?.close() }.onFailure { Log.w(LOG_TAG, "close input failed") }
        runCatching { output?.close() }.onFailure { Log.w(LOG_TAG, "close output failed") }
        if (sshSession != null) {
            portForwards.asReversed().forEach { removePortForward(sshSession, it) }
        }
        sftpChannels.forEach { sftp ->
            runCatching { sftp.disconnect() }.onFailure { Log.w(LOG_TAG, "close SFTP failed") }
        }
        runCatching { shell?.disconnect() }.onFailure { Log.w(LOG_TAG, "close channel failed") }
        runCatching { sshSession?.disconnect() }.onFailure { Log.w(LOG_TAG, "close session failed") }
    }

    private suspend fun <T> withSftpChannel(
        isTransfer: Boolean,
        block: suspend (ChannelSftp) -> T
    ): T = withContext(Dispatchers.IO) {
        val operationContext = currentCoroutineContext()
        operationContext.ensureActive()
        val sshSession = synchronized(resourceLock) {
            session?.takeIf { !closed.get() && it.isConnected }
        } ?: throw UserStateException(UserMessage(R.string.error_connection_not_ready))

        val sftp = sshSession.openChannel("sftp") as ChannelSftp
        val registered = synchronized(resourceLock) {
            if (!operationContext.isActive || closed.get() ||
                session !== sshSession || !sshSession.isConnected ||
                (isTransfer && activeTransferChannel != null)) {
                false
            } else {
                activeSftpChannels += sftp
                if (isTransfer) activeTransferChannel = sftp
                true
            }
        }
        if (!registered) {
            runCatching { sftp.disconnect() }
            if (isTransfer && synchronized(resourceLock) { activeTransferChannel != null }) {
                throw UserStateException(UserMessage(R.string.file_transfer_in_progress))
            }
            throw UserStateException(UserMessage(R.string.error_ssh_disconnected))
        }

        try {
            operationContext.ensureActive()
            sftp.connect(SFTP_CONNECT_TIMEOUT_MILLIS)
            operationContext.ensureActive()
            val current = synchronized(resourceLock) {
                !closed.get() && session === sshSession && sftp in activeSftpChannels
            }
            if (!current) throw CancellationException("SSH 连接已断开")
            block(sftp)
        } finally {
            synchronized(resourceLock) {
                activeSftpChannels.remove(sftp)
                if (activeTransferChannel === sftp) activeTransferChannel = null
            }
            runCatching { sftp.disconnect() }
                .onFailure { Log.w(LOG_TAG, "close operation SFTP failed") }
        }
    }

    private fun progressMonitor(
        coroutineContext: kotlin.coroutines.CoroutineContext,
        totalBytes: Long?,
        onProgress: (Long) -> Unit
    ): SftpProgressMonitor = object : SftpProgressMonitor {
        private var transferred = 0L

        override fun init(operation: Int, source: String?, destination: String?, max: Long) = Unit

        override fun count(count: Long): Boolean {
            if (!coroutineContext.isActive || closed.get()) return false
            transferred += count.coerceAtLeast(0)
            onProgress(transferred)
            return true
        }

        override fun end() {
            if (totalBytes != null && transferred < totalBytes && coroutineContext.isActive) {
                // Some servers/JSch paths do not deliver a final exact count;
                // keep progress monotonic without fabricating bytes beyond the
                // caller's known size.
                onProgress(transferred)
            }
        }
    }

    private fun validatePortForward(rule: PortForwardRule) {
        require(rule.bindAddress.isNotBlank()) { throw UserArgumentException(UserMessage(R.string.port_forward_bind_required)) }
        require(rule.targetHost.isNotBlank()) { throw UserArgumentException(UserMessage(R.string.port_forward_target_required)) }
        require(rule.listenPort in 1..65535) { throw UserArgumentException(UserMessage(R.string.port_forward_invalid_listen_port)) }
        require(rule.targetPort in 1..65535) { throw UserArgumentException(UserMessage(R.string.port_forward_invalid_target_port)) }
    }

    private fun removePortForward(sshSession: Session, active: ActivePortForward) {
        runCatching {
            when (active.rule.direction) {
                PortForwardDirection.LOCAL -> sshSession.delPortForwardingL(
                    active.rule.bindAddress,
                    active.boundPort
                )
                PortForwardDirection.REMOTE -> sshSession.delPortForwardingR(
                    active.rule.bindAddress,
                    active.boundPort
                )
            }
        }.onFailure { Log.w(LOG_TAG, "remove port forward failed") }
    }

    private fun requireValidRemoteFileName(fileName: String) {
        require(fileName.isNotBlank() && fileName != "." && fileName != "..") {
            throw UserArgumentException(UserMessage(R.string.error_remote_filename))
        }
        require('/' !in fileName && '\u0000' !in fileName) { throw UserArgumentException(UserMessage(R.string.error_remote_filename_separator)) }
    }

    private fun joinRemotePath(directory: String, name: String): String =
        if (directory == "/") "/$name" else "${directory.trimEnd('/')}/$name"

    private fun remotePathAttributesOrNull(
        sftp: ChannelSftp,
        path: String
    ): com.jcraft.jsch.SftpATTRS? = try {
        sftp.lstat(path)
    } catch (failure: SftpException) {
        if (failure.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) null else throw failure
    }

    private data class ActivePortForward(
        val rule: PortForwardRule,
        val boundPort: Int
    )

    /**
     * A concurrent disconnect can clear the shared fields while a blocking
     * JSch connect call is still returning. Close the operation-local handles
     * as well so a late successful connect cannot become an orphaned socket.
     */
    private fun closeCandidateTransport(
        input: InputStream?,
        output: OutputStream?,
        shell: ChannelShell?,
        sshSession: Session?
    ) {
        runCatching { input?.close() }.onFailure { Log.w(LOG_TAG, "close candidate input failed") }
        runCatching { output?.close() }.onFailure { Log.w(LOG_TAG, "close candidate output failed") }
        runCatching { shell?.disconnect() }.onFailure { Log.w(LOG_TAG, "close candidate channel failed") }
        runCatching { sshSession?.disconnect() }.onFailure { Log.w(LOG_TAG, "close candidate session failed") }
    }

    private companion object {
        const val LOG_TAG = "SSHConnection"
        const val DEFAULT_PTY_COLUMNS = 80
        const val DEFAULT_PTY_ROWS = 24
        const val SFTP_CONNECT_TIMEOUT_MILLIS = 30_000
        const val MAX_DIRECTORY_ENTRIES = 10_000
    }
}
