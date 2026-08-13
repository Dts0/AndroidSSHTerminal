package com.sshtool.ui.filetransfer

import com.sshtool.ssh.RemoteFileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileTransferPickerStateViewModelTest {

    @Test
    fun downloadTokenIsSessionScopedAndConsumedOnlyOnce() {
        val state = FileTransferPickerStateViewModel()
        val entry = RemoteFileEntry(
            name = "report.txt",
            path = "/private/report.txt",
            isDirectory = false
        )
        val token = state.registerDownload("session-a", entry)

        assertNull(state.consumeDownload("session-b", token))
        assertEquals(entry, state.consumeDownload("session-a", token))
        assertNull(state.consumeDownload("session-a", token))
    }

    @Test
    fun rawCurrentPathStaysScopedToInMemorySessionState() {
        val state = FileTransferPickerStateViewModel()

        state.updateCurrentPath("session-a", "/srv/private")
        state.updateCurrentPath("session-b", "/var/data")

        assertEquals("/srv/private", state.currentPath("session-a"))
        assertEquals("/var/data", state.currentPath("session-b"))
        state.discardSession("session-a")
        assertNull(state.currentPath("session-a"))
        assertEquals("/var/data", state.currentPath("session-b"))
    }
}
