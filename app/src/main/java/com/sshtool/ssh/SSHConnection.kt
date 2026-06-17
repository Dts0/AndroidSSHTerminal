package com.sshtool.ssh

import android.content.Context
import com.jcraft.jsch.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * SSH 连接状态
 */
sealed class SSHConnectionState {
    object Connecting : SSHConnectionState()
    object Connected : SSHConnectionState()
    data class HostKeyConfirmationRequired(
        val host: String,
        val port: Int,
        val algorithm: String,
        val fingerprint: String
    ) : SSHConnectionState()
    /**
     * A host previously trusted under a different key is now presenting a
     * different key. This is a potential MITM and must be surfaced as a
     * distinct warning rather than folded into the first-connect prompt.
     */
    data class HostKeyChanged(
        val host: String,
        val port: Int,
        val algorithm: String,
        val fingerprint: String
    ) : SSHConnectionState()
    data class Error(val message: String) : SSHConnectionState()
    object Disconnected : SSHConnectionState()
}

/**
 * SSH 连接监听器
 */
interface SSHConnectionListener {
    fun onStateChanged(state: SSHConnectionState)
    fun onOutput(data: ByteArray)
    fun onDisconnected()
}

/**
 * SSH 连接管理器
 */
class SSHConnection(
    context: Context,
    private val hostAddress: String,
    private val hostPort: Int,
    private val username: String,
    private val useKeyAuth: Boolean,
    private val password: String?,
    private val privateKey: String?,
    private val passphrase: String?
) {
    
    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var readerJob: Job? = null
    /** Serializes outbound writes so concurrent send() calls can't interleave
     *  bytes on JSch's non-thread-safe ChannelShell OutputStream (C6). */
    private val sendMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, throwable ->
        android.util.Log.e("SSHConnection", "Unhandled coroutine exception", throwable)
    })
    private val trustStore = HostKeyTrustStore(context)
    private val knownHostsFile = File(context.filesDir, "known_hosts")
    private var hostKeyRepository: StrictHostKeyRepository? = null
    
    var listener: SSHConnectionListener? = null
    
    @Volatile
    private var disconnected = false
    
    val isConnected: Boolean
        get() = session?.isConnected == true

    /**
     * 建立 SSH 连接
     */
    suspend fun connect() {
        withContext(Dispatchers.IO) {
            try {
                listener?.onStateChanged(SSHConnectionState.Connecting)
                
                val jsch = JSch()
                if (!knownHostsFile.exists()) {
                    knownHostsFile.parentFile?.mkdirs()
                    knownHostsFile.createNewFile()
                }
                jsch.setKnownHosts(knownHostsFile.absolutePath)
                
                // 如果使用密钥认证，添加密钥
                if (useKeyAuth && privateKey != null) {
                    if (passphrase != null) {
                        jsch.addIdentity("temp_key", privateKey.toByteArray(), null, passphrase.toByteArray())
                    } else {
                        jsch.addIdentity("temp_key", privateKey.toByteArray(), null, null)
                    }
                }
                
                session = jsch.getSession(username, hostAddress, hostPort)
                val verifier = HostKeyVerifier(hostAddress, hostPort, trustStore)
                val repo = StrictHostKeyRepository(
                    delegate = jsch.hostKeyRepository,
                    verifier = verifier,
                    host = hostAddress,
                    port = hostPort,
                    trustStore = trustStore
                )
                hostKeyRepository = repo
                jsch.hostKeyRepository = repo
                
                // 设置密码或密钥认证
                if (!useKeyAuth && password != null) {
                    session?.setPassword(password)
                }
                
                // 配置 SSH 会话
                session?.apply {
                    setConfig("StrictHostKeyChecking", "yes")
                    setConfig("PreferredAuthentications", if (useKeyAuth) "publickey" else "password")
                    setConfig("HashKnownHosts", "yes")
                    serverAliveInterval = 15000
                    serverAliveCountMax = 3
                    timeout = 30000
                    connect(30000)
                }
                
                // 打开 shell 通道
                channel = session?.openChannel("shell") as? ChannelShell
                channel?.apply {
                    setPty(true)
                    setEnv("TERM", "xterm-256color")
                    setPtyType("xterm-256color")
                    setPtySize(120, 40, 0, 0)
                    connect(30000)
                }

                inputStream = channel?.inputStream
                outputStream = channel?.outputStream
                
                // 开始读取输出
                startReader()
                
                listener?.onStateChanged(SSHConnectionState.Connected)
                
            } catch (e: JSchException) {
                val message = e.message ?: "连接失败"
                // Drive the TOFU flow from the host-key repository's recorded
                // result rather than from JSch's English exception text, which
                // is fragile across versions/locales (M6).
                val rejected = hostKeyRepository?.lastRejectedKey
                if (rejected != null) {
                    val alreadyPinned = trustStore.hasTrustedHost(hostAddress, hostPort)
                    val state = if (alreadyPinned) {
                        SSHConnectionState.HostKeyChanged(
                            host = hostAddress,
                            port = hostPort,
                            algorithm = rejected.algorithm,
                            fingerprint = rejected.fingerprint
                        )
                    } else {
                        SSHConnectionState.HostKeyConfirmationRequired(
                            host = hostAddress,
                            port = hostPort,
                            algorithm = rejected.algorithm,
                            fingerprint = rejected.fingerprint
                        )
                    }
                    listener?.onStateChanged(state)
                } else {
                    listener?.onStateChanged(SSHConnectionState.Error(message))
                }
            } catch (e: Exception) {
                listener?.onStateChanged(SSHConnectionState.Error(e.message ?: "未知错误"))
            }
        }
    }

    /**
     * Pin the host key that was just rejected and shown to the user.
     *
     * Uses the key recorded by [StrictHostKeyRepository.lastRejectedKey] — the
     * exact key whose fingerprint was displayed in the confirmation/changed
     * dialog — instead of `session?.hostKey`, which may be null after a failed
     * connect. On the subsequent reconnect the key is re-verified during the
     * fresh handshake (C2 TOCTOU defense): if an attacker rotates the key in
     * between, the new key will not match this pin and the connect fails /
     * re-prompts rather than silently connecting under a different key.
     */
    fun trustCurrentHostKey(): Boolean {
        val rejected = hostKeyRepository?.lastRejectedKey ?: return false
        trustStore.trust(hostAddress, hostPort, rejected.algorithm, rejected.encodedKey)
        return true
    }

    /**
     * 开始读取远程输出
     */
    private fun startReader() {
        readerJob = scope.launch {
            val buffer = ByteArray(4096)
            try {
                while (isActive) {
                    val currentInput = inputStream ?: break
                    val len = currentInput.read(buffer)
                    if (len < 0) {
                        break
                    }
                    if (len > 0) {
                        val data = buffer.copyOfRange(0, len)
                        withContext(Dispatchers.Main) {
                            listener?.onOutput(data)
                        }
                    }
                }
            } catch (e: Exception) {
                // Reader loop ended due to an I/O or channel error. Log the
                // cause so disconnects aren't silent (M3); the disconnect path
                // below handles state reporting to the listener.
                if (!disconnected) {
                    Log.i("SSHConnection", "reader loop ended: ${e.message}", e)
                }
            } finally {
                // Already on Dispatchers.IO here. Run disconnect() on the IO
                // thread so blocking socket/channel close does not stall the UI
                // thread (M8); disconnect() hops to Main only for the listener
                // notification at the end.
                if (!disconnected) {
                    disconnect()
                }
            }
        }
    }

    /**
     * 发送数据到 SSH 会话。
     *
     * 用 [sendMutex] 串行化写操作，避免多次并发调用导致字节流在
     * JSch 非线程安全的 ChannelShell OutputStream 上交错（C6）。
     */
    fun send(data: String) {
        if (disconnected) return
        scope.launch {
            sendMutex.withLock {
                if (disconnected) return@withLock
                try {
                    val stream = outputStream ?: return@withLock
                    stream.write(data.toByteArray(Charsets.UTF_8))
                    stream.flush()
                } catch (e: Exception) {
                    if (disconnected) return@withLock
                    Log.w("SSHConnection", "send failed", e)
                    withContext(Dispatchers.Main) {
                        listener?.onStateChanged(SSHConnectionState.Error(e.message ?: "发送失败"))
                    }
                }
            }
        }
    }

    fun updatePtySize(columns: Int, rows: Int) {
        scope.launch {
            try {
                channel?.setPtySize(columns, rows, 0, 0)
            } catch (e: Exception) {
                if (!disconnected) Log.w("SSHConnection", "setPtySize failed", e)
            }
        }
    }

    /**
     * 断开连接（幂等）。
     *
     * 阻塞的 socket/channel 关闭在调用线程执行（通常为 IO 线程），避免在
     * UI 线程上做 socket close（M8）。Listener 回调被 post 到主线程。
     */
    fun disconnect() {
        if (disconnected) return
        disconnected = true

        // 先关闭 inputStream，打断阻塞中的 read()
        try {
            inputStream?.close()
        } catch (e: Exception) {
            Log.w("SSHConnection", "close inputStream failed", e)
        }
        readerJob?.cancel()
        readerJob = null
        try {
            outputStream?.close()
        } catch (e: Exception) {
            Log.w("SSHConnection", "close outputStream failed", e)
        }
        try {
            channel?.disconnect()
        } catch (e: Exception) {
            Log.w("SSHConnection", "channel.disconnect failed", e)
        }
        try {
            session?.disconnect()
        } catch (e: Exception) {
            Log.w("SSHConnection", "session.disconnect failed", e)
        }
        channel = null
        session = null
        inputStream = null
        outputStream = null
        // Notify the listener on the main thread. disconnect() may be called
        // from the IO reader loop; listener impls typically touch views.
        val l = listener
        if (l != null) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                l.onStateChanged(SSHConnectionState.Disconnected)
                l.onDisconnected()
            }
        }
    }

    /**
     * 销毁连接
     */
    fun destroy() {
        disconnect()
        scope.cancel()
    }
}
