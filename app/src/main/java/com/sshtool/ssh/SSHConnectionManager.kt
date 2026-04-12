package com.sshtool.ssh

import com.sshtool.SSHToolApp
import com.sshtool.data.model.Host
import com.sshtool.data.repository.HostRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * SSH 连接管理器（单例）
 */
object SSHConnectionManager {
    
    private var currentConnection: SSHConnection? = null
    private var currentHost: Host? = null
    private var currentListener: SSHConnectionListener? = null

    fun getCurrentConnection(): SSHConnection? = currentConnection
    fun getCurrentHost(): Host? = currentHost

    suspend fun connect(
        host: Host,
        repository: HostRepository,
        listener: SSHConnectionListener
    ) {
        // 断开现有连接
        disconnect()
        
        currentListener = listener

        // 获取加密的凭据
        val password = if (!host.useKeyAuth) {
            repository.getPassword(host.id) ?: host.password
        } else null
        
        val privateKey = if (host.useKeyAuth) {
            repository.getPrivateKey(host.id) ?: host.privateKey
        } else null
        
        val passphrase = if (host.useKeyAuth) {
            repository.getPassphrase(host.id)
        } else null
        
        val wrappedListener = object : SSHConnectionListener {
            override fun onStateChanged(state: SSHConnectionState) {
                if (state is SSHConnectionState.Connected) {
                    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launchUpdateLastConnected(repository, host.id)
                }
                listener.onStateChanged(state)
            }

            override fun onOutput(data: ByteArray) {
                listener.onOutput(data)
            }

            override fun onDisconnected() {
                listener.onDisconnected()
            }
        }

        // 创建新连接
        currentConnection = SSHConnection(
            context = SSHToolApp.instance.applicationContext,
            hostAddress = host.host,
            hostPort = host.port,
            username = host.username,
            useKeyAuth = host.useKeyAuth,
            password = password,
            privateKey = privateKey,
            passphrase = passphrase
        ).apply {
            this.listener = wrappedListener
        }
        currentHost = host
        
        // 建立连接
        currentConnection?.connect()
    }

    suspend fun trustCurrentHostAndReconnect(repository: HostRepository) {
        val host = currentHost ?: return
        val listener = currentListener ?: return
        val trusted = currentConnection?.trustCurrentHostKey() == true
        currentConnection?.destroy()
        currentConnection = null
        if (trusted) {
            connect(host, repository, listener)
        } else {
            listener.onStateChanged(SSHConnectionState.Error("无法保存主机指纹"))
        }
    }

    fun disconnect() {
        currentConnection?.destroy()
        currentConnection = null
        currentHost = null
    }

    fun isConnected(): Boolean = currentConnection?.isConnected == true
}

private fun CoroutineScope.launchUpdateLastConnected(
    repository: HostRepository,
    hostId: Long
) {
    launch {
        repository.updateLastConnected(hostId)
    }
}
