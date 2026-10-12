package com.sshtool.ssh

import com.sshtool.R
import com.sshtool.data.model.PortForwardRule
import com.sshtool.data.repository.ConnectionProfile
import com.sshtool.util.UserMessage
import com.sshtool.util.UserStateException
import java.io.InputStream
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel

/**
 * The transport surface used by [SSHConnectionManager]. Keeping the registry
 * dependent on this small interface makes generation and session routing
 * independently verifiable without opening a real socket.
 */
internal interface SSHTransport {
    var listener: SSHConnectionListener?
    val isConnected: Boolean

    suspend fun connect()
    fun trustCurrentHostKey(): Boolean
    fun persistPendingHostKey()
    fun send(data: ByteArray, offset: Int = 0, count: Int = data.size - offset): Boolean
    fun updatePtySize(columns: Int, rows: Int)

    /** Starts each rule independently. Implementations must not fail the shell for one bad rule. */
    suspend fun startPortForwards(
        rules: List<PortForwardRule>,
        onStateChanged: (PortForwardRuntimeState) -> Unit
    ) {
        rules.forEach { rule ->
            onStateChanged(
                PortForwardRuntimeState(
                    rule = rule,
                    status = PortForwardStatus.FAILED,
                    userMessage = UserMessage(R.string.error_forward_unsupported)
                )
            )
        }
    }

    suspend fun listRemoteDirectory(path: String): RemoteDirectoryListing =
        throw UserStateException(UserMessage(R.string.error_sftp_unsupported))

    /** Resolve a selected alias to a directory or regular-file target. */
    suspend fun resolveRemoteEntry(path: String): RemoteFileEntry =
        throw UserStateException(UserMessage(R.string.error_sftp_unsupported))

    suspend fun uploadFile(
        input: InputStream,
        remoteDirectory: String,
        fileName: String,
        overwriteExisting: Boolean,
        totalBytes: Long?,
        onProgress: (Long) -> Unit
    ): Unit = throw UserStateException(UserMessage(R.string.error_sftp_unsupported))

    suspend fun downloadFile(
        remotePath: String,
        output: OutputStream,
        totalBytes: Long?,
        onProgress: (Long) -> Unit
    ): Unit = throw UserStateException(UserMessage(R.string.error_sftp_unsupported))

    fun cancelFileTransfer() = Unit
    fun destroy()
}

internal fun interface SSHTransportFactory {
    fun create(profile: ConnectionProfile): SSHTransport
}

/**
 * Copies every accepted frame and drains it through exactly one writer.
 * OutputStream is not safe for concurrent writes in general, so all SSH input
 * (IME, hardware keyboard, terminal responses and paste) must pass here.
 */
internal class SSHOutboundQueue internal constructor(
    private val maxPendingBytes: Int = DEFAULT_MAX_PENDING_BYTES,
    private val maxWriteChunkBytes: Int = DEFAULT_MAX_WRITE_CHUNK_BYTES
) {
    init {
        require(maxPendingBytes > 0)
        require(maxWriteChunkBytes > 0)
    }

    private val lock = Any()
    private val frames = ArrayDeque<ByteArray>()
    private val available = Channel<Unit>(Channel.CONFLATED)
    private val draining = AtomicBoolean(false)
    private var pendingBytes = 0
    private var closed = false

    fun enqueue(data: ByteArray, offset: Int, count: Int): Boolean {
        if (offset < 0 || count <= 0 || offset > data.size || count > data.size - offset) {
            return false
        }
        synchronized(lock) {
            // Reserve the entire logical write before copying any of it. A
            // large paste is either accepted whole or rejected whole; chunks
            // are only an OutputStream implementation detail for the writer.
            if (closed || count > maxPendingBytes - pendingBytes) return false
            frames.addLast(data.copyOfRange(offset, offset + count))
            pendingBytes += count
        }
        available.trySend(Unit)
        return true
    }

    suspend fun drainTo(stream: OutputStream) {
        check(draining.compareAndSet(false, true)) { "SSH outbound queue already has a writer" }
        try {
            while (true) {
                val frame = synchronized(lock) {
                    when {
                        frames.isNotEmpty() -> frames.removeFirst()
                        closed -> return
                        else -> null
                    }
                }
                if (frame == null) {
                    available.receiveCatching()
                    continue
                }
                try {
                    var offset = 0
                    while (offset < frame.size) {
                        val length = minOf(maxWriteChunkBytes, frame.size - offset)
                        stream.write(frame, offset, length)
                        offset += length
                    }
                    stream.flush()
                } finally {
                    synchronized(lock) { pendingBytes -= frame.size }
                }
            }
        } catch (failure: Throwable) {
            // A failed stream cannot safely resume mid-logical-write. Close
            // admission immediately; SSHConnection will report the failure
            // and tear down the transport.
            close()
            throw failure
        } finally {
            draining.set(false)
        }
    }

    fun close() {
        val shouldClose = synchronized(lock) {
            if (closed) false else {
                closed = true
                true
            }
        }
        if (shouldClose) available.close()
    }

    private companion object {
        const val DEFAULT_MAX_PENDING_BYTES = 512 * 1024
        const val DEFAULT_MAX_WRITE_CHUNK_BYTES = 64 * 1024
    }
}
