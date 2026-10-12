package com.sshtool.ssh

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpException
import com.sshtool.R
import com.sshtool.util.UserArgumentException
import com.sshtool.util.UserMessage
import com.sshtool.util.UserStateException

/** Injectable protocol boundary: resolving a link never requires an extra SSH session. */
internal interface RemoteFileAccess {
    fun realpath(path: String): String
    fun attributes(path: String, followLinks: Boolean): RemoteFileAttributes
}

internal data class RemoteFileAttributes(
    val isDirectory: Boolean = false,
    val isSymbolicLink: Boolean = false,
    val isRegularFile: Boolean = false,
    val size: Long = 0,
    val modifiedTimeMillis: Long? = null
)

internal class SftpRemoteFileAccess(private val sftp: ChannelSftp) : RemoteFileAccess {
    override fun realpath(path: String): String = sftp.realpath(path)

    override fun attributes(path: String, followLinks: Boolean): RemoteFileAttributes {
        val attrs = if (followLinks) sftp.stat(path) else sftp.lstat(path)
        return RemoteFileAttributes(
            isDirectory = attrs.isDir,
            isSymbolicLink = attrs.isLink,
            isRegularFile = attrs.isReg,
            size = attrs.size,
            modifiedTimeMillis = attrs.mTime.takeIf { it > 0 }?.toLong()?.times(1_000L)
        )
    }
}

/** Preserve the selected alias as the suggested name, but operate on the resolved target. */
internal fun resolveRemoteFile(access: RemoteFileAccess, path: String): RemoteFileEntry {
    try {
        val selected = access.attributes(path, followLinks = false)
        // The server handles relative targets, link chains and cycle detection.
        val canonicalPath = access.realpath(path)
        val target = access.attributes(canonicalPath, followLinks = true)
        if (!target.isDirectory && !target.isRegularFile) {
            throw UserArgumentException(UserMessage(R.string.error_download_regular_only))
        }
        return RemoteFileEntry(
            name = path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" },
            path = canonicalPath,
            isDirectory = target.isDirectory,
            isSymbolicLink = selected.isSymbolicLink,
            size = target.size.coerceAtLeast(0),
            modifiedTimeMillis = target.modifiedTimeMillis
        )
    } catch (failure: SftpException) {
        // Do not expose server errors containing private remote paths.
        throw UserStateException(UserMessage(R.string.error_remote_link_unavailable), failure)
    }
}

/** Reused by downloads, including a second validation after the document picker returns. */
internal fun resolveRemoteDownload(access: RemoteFileAccess, path: String): RemoteFileEntry {
    val target = resolveRemoteFile(access, path)
    if (target.isDirectory) {
        throw UserArgumentException(UserMessage(R.string.error_download_directory))
    }
    return target
}
