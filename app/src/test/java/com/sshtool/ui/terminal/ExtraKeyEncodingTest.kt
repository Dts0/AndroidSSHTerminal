package com.sshtool.ui.terminal

import android.view.KeyEvent
import com.termux.terminal.KeyHandler
import org.junit.Assert.assertEquals
import org.junit.Test

class ExtraKeyEncodingTest {
    @Test
    fun `Del sends forward delete and Enter sends carriage return`() {
        assertEquals("\u001B[3~", KeyHandler.getCode(KeyEvent.KEYCODE_FORWARD_DEL, 0, false, false))
        assertEquals("\r", KeyHandler.getCode(KeyEvent.KEYCODE_ENTER, 0, false, false))
    }

    @Test
    fun `arrows continue respecting application cursor mode`() {
        val keys = listOf(
            KeyEvent.KEYCODE_DPAD_UP to "A",
            KeyEvent.KEYCODE_DPAD_DOWN to "B",
            KeyEvent.KEYCODE_DPAD_RIGHT to "C",
            KeyEvent.KEYCODE_DPAD_LEFT to "D"
        )
        keys.forEach { (key, suffix) ->
            assertEquals("\u001B[$suffix", KeyHandler.getCode(key, 0, false, false))
            assertEquals("\u001BO$suffix", KeyHandler.getCode(key, 0, true, false))
        }
    }

    @Test
    fun `Ctrl is encoded by Termux for arrow and delete keys`() {
        assertEquals(
            "\u001B[1;5A",
            KeyHandler.getCode(KeyEvent.KEYCODE_DPAD_UP, KeyHandler.KEYMOD_CTRL, true, false)
        )
        assertEquals(
            "\u001B[3;5~",
            KeyHandler.getCode(KeyEvent.KEYCODE_FORWARD_DEL, KeyHandler.KEYMOD_CTRL, false, false)
        )
    }
}
