package com.sshtool.ssh

import android.util.Log
import com.sshtool.SSHToolApp
import com.sshtool.data.model.Host
import com.sshtool.data.repository.ConnectionProfile
import com.sshtool.data.repository.HostRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
        internal var desiredPtyRows: Int? = null
    )

    private data class ConnectionEndpoint(
        val hostId: Long,
        val host: String,
        val port: Int
    )

    private data class BuiltTransport(
        val transport: SSHTransport,
        val endpoint: ConnectionEndpoint
    )

    private val lock = Any()
    private val sessions = linkedMapOf<String, SessionInfo>()
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
            check(!sessions.containsKey(sessionId)) { "Duplicate SSH session id: $sessionId" }
            sessions[sessionId] = info
        }
        listener.onStateChanged(SSHConnectionState.Connecting)
        startGeneration(info, generation = 0)
    }

    private fun startGeneration(info: SessionInfo, generation: Long) {
        scope.launch {
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
                    SSHConnectionState.Error(e.message ?: "无法读取 SSH 凭据")
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
            connection.connect()
            if (!connection.isConnected) return@launch

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
                Log.w(LOG_TAG, "Unable to persist accepted host key", it)
            }
        }
    }

    private suspend fun buildConnection(info: SessionInfo, generation: Long): BuiltTransport {
        // Resolve metadata and credentials under the repository's mutation
        // mutex. They live in two stores and must belong to the same revision.
        val profile = info.repository.getConnectionProfile(info.hostId)
            ?: throw IllegalArgumentException("主机不存在")
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
                            .onFailure { Log.w(LOG_TAG, "Unable to update last-connected time", it) }
                    }
                }
                target.onStateChanged(state)
            }

            override suspend fun onOutput(data: ByteArray) {
                currentListener()?.onOutput(data)
            }

            override fun onDisconnected() {
                currentListener()?.onDisconnected()
            }
        }

        connection = connectionFactory.create(
            ConnectionProfile(
                host = host,
                credentials = credentials.copy(
                    password = password,
                    privateKey = privateKey,
                    passphrase = passphrase
                )
            )
        ).apply { listener = relay }
        return BuiltTransport(
            transport = connection,
            endpoint = ConnectionEndpoint(host.id, host.host, host.port)
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
        synchronized(lock) {
            info = sessions[sessionId] ?: return
            require(updatedHost == null || updatedHost.id == info.hostId) {
                "Cannot change the host id of an existing SSH session"
            }
            if (listener != null) info.listener = listener
            info.generation += 1
            generation = info.generation
            oldConnection = info.connection
            info.connection = null
            info.connectionEndpoint = null
            stateListener = info.listener
        }
        stateListener.onStateChanged(SSHConnectionState.Connecting)
        scope.launch {
            oldConnection?.listener = null
            oldConnection?.destroy()
            startGeneration(info, generation)
        }
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
                currentListener.onStateChanged(SSHConnectionState.Error("无法保存主机指纹"))
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
                info.desiredPtyColumns = columns
                info.desiredPtyRows = rows
                info.connection
            }
        }
        connection?.updatePtySize(columns, rows)
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
        synchronized(lock) {
            info = sessions[sessionId] ?: return
            info.generation += 1
            oldConnection = info.connection
            info.connection = null
            info.connectionEndpoint = null
            stateListener = info.listener
        }
        stateListener.onStateChanged(SSHConnectionState.Disconnected)
        stateListener.onDisconnected()
        scope.launch {
            oldConnection?.listener = null
            oldConnection?.destroy()
        }
    }

    fun removeSession(sessionId: String) {
        val removed = synchronized(lock) { sessions.remove(sessionId) } ?: return
        scope.launch {
            removed.connection?.listener = null
            removed.connection?.destroy()
        }
    }

    fun disconnectAll() {
        val removed = synchronized(lock) {
            sessions.values.toList().also {
                sessions.clear()
            }
        }
        scope.launch {
            removed.forEach {
                it.connection?.listener = null
                it.connection?.destroy()
            }
        }
    }

    /** Activity ViewModel teardown: close sockets on IO, then retire the scope. */
    fun shutdown() {
        val removed = synchronized(lock) {
            sessions.values.toList().also {
                sessions.clear()
            }
        }
        scope.launch {
            removed.forEach {
                it.connection?.listener = null
                it.connection?.destroy()
            }
            scope.cancel()
        }
    }

    private companion object {
        private const val LOG_TAG = "SSHConnectionManager"

        fun newScope() = CoroutineScope(
            Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, throwable ->
                Log.e(LOG_TAG, "Managed SSH operation failed", throwable)
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
