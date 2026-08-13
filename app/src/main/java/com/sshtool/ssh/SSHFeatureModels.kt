package com.sshtool.ssh

import com.sshtool.data.model.PortForwardRule

/** Observable state for one configured forwarding rule on one SSH session. */
enum class PortForwardStatus {
    PENDING,
    STARTING,
    ACTIVE,
    FAILED
}

data class PortForwardRuntimeState(
    val rule: PortForwardRule,
    val status: PortForwardStatus,
    /** The actual listening port. It currently equals the configured port. */
    val boundPort: Int? = null,
    val error: String? = null
)

/** A remote SFTP entry. [path] is canonical and can be passed back to SFTP APIs. */
data class RemoteFileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val isSymbolicLink: Boolean = false,
    val size: Long = 0,
    val modifiedTimeMillis: Long? = null
)

data class RemoteDirectoryListing(
    /** Canonical absolute path returned by the SFTP server. */
    val path: String,
    val entries: List<RemoteFileEntry>
)

enum class FileTransferDirection {
    UPLOAD,
    DOWNLOAD
}

/**
 * Durable UI state for the most recent transfer on a logical SSH session.
 * A new transfer replaces a terminal Completed/Failed/Cancelled state.
 */
sealed interface FileTransferState {
    data object Idle : FileTransferState

    data class Running(
        val direction: FileTransferDirection,
        val remotePath: String,
        val transferredBytes: Long,
        val totalBytes: Long? = null
    ) : FileTransferState

    data class Completed(
        val direction: FileTransferDirection,
        val remotePath: String,
        val transferredBytes: Long,
        val totalBytes: Long? = null
    ) : FileTransferState

    data class Failed(
        val direction: FileTransferDirection,
        val remotePath: String,
        val transferredBytes: Long,
        val totalBytes: Long? = null,
        val error: String
    ) : FileTransferState

    data class Cancelled(
        val direction: FileTransferDirection,
        val remotePath: String,
        val transferredBytes: Long,
        val totalBytes: Long? = null
    ) : FileTransferState
}
