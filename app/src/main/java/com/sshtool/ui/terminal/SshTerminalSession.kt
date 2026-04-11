package com.sshtool.ui.terminal

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

class SshTerminalSession(
    private val outputWriter: (String) -> Unit
) : TerminalSessionClient {

    lateinit var session: TerminalSession
        private set

    fun create(): TerminalSession {
        session = TerminalSession("/system/bin/sh", "/", emptyArray(), emptyArray(), 10_000, this)
        return session
    }

    fun appendOutput(data: String) {
        val emulator = session.emulator ?: return
        emulator.append(data.toByteArray(Charsets.UTF_8), data.toByteArray(Charsets.UTF_8).size)
        onTextChanged(session)
    }

    fun appendSystemMessage(data: String) {
        appendOutput(data)
    }

    fun write(data: String) {
        outputWriter(data)
    }

    override fun onTextChanged(changedSession: TerminalSession) = Unit
    override fun onTitleChanged(changedSession: TerminalSession) = Unit
    override fun onSessionFinished(finishedSession: TerminalSession) = Unit
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) = Unit
    override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
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
