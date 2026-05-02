package com.sshtool.ssh

import com.sshtool.SSHToolApp
import com.sshtool.data.model.Host
import com.sshtool.data.repository.HostRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 多会话 SSH 连接管理器
 */
class SSHConnectionManager {

    data class SessionInfo(
        val id: String,
        val host: Host,
        val connection: SSHConnection,
        var listener: SSHConnectionListener
    )

    private val sessions = linkedMapOf<String, SessionInfo>()
    private var activeSessionId: String? = null

    val activeConnection: SSHConnection?
        get() = activeSessionId?.let { sessions[it]?.connection }

    val activeHost: Host?
        get() = activeSessionId?.let { sessions[it]?.host }

    val sessionCount: Int
        get() = sessions.size

    fun getSessionIds(): List<String> = sessions.keys.toList()

    fun getSession(id: String): SessionInfo? = sessions[id]

    fun getActiveSessionId(): String? = activeSessionId

    fun isActiveConnected(): Boolean = activeConnection?.isConnected == true

    fun isSessionConnected(id: String): Boolean = sessions[id]?.connection?.isConnected == true

    fun switchSession(id: String) {
        if (sessions.containsKey(id)) {
            activeSessionId = id
        }
    }

    suspend fun connect(
        host: Host,
        repository: HostRepository,
        listener: SSHConnectionListener
    ): String {
        val sessionId = UUID.randomUUID().toString()

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
                    CoroutineScope(Dispatchers.IO).launchUpdateLastConnected(repository, host.id)
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

        val connection = SSHConnection(
            context = SSHToolApp.instance.applicationContext,
            hostAddress = host.host,
            hostPort = host.port,
            username = host.username,
            useKeyAuth = host.useKeyAuth,
            password = password,
            privateKey = privateKey,
            passphrase = passphrase
        ).apply { this.listener = wrappedListener }

        sessions[sessionId] = SessionInfo(
            id = sessionId,
            host = host,
            connection = connection,
            listener = wrappedListener
        )
        activeSessionId = sessionId

        connection.connect()
        return sessionId
    }

    suspend fun trustAndReconnect(sessionId: String, repository: HostRepository) {
        val session = sessions[sessionId] ?: return
        val host = session.host
        val currentListener = session.listener
        val trusted = session.connection.trustCurrentHostKey()
        session.connection.destroy()
        sessions.remove(sessionId)
        if (activeSessionId == sessionId) activeSessionId = null
        if (trusted) {
            connect(host, repository, currentListener)
        } else {
            currentListener.onStateChanged(SSHConnectionState.Error("无法保存主机指纹"))
        }
    }

    fun updatePtySize(columns: Int, rows: Int) {
        activeConnection?.updatePtySize(columns, rows)
    }

    fun reattachListener(sessionId: String, listener: SSHConnectionListener) {
        val session = sessions[sessionId] ?: return
        session.listener = listener
        session.connection.listener = listener
    }

    fun disconnect(sessionId: String) {
        val session = sessions.remove(sessionId) ?: return
        session.connection.destroy()
        if (activeSessionId == sessionId) {
            activeSessionId = if (sessions.isNotEmpty()) sessions.keys.last() else null
        }
    }

    fun disconnectAll() {
        sessions.values.forEach { it.connection.destroy() }
        sessions.clear()
        activeSessionId = null
    }

    companion object {
        val instance = SSHConnectionManager()
    }
}

private fun CoroutineScope.launchUpdateLastConnected(
    repository: HostRepository,
    hostId: Long
) {
    launch {
        repository.updateLastConnected(hostId)
    }
}
