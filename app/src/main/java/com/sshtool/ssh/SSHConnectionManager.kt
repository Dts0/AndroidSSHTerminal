package com.sshtool.ssh

import com.sshtool.SSHToolApp
import com.sshtool.data.model.Host
import com.sshtool.data.repository.HostRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 多会话 SSH 连接管理器。
 *
 * [sessions] 与 [activeSessionId] 通过 [lock] 同步访问，避免 [connect]/
 * [disconnect]/[switchSession] 在不同协程上下文并发读写时出现
 * ConcurrentModificationException 或丢写（M7）。后台写（如更新最近连接
 * 时间）使用受管的 [scope] 而非游离的 CoroutineScope（M2）。
 */
class SSHConnectionManager {

    data class SessionInfo(
        val id: String,
        val host: Host,
        val connection: SSHConnection,
        var listener: SSHConnectionListener
    )

    private val lock = Any()

    private val sessions = linkedMapOf<String, SessionInfo>()
    private var activeSessionId: String? = null

    /** 受管的后台 scope，随管理器生命周期取消，不会泄漏（M2）。 */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val activeConnection: SSHConnection?
        get() = synchronized(lock) { activeSessionId?.let { sessions[it]?.connection } }

    val activeHost: Host?
        get() = synchronized(lock) { activeSessionId?.let { sessions[it]?.host } }

    val sessionCount: Int
        get() = synchronized(lock) { sessions.size }

    fun getSessionIds(): List<String> = synchronized(lock) { sessions.keys.toList() }

    fun getSession(id: String): SessionInfo? = synchronized(lock) { sessions[id] }

    fun getActiveSessionId(): String? = synchronized(lock) { activeSessionId }

    fun isActiveConnected(): Boolean = activeConnection?.isConnected == true

    fun isSessionConnected(id: String): Boolean =
        synchronized(lock) { sessions[id] }?.connection?.isConnected == true

    fun switchSession(id: String) {
        synchronized(lock) {
            if (sessions.containsKey(id)) {
                activeSessionId = id
            }
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
                    // Use the manager's owned scope rather than a free-floating
                    // CoroutineScope that could never be cancelled (M2).
                    scope.launch { repository.updateLastConnected(host.id) }
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

        synchronized(lock) {
            sessions[sessionId] = SessionInfo(
                id = sessionId,
                host = host,
                connection = connection,
                listener = wrappedListener
            )
            activeSessionId = sessionId
        }

        connection.connect()
        return sessionId
    }

    suspend fun trustAndReconnect(sessionId: String, repository: HostRepository) {
        val host: Host
        val currentListener: SSHConnectionListener
        val connection: SSHConnection
        synchronized(lock) {
            val session = sessions[sessionId] ?: return
            host = session.host
            currentListener = session.listener
            connection = session.connection
        }
        val trusted = connection.trustCurrentHostKey()
        connection.destroy()
        synchronized(lock) {
            sessions.remove(sessionId)
            if (activeSessionId == sessionId) activeSessionId = null
        }
        if (trusted) {
            connect(host, repository, currentListener)
        } else {
            currentListener.onStateChanged(SSHConnectionState.Error("无法保存主机指纹"))
        }
    }

    fun updatePtySize(columns: Int, rows: Int) {
        activeConnection?.updatePtySize(columns, rows)
    }

    /**
     * Resize the PTY of a specific session (not just the active one), so a
     * newly-connected session can be reconciled with the emulator's actual
     * dimensions regardless of which session is currently active (M9).
     */
    fun updatePtySizeForSession(sessionId: String, columns: Int, rows: Int) {
        synchronized(lock) { sessions[sessionId] }?.connection?.updatePtySize(columns, rows)
    }

    fun reattachListener(sessionId: String, listener: SSHConnectionListener) {
        synchronized(lock) {
            val session = sessions[sessionId] ?: return
            session.listener = listener
            session.connection.listener = listener
        }
    }

    fun disconnect(sessionId: String) {
        val session: SessionInfo?
        synchronized(lock) {
            session = sessions.remove(sessionId)
            if (session != null && activeSessionId == sessionId) {
                activeSessionId = if (sessions.isNotEmpty()) sessions.keys.last() else null
            }
        }
        session?.connection?.destroy()
    }

    fun disconnectAll() {
        val toDestroy: List<SSHConnection>
        synchronized(lock) {
            toDestroy = sessions.values.map { it.connection }
            sessions.clear()
            activeSessionId = null
        }
        // Tear down outside the lock so blocking socket close doesn't hold it.
        toDestroy.forEach { runCatching { it.destroy() } }
    }

    /** Cancel background scopes; used when the app is being torn down. */
    fun shutdown() {
        disconnectAll()
        scope.cancel()
    }

    companion object {
        val instance = SSHConnectionManager()
    }
}
