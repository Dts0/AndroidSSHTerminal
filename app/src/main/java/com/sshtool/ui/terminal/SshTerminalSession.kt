package com.sshtool.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/**
 * A Termux terminal emulator backed directly by an SSH byte stream.
 *
 * Unlike the old bridge, this does not spawn a local `sleep` subprocess. Every
 * byte produced by the emulator itself (keyboard input, DSR/DA replies, mouse
 * reports and bracketed paste) is forwarded to [outputWriter]. The session is
 * owned by the activity-scoped [TerminalViewModel]; a Fragment only installs a
 * short-lived [screenUpdater] while its view exists.
 */
class SshTerminalSession(
    context: Context,
    private val outputWriter: (data: ByteArray, offset: Int, count: Int) -> Boolean,
    private val onWriteRejected: () -> Unit
) : TerminalSessionClient {

    private val appContext = context.applicationContext

    lateinit var session: TerminalSession
        private set

    @Volatile
    private var screenUpdater: (() -> Unit)? = null

    fun create(): TerminalSession {
        check(!::session.isInitialized) { "Terminal session already created" }
        session = TerminalSession(10_000, this) { data, offset, count ->
            if (!outputWriter(data, offset, count)) onWriteRejected()
        }
        // The emulator must exist before the SSH reader can deliver its first
        // bytes. TerminalView will resize this provisional 80x24 geometry as
        // soon as it is attached; remote mode never touches a local PTY fd.
        session.updateSize(80, 24, 0, 0)
        return session
    }

    fun setScreenUpdater(updater: (() -> Unit)?) {
        screenUpdater = updater
    }

    /** Must be called on the main thread; Termux emulator callbacks are main-thread based. */
    fun appendOutput(data: ByteArray, offset: Int = 0, count: Int = data.size - offset) {
        if (!::session.isInitialized || !session.isRunning) return
        session.appendOutput(data, offset, count)
    }

    fun paste(text: String) {
        if (!::session.isInitialized || text.isEmpty()) return
        session.emulator?.paste(text)
    }

    fun close() {
        screenUpdater = null
        if (::session.isInitialized) session.finishIfRunning()
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        screenUpdater?.invoke()
    }

    override fun onTitleChanged(changedSession: TerminalSession) = Unit

    override fun onSessionFinished(finishedSession: TerminalSession) {
        android.util.Log.i("SshTerminalSession", "remote terminal session finished")
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        runCatching {
            val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: error("Clipboard service unavailable")
            clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
        }.onFailure {
            // OSC 52 is remote-controlled input. Platform clipboard failures
            // must not be allowed to crash the emulator/main thread.
            android.util.Log.w("SshTerminalSession", "Unable to write clipboard")
        }
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        runCatching {
            val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip ?: return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0).coerceToText(appContext)?.toString().orEmpty()
            if (text.isNotEmpty()) paste(text)
        }.onFailure {
            android.util.Log.w("SshTerminalSession", "Unable to read clipboard")
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
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        android.util.Log.e(tag, "Terminal component error")
    }
    override fun logStackTrace(tag: String, e: Exception) {
        android.util.Log.e(tag, "Terminal component error")
    }
}
