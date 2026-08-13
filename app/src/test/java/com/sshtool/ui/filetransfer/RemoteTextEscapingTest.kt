package com.sshtool.ui.filetransfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTextEscapingTest {

    @Test
    fun `escapes controls del and bidi overrides without changing ordinary text`() {
        assertEquals(
            "normal 文件\\u001B\\u007F\\u202Etxt",
            "normal 文件\u001B\u007F\u202Etxt".escapeRemoteText()
        )
    }

    @Test
    fun `escapes supplementary format code point as one token`() {
        assertEquals("name\\U000E0001", "name\uDB40\uDC01".escapeRemoteText())
    }

    @Test
    fun `unsafe detection covers formatting and separators`() {
        assertFalse("safe 文件.txt".hasUnsafeRemoteDisplayCodePoint())
        assertTrue("bad\u202Etxt".hasUnsafeRemoteDisplayCodePoint())
        assertTrue("bad\u2028txt".hasUnsafeRemoteDisplayCodePoint())
    }

    @Test
    fun `document suggestion replaces bidi controls and separators`() {
        assertEquals(
            "report_txt_name",
            "report\u202Etxt/name".toSafeDocumentName()
        )
        assertEquals("download", "\u202E".toSafeDocumentName())
    }
}
