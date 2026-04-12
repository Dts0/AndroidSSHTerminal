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
            arrayOf("-c", "while true; do sleep 3600; done"),
            emptyArray(),
            10_000,
            this
        )
        return session
    }

    fun appendOutput(data: String) {
        val emulator = session.emulator ?: return
        val bytes = data.toByteArray(Charsets.UTF_8)
        emulator.append(bytes, bytes.size)
        onTextChanged(session)
    }

    fun appendSystemMessage(data: String) {
        appendOutput(data)
    }

    fun write(data: String) {
        outputWriter(data)
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        screenUpdater()
    }
    override fun onTitleChanged(changedSession: TerminalSession) = Unit
    override fun onSessionFinished(finishedSession: TerminalSession) = Unit

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
    override fun logError(tag: String, message: String) = Unit
    override fun logWarn(tag: String, message: String) = Unit
    override fun logInfo(tag: String, message: String) = Unit
    override fun logDebug(tag: String, message: String) = Unit
    override fun logVerbose(tag: String, message: String) = Unit
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = Unit
    override fun logStackTrace(tag: String, e: Exception) = Unit
}
