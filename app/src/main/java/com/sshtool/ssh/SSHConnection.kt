package com.sshtool.ssh

import android.content.Context
import android.util.Log
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.JSchPtyResizeCompat
import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

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
    data class Error(val message: String) : SSHConnectionState()
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
    private val ptyLock = Any()

    @Volatile private var session: Session? = null
    @Volatile private var channel: ChannelShell? = null
    @Volatile private var inputStream: InputStream? = null
    @Volatile private var outputStream: OutputStream? = null

    private var desiredPtyColumns = DEFAULT_PTY_COLUMNS
    private var desiredPtyRows = DEFAULT_PTY_ROWS
    private var desiredPtyVersion = 0L

    private var readerJob: Job? = null
    private var writerJob: Job? = null
    private val closed = AtomicBoolean(false)
    private val outbound = SSHOutboundQueue()
    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, throwable ->
            Log.e(LOG_TAG, "Unhandled SSH coroutine exception", throwable)
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
                val configuredVersion = synchronized(ptyLock) {
                    setPtySize(desiredPtyColumns, desiredPtyRows, 0, 0)
                    desiredPtyVersion
                }
                connect(30_000)
                // A resize can race the blocking channel connect. JSch 0.2.18
                // only stores setPtySize() locally after connect, so emit its
                // package-private window-change request when the desired size
                // advanced during that interval.
                synchronized(ptyLock) {
                    if (desiredPtyVersion != configuredVersion) {
                        JSchPtyResizeCompat.resize(
                            this,
                            desiredPtyColumns,
                            desiredPtyRows,
                            0,
                            0
                        )
                    }
                }
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
                listener?.onStateChanged(SSHConnectionState.Error(e.message ?: "未知错误"))
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
            SSHConnectionState.Error(error.message ?: "连接失败")
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
            } catch (e: Exception) {
                if (!closed.get()) {
                    Log.w(LOG_TAG, "SSH writer ended", e)
                    listener?.onStateChanged(SSHConnectionState.Error(e.message ?: "发送失败"))
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
            } catch (e: Exception) {
                if (!closed.get()) Log.i(LOG_TAG, "SSH reader ended: ${e.message}", e)
            } finally {
                if (!closed.get()) disconnect()
            }
        }
    }

    override fun updatePtySize(columns: Int, rows: Int) {
        if (columns <= 0 || rows <= 0 || closed.get()) return
        synchronized(ptyLock) {
            desiredPtyColumns = columns
            desiredPtyRows = rows
            desiredPtyVersion += 1
        }
        val targetChannel = channel ?: return
        scope.launch {
            try {
                synchronized(ptyLock) {
                    if (!closed.get() && channel === targetChannel) {
                        JSchPtyResizeCompat.resize(
                            targetChannel,
                            desiredPtyColumns,
                            desiredPtyRows,
                            0,
                            0
                        )
                    }
                }
            } catch (e: Exception) {
                if (!closed.get()) Log.w(LOG_TAG, "setPtySize failed", e)
            }
        }
    }

    /** Idempotent teardown. Call from an IO context for explicit disconnects. */
    fun disconnect() = disconnectInternal(notifyState = true)

    private fun disconnectInternal(notifyState: Boolean) {
        if (!closed.compareAndSet(false, true)) return
        outbound.close()
        readerJob?.cancel()
        writerJob?.cancel()
        closeTransport()
        val callback = listener
        listener = null
        if (notifyState) callback?.onStateChanged(SSHConnectionState.Disconnected)
        callback?.onDisconnected()
        scope.cancel()
    }

    override fun destroy() = disconnect()

    private fun closeTransport() {
        val input: InputStream?
        val output: OutputStream?
        val shell: ChannelShell?
        val sshSession: Session?
        synchronized(resourceLock) {
            input = inputStream
            output = outputStream
            shell = channel
            sshSession = session
            inputStream = null
            outputStream = null
            channel = null
            session = null
        }
        runCatching { input?.close() }.onFailure { Log.w(LOG_TAG, "close input failed", it) }
        runCatching { output?.close() }.onFailure { Log.w(LOG_TAG, "close output failed", it) }
        runCatching { shell?.disconnect() }.onFailure { Log.w(LOG_TAG, "close channel failed", it) }
        runCatching { sshSession?.disconnect() }.onFailure { Log.w(LOG_TAG, "close session failed", it) }
    }

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
        runCatching { input?.close() }.onFailure { Log.w(LOG_TAG, "close candidate input failed", it) }
        runCatching { output?.close() }.onFailure { Log.w(LOG_TAG, "close candidate output failed", it) }
        runCatching { shell?.disconnect() }.onFailure { Log.w(LOG_TAG, "close candidate channel failed", it) }
        runCatching { sshSession?.disconnect() }.onFailure { Log.w(LOG_TAG, "close candidate session failed", it) }
    }

    private companion object {
        const val LOG_TAG = "SSHConnection"
        const val DEFAULT_PTY_COLUMNS = 80
        const val DEFAULT_PTY_ROWS = 24
    }
}
