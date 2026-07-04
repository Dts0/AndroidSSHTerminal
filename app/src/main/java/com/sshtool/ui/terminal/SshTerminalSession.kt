package com.sshtool.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

class SshTerminalSession(
    private val context: Context,
    private val outputWriter: (String) -> Unit,
    private val screenUpdater: () -> Unit
) : TerminalSessionClient {

    lateinit var session: TerminalSession
        private set

    fun create(): TerminalSession {
        session = TerminalSession(
            "/system/bin/sh",
            "/",
            arrayOf("sh", "-c", "while true; do sleep 86400; done"),
            emptyArray(),
            10_000,
            this
        )
        return session
    }

    fun appendOutput(data: ByteArray) {
        val emulator = session.emulator ?: return
        emulator.append(data, data.size)
        onTextChanged(session)
    }

    fun write(data: String) {
        outputWriter(data)
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        screenUpdater()
    }
    override fun onTitleChanged(changedSession: TerminalSession) = Unit
    override fun onSessionFinished(finishedSession: TerminalSession) {
        // The backing `sleep` subprocess exited. This is expected on explicit
        // disconnect; log it so an unexpectedly-terminated process (e.g. an
        // orphan from a reconnect race) is at least observable rather than
        // silently swallowed (M14).
        android.util.Log.i("SshTerminalSession", "terminal session finished")
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (text.isNotEmpty()) {
            outputWriter(text)
        }
    }
    override fun onBell(session: TerminalSession) = Unit
    override fun onColorsChanged(changedSession: TerminalSession) = Unit
    override fun onTerminalCursorStateChange(state: Boolean) = Unit
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit
    override fun getTerminalCursorStyle(): Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
    override fun logError(tag: String, message: String) { android.util.Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { android.util.Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { android.util.Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { android.util.Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { android.util.Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { android.util.Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { android.util.Log.e(tag, e.toString()) }
}
