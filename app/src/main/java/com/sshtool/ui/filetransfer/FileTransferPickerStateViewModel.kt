package com.sshtool.ui.filetransfer

import androidx.lifecycle.ViewModel
import com.sshtool.ssh.RemoteFileEntry
import java.util.UUID

/**
 * Keeps the raw SFTP entry in process memory while Android's document picker
 * is in front. Saved instance state contains only an opaque token, never a
 * potentially sensitive remote path.
 */
internal class FileTransferPickerStateViewModel : ViewModel() {
    private data class PendingDownload(
        val sessionId: String,
        val entry: RemoteFileEntry
    )

    private val pendingDownloads = mutableMapOf<String, PendingDownload>()
    private val currentPaths = mutableMapOf<String, String>()

    @Synchronized
    fun registerDownload(sessionId: String, entry: RemoteFileEntry): String =
        UUID.randomUUID().toString().also { token ->
            pendingDownloads[token] = PendingDownload(sessionId, entry)
        }

    @Synchronized
    fun consumeDownload(sessionId: String, token: String?): RemoteFileEntry? {
        val resolvedToken = token ?: return null
        val pending = pendingDownloads[resolvedToken]
            ?.takeIf { it.sessionId == sessionId }
            ?: return null
        pendingDownloads.remove(resolvedToken)
        return pending.entry
    }

    @Synchronized
    fun discardDownload(sessionId: String, token: String?) {
        val resolvedToken = token ?: return
        if (pendingDownloads[resolvedToken]?.sessionId == sessionId) {
            pendingDownloads.remove(resolvedToken)
        }
    }

    @Synchronized
    fun currentPath(sessionId: String): String? = currentPaths[sessionId]

    @Synchronized
    fun updateCurrentPath(sessionId: String, path: String) {
        currentPaths[sessionId] = path
    }

    @Synchronized
    fun discardSession(sessionId: String) {
        currentPaths.remove(sessionId)
        pendingDownloads.entries.removeAll { it.value.sessionId == sessionId }
    }
}
