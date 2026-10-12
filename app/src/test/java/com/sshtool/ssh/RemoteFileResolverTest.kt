package com.sshtool.ssh

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpException
import com.sshtool.R
import com.sshtool.util.UserArgumentException
import com.sshtool.util.UserStateException
import org.junit.Assert.*
import org.junit.Test

class RemoteFileResolverTest {
    private class FakeAccess : RemoteFileAccess {
        val aliases = mutableMapOf<String, String>()
        val entries = mutableMapOf<String, RemoteFileAttributes>()
        val requests = mutableListOf<Pair<String, Boolean>>()
        var failure: SftpException? = null

        override fun realpath(path: String): String {
            failure?.let { throw it }
            return aliases[path] ?: path
        }

        override fun attributes(path: String, followLinks: Boolean): RemoteFileAttributes {
            requests += path to followLinks
            return entries[path] ?: throw SftpException(ChannelSftp.SSH_FX_NO_SUCH_FILE, "private path: $path")
        }
    }

    @Test fun fileLinkUsesTargetMetadataButPreservesAliasName() {
        val access = FakeAccess().apply {
            aliases["/home/report"] = "/srv/report.txt"
            entries["/home/report"] = RemoteFileAttributes(isSymbolicLink = true, size = 7)
            entries["/srv/report.txt"] = RemoteFileAttributes(isRegularFile = true, size = 4096, modifiedTimeMillis = 123000)
        }
        val result = resolveRemoteDownload(access, "/home/report")
        assertEquals("report", result.name)
        assertEquals("/srv/report.txt", result.path)
        assertTrue(result.isSymbolicLink)
        assertFalse(result.isDirectory)
        assertEquals(4096L, result.size)
        assertEquals(123000L, result.modifiedTimeMillis)
        assertEquals(listOf("/home/report" to false, "/srv/report.txt" to true), access.requests)
    }

    @Test fun relativeAndChainedLinksUseTheServersCanonicalTarget() {
        val access = FakeAccess().apply {
            aliases["/home/relative-chain"] = "/srv/final"
            entries["/home/relative-chain"] = RemoteFileAttributes(isSymbolicLink = true)
            entries["/srv/final"] = RemoteFileAttributes(isDirectory = true)
        }
        val result = resolveRemoteFile(access, "/home/relative-chain")
        assertTrue(result.isDirectory)
        assertEquals("/srv/final", result.path)
    }

    @Test fun ordinaryFilesRemainDownloadable() {
        val access = FakeAccess().apply {
            entries["/file"] = RemoteFileAttributes(isRegularFile = true, size = 12)
        }
        assertFalse(resolveRemoteDownload(access, "/file").isSymbolicLink)
    }

    @Test fun brokenCyclicAndInaccessibleLinksProducePrivateLocalizedErrors() {
        for (status in listOf(ChannelSftp.SSH_FX_NO_SUCH_FILE, ChannelSftp.SSH_FX_FAILURE, ChannelSftp.SSH_FX_PERMISSION_DENIED)) {
            val access = FakeAccess().apply {
                entries["/link"] = RemoteFileAttributes(isSymbolicLink = true)
                failure = SftpException(status, "sensitive remote path")
            }
            val error = assertThrows(UserStateException::class.java) {
                resolveRemoteFile(access, "/link")
            }
            assertEquals(R.string.error_remote_link_unavailable, error.userMessage.resourceId)
            assertFalse(error.message.orEmpty().contains("sensitive"))
        }
    }

    @Test fun downloadRevalidatesTargetAndRejectsDirectoriesAndSpecialFiles() {
        val access = FakeAccess().apply {
            aliases["/link"] = "/target"
            entries["/link"] = RemoteFileAttributes(isSymbolicLink = true)
            entries["/target"] = RemoteFileAttributes(isRegularFile = true, size = 10)
        }
        assertEquals(10L, resolveRemoteDownload(access, "/link").size)
        access.entries["/target"] = RemoteFileAttributes(isDirectory = true)
        assertEquals(R.string.error_download_directory, assertThrows(UserArgumentException::class.java) {
            resolveRemoteDownload(access, "/link")
        }.userMessage.resourceId)
        access.entries["/target"] = RemoteFileAttributes() // FIFO/device/socket
        assertEquals(R.string.error_download_regular_only, assertThrows(UserArgumentException::class.java) {
            resolveRemoteDownload(access, "/link")
        }.userMessage.resourceId)
    }
}
