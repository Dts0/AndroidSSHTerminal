package com.sshtool.ssh

import android.content.Context
import com.jcraft.jsch.*
import kotlinx.coroutines.*
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
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, throwable ->
        android.util.Log.e("SSHConnection", "Unhandled coroutine exception", throwable)
    })
    private val trustStore = HostKeyTrustStore(context)
    private val knownHostsFile = File(context.filesDir, "known_hosts")
    
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
                val hostKeyRepository = TrustingHostKeyRepository(jsch.hostKeyRepository, verifier)
                jsch.hostKeyRepository = hostKeyRepository
                
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
                if (message.contains("reject HostKey", ignoreCase = true) || message.contains("HostKey", ignoreCase = true)) {
                    try {
                        val hostKey = session?.hostKey
                        if (hostKey != null) {
                            listener?.onStateChanged(
                                SSHConnectionState.HostKeyConfirmationRequired(
                                    host = hostAddress,
                                    port = hostPort,
                                    algorithm = hostKey.type,
                                    fingerprint = trustStore.fingerprint(hostKey.key)
                                )
                            )
                        } else {
                            listener?.onStateChanged(SSHConnectionState.Error(message))
                        }
                    } catch (_: Exception) {
                        listener?.onStateChanged(SSHConnectionState.Error(message))
                    }
                } else {
                    listener?.onStateChanged(SSHConnectionState.Error(message))
                }
            } catch (e: Exception) {
                listener?.onStateChanged(SSHConnectionState.Error(e.message ?: "未知错误"))
            }
        }
    }

    fun trustCurrentHostKey(): Boolean {
        val hostKey = session?.hostKey ?: return false
        trustStore.trust(hostAddress, hostPort, hostKey.type, hostKey.key)
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
            } catch (_: Exception) {
                // ignore, disconnect path below handles state reporting
            } finally {
                if (!disconnected) {
                    withContext(Dispatchers.Main) {
                        disconnect()
                    }
                }
            }
        }
    }

    /**
     * 发送数据到 SSH 会话
     */
    fun send(data: String) {
        if (disconnected) return
        scope.launch {
            try {
                val stream = outputStream ?: return@launch
                stream.write(data.toByteArray(Charsets.UTF_8))
                stream.flush()
            } catch (e: Exception) {
                if (disconnected) return@launch
                withContext(Dispatchers.Main) {
                    listener?.onStateChanged(SSHConnectionState.Error(e.message ?: "发送失败"))
                }
            }
        }
    }

    fun updatePtySize(columns: Int, rows: Int) {
        scope.launch {
            try {
                channel?.setPtySize(columns, rows, 0, 0)
            } catch (_: Exception) {
                // Ignore transient resize failures.
            }
        }
    }

    /**
     * 断开连接（幂等）
     */
    fun disconnect() {
        if (disconnected) return
        disconnected = true
        
        // 先关闭 inputStream，打断阻塞中的 read()
        try {
            inputStream?.close()
        } catch (_: Exception) {
        }
        readerJob?.cancel()
        readerJob = null
        try {
            outputStream?.close()
        } catch (_: Exception) {
        }
        try {
            channel?.disconnect()
        } catch (_: Exception) {
        }
        try {
            session?.disconnect()
        } catch (_: Exception) {
        }
        channel = null
        session = null
        inputStream = null
        outputStream = null
        listener?.onStateChanged(SSHConnectionState.Disconnected)
        listener?.onDisconnected()
    }

    /**
     * 销毁连接
     */
    fun destroy() {
        disconnect()
        scope.cancel()
    }
}
