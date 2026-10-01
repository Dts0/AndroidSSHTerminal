package com.sshtool.ui.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withStarted
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.material.tabs.TabLayout
import com.sshtool.R
import com.sshtool.SSHToolApp
import com.sshtool.databinding.FragmentTerminalBinding
import com.sshtool.ssh.FileTransferState
import com.sshtool.ssh.PortForwardStatus
import com.sshtool.ssh.SSHConnectionState
import com.sshtool.ui.MainActivity
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A view-only facade over activity-owned SSH/terminal sessions. */
class TerminalFragment : Fragment() {

    private var _binding: FragmentTerminalBinding? = null
    private val binding get() = _binding!!

    private val args: TerminalFragmentArgs by navArgs()
    private val viewModel: TerminalViewModel by lazy(LazyThreadSafetyMode.NONE) {
        (requireActivity() as MainActivity).terminalViewModel
    }

    private var isLeavingScreen = false
    private var ctrlArmed = false
    private var terminalFontSize = 14f
    private var entryHostRequestConsumed = false
    private var renderingTabs = false
    private var renderedTabKeys: List<Pair<String, String>> = emptyList()
    private var attachedSessionId: String? = null
    private var lastRenderedActiveSessionId: String? = null
    private var lastRenderedConnectionState: SSHConnectionState? = null
    private var screenRefreshPending = false
    private val observedBridges = mutableMapOf<String, SshTerminalSession>()
    private var hostKeyDialog: AlertDialog? = null
    private var hostKeyDialogKey: String? = null
    private var hostPickerDialog: AlertDialog? = null
    private var hostPickerLoading = false
    private var hostPickerJob: Job? = null
    private var lastErrorToastKey: String? = null
    private var focusRunnable: Runnable? = null
    private var entryHostJob: Job? = null
    private var isOpeningFileTransfer = false
    private var terminalMenuPopup: PopupMenu? = null
    private var pressedDirectionButton: View? = null
    private val keyRepeatHandler = Handler(Looper.getMainLooper())
    private val keyRepeater = KeyRepeatController { delayMillis, task ->
        val runnable = Runnable { task() }
        keyRepeatHandler.postDelayed(runnable, delayMillis)
        val cancel: () -> Unit = { keyRepeatHandler.removeCallbacks(runnable) }
        cancel
    }
    private val windowFocusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
        if (!hasFocus) keyRepeater.stop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        entryHostRequestConsumed = savedInstanceState?.getBoolean(
            STATE_ENTRY_HOST_REQUEST_CONSUMED
        ) ?: false
        terminalFontSize = savedInstanceState?.getFloat(STATE_TERMINAL_FONT_SIZE, 14f) ?: 14f
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_ENTRY_HOST_REQUEST_CONSUMED, entryHostRequestConsumed)
        outState.putFloat(STATE_TERMINAL_FONT_SIZE, terminalFontSize)
        super.onSaveInstanceState(outState)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTerminalBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        isLeavingScreen = false
        setupToolbar()
        setupTerminalView()
        setupExtraKeys()
        setupBackPressHandler()
        setupTabLayout()
        setupImeResizeListener()
        binding.root.viewTreeObserver.addOnWindowFocusChangeListener(windowFocusListener)
        observeSessions()

        entryHostJob = viewLifecycleOwner.lifecycleScope.launch {
            val target = try {
                val current = viewModel.uiState.value
                when {
                    // Retry an entry request that was cancelled by recreation,
                    // but do not re-select its original host after it has
                    // completed and the user has moved to another tab. An empty
                    // activity ViewModel means process recreation and also
                    // requires rebuilding the entry session from the nav arg.
                    args.hostId != -1L &&
                        (!entryHostRequestConsumed || current.sessions.isEmpty()) -> {
                        viewModel.ensureSessionForHost(args.hostId).also { sessionId ->
                            if (sessionId != null) entryHostRequestConsumed = true
                        }
                    }
                    current.sessions.isNotEmpty() -> {
                        current.activeSessionId
                            ?: current.sessions.first().sessionId.also(viewModel::selectSession)
                    }
                    else -> null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                viewLifecycleOwner.lifecycle.withStarted {
                    showHostLoadFailure(e)
                    if (viewModel.uiState.value.sessions.isEmpty() && _binding != null) {
                        leaveScreen()
                    }
                }
                return@launch
            }
            if (target == null) {
                viewLifecycleOwner.lifecycle.withStarted {
                    if (viewModel.uiState.value.sessions.isEmpty() && _binding != null) {
                        Toast.makeText(
                            requireContext(),
                            R.string.host_not_found,
                            Toast.LENGTH_SHORT
                        ).show()
                        leaveScreen()
                    }
                }
            }
        }
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener { leaveScreen() }
        binding.btnMore.setOnClickListener { showTerminalMenu() }
    }

    private fun setupTerminalView() {
        binding.terminalView.setTextSize(terminalFontSize.toInt())
        binding.terminalView.setScaleFactor(terminalFontSize / 14f)
        binding.terminalView.setTerminalViewClient(object : TerminalViewClient {
            override fun onScale(scale: Float): Float {
                val b = _binding ?: return terminalFontSize / 14f
                val newSize = (14f * scale).coerceIn(8f, 32f)
                if (kotlin.math.abs(newSize - terminalFontSize) >= 0.1f) {
                    terminalFontSize = newSize
                    b.terminalView.setTextSize(newSize.toInt())
                    b.terminalView.onScreenUpdated()
                }
                return terminalFontSize / 14f
            }

            override fun onSingleTapUp(e: MotionEvent) = focusTerminalInput()
            override fun shouldBackButtonBeMappedToEscape(): Boolean = false
            override fun shouldEnforceCharBasedInput(): Boolean = true
            override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
            override fun isTerminalViewSelected(): Boolean =
                _binding?.terminalView?.hasFocus() == true
            override fun copyModeChanged(copyMode: Boolean) = Unit

            // Returning false delegates all physical-key semantics to the
            // vendored Termux implementation. Its writes now use the remote
            // TerminalSession byte sink rather than a hidden EditText.
            override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
                if (ctrlArmed && e.action == KeyEvent.ACTION_DOWN && !KeyEvent.isModifierKey(keyCode)) {
                    clearCtrlAfterCurrentEvent()
                }
                return false
            }

            override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
            override fun onLongPress(event: MotionEvent): Boolean = false
            override fun readControlKey(): Boolean = ctrlArmed
            override fun readAltKey(): Boolean = false
            override fun readShiftKey(): Boolean = false
            override fun readFnKey(): Boolean = false

            override fun onCodePoint(
                codePoint: Int,
                ctrlDown: Boolean,
                session: TerminalSession
            ): Boolean {
                // TerminalView computed ctrlDown before invoking this callback,
                // so clearing synchronously preserves Ctrl for this code point
                // but not for later code points in the same IME commit batch.
                if (ctrlArmed) clearCtrlArmed()
                return false
            }

            override fun onEmulatorSet() = Unit
            override fun onTerminalSizeChanged(columns: Int, rows: Int) {
                attachedSessionId?.let {
                    viewModel.resize(it, columns, rows)
                }
            }

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
        })
    }

    private fun setupExtraKeys() {
        binding.btnCtrl.setOnClickListener {
            ctrlArmed = !ctrlArmed
            renderCtrlState()
            focusTerminalInput()
        }
        binding.btnTab.setOnClickListener { sendRaw("\t") }
        binding.btnEsc.setOnClickListener { sendRaw("\u001B") }
        binding.btnPaste.setOnClickListener { pasteClipboard() }
        setupRepeatingDirectionKey(binding.btnUp, KeyEvent.KEYCODE_DPAD_UP)
        setupRepeatingDirectionKey(binding.btnDown, KeyEvent.KEYCODE_DPAD_DOWN)
        setupRepeatingDirectionKey(binding.btnRight, KeyEvent.KEYCODE_DPAD_RIGHT)
        setupRepeatingDirectionKey(binding.btnLeft, KeyEvent.KEYCODE_DPAD_LEFT)
        binding.btnPgup.setOnClickListener { sendTerminalKey(KeyEvent.KEYCODE_PAGE_UP) }
        binding.btnPgdn.setOnClickListener { sendTerminalKey(KeyEvent.KEYCODE_PAGE_DOWN) }
        binding.btnHome.setOnClickListener { sendTerminalKey(KeyEvent.KEYCODE_MOVE_HOME) }
        binding.btnEnd.setOnClickListener { sendTerminalKey(KeyEvent.KEYCODE_MOVE_END) }
        binding.btnDel.setOnClickListener { sendTerminalKey(KeyEvent.KEYCODE_FORWARD_DEL) }
        binding.btnEnter.setOnClickListener { sendTerminalKey(KeyEvent.KEYCODE_ENTER) }
        setTerminalInputEnabled(false)
    }

    @SuppressLint("ClickableViewAccessibility") // Touch sends the first key via performClick().
    private fun setupRepeatingDirectionKey(button: View, keyCode: Int) {
        button.setOnClickListener { sendTerminalKey(keyCode) }
        var pointerId = MotionEvent.INVALID_POINTER_ID
        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Ignore other fingers/buttons until the original press ends.
                    if (pressedDirectionButton == null && button.isEnabled) {
                        val sessionId = attachedSessionId
                        pressedDirectionButton = button
                        pointerId = event.getPointerId(event.actionIndex)
                        button.isPressed = true
                        var firstKey = true
                        keyRepeater.start(
                            canSend = {
                                val b = _binding
                                sessionId != null && b != null && button.isEnabled &&
                                    b.terminalView.isEnabled && !isLeavingScreen &&
                                    !isOpeningFileTransfer && terminalMenuPopup == null &&
                                    hostPickerDialog?.isShowing != true &&
                                    hostKeyDialog?.isShowing != true &&
                                    viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                                    attachedSessionId == sessionId &&
                                    activeSession()?.let {
                                        it.sessionId == sessionId &&
                                            it.connectionState is SSHConnectionState.Connected
                                    } == true
                            },
                            send = {
                                if (firstKey) {
                                    firstKey = false
                                    button.performClick()
                                } else {
                                    sendTerminalKey(keyCode)
                                }
                            },
                            onStopped = {
                                button.isPressed = false
                                pointerId = MotionEvent.INVALID_POINTER_ID
                                if (pressedDirectionButton === button) pressedDirectionButton = null
                            }
                        )
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (pressedDirectionButton === button) {
                        val index = event.findPointerIndex(pointerId)
                        if (index < 0 || event.getX(index) < 0 || event.getX(index) >= button.width ||
                            event.getY(index) < 0 || event.getY(index) >= button.height) {
                            keyRepeater.stop()
                        }
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (pressedDirectionButton === button &&
                        event.getPointerId(event.actionIndex) == pointerId) {
                        keyRepeater.stop()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (pressedDirectionButton === button) keyRepeater.stop()
                }
            }
            // Consuming UP avoids Button generating a second click after the DOWN click.
            true
        }
    }

    private fun activeSession(): TerminalTabState? {
        val state = viewModel.uiState.value
        return state.sessions.firstOrNull { it.sessionId == state.activeSessionId }
    }

    private fun showTerminalMenu() {
        keyRepeater.stop()
        terminalMenuPopup?.dismiss()
        val popup = PopupMenu(requireContext(), binding.btnMore)
        popup.inflate(R.menu.menu_terminal)
        terminalMenuPopup = popup
        updateTerminalMenu(activeSession())
        popup.setOnMenuItemClickListener { item ->
            // Recheck live state rather than trusting the state when the menu was opened.
            val session = activeSession()
            when (item.itemId) {
                R.id.action_file_transfer -> openFileTransfer()
                R.id.action_connect -> if (session != null && canReconnect(session)) {
                    viewModel.reconnect(session.sessionId)
                }
                R.id.action_disconnect -> if (session != null && canDisconnect(session)) {
                    viewModel.disconnect(session.sessionId)
                }
            }
            true
        }
        popup.setOnDismissListener {
            if (terminalMenuPopup === popup) terminalMenuPopup = null
        }
        popup.show()
    }

    private fun canReconnect(session: TerminalTabState): Boolean =
        session.connectionState is SSHConnectionState.Disconnected ||
            session.connectionState is SSHConnectionState.Error

    private fun canDisconnect(session: TerminalTabState): Boolean = !canReconnect(session)

    private fun sessionStatusText(session: TerminalTabState): String =
        when (val state = session.connectionState) {
            SSHConnectionState.Connected -> connectedStatusText(session)
            SSHConnectionState.Connecting -> getString(R.string.status_connecting)
            SSHConnectionState.Disconnected -> getString(R.string.status_disconnected)
            is SSHConnectionState.Error -> getString(R.string.status_error, state.message)
            is SSHConnectionState.HostKeyConfirmationRequired -> getString(R.string.status_error, state.fingerprint)
            is SSHConnectionState.HostKeyChanged -> getString(R.string.status_error, state.fingerprint)
        }

    private fun updateTerminalMenu(session: TerminalTabState?) {
        val b = _binding ?: return
        val status = session?.let(::sessionStatusText) ?: getString(R.string.status_disconnected)
        b.btnMore.contentDescription = getString(
            R.string.terminal_actions_status_desc,
            session?.host?.name ?: getString(R.string.app_name), status
        )
        val menu = terminalMenuPopup?.menu ?: return
        menu.findItem(R.id.terminal_status).title = status
        menu.findItem(R.id.action_connect).isEnabled = session?.let(::canReconnect) == true
        menu.findItem(R.id.action_disconnect).isEnabled = session?.let(::canDisconnect) == true
        menu.findItem(R.id.action_file_transfer).apply {
            isEnabled = session?.connectionState is SSHConnectionState.Connected
            title = when (val transfer = session?.fileTransferState) {
                is FileTransferState.Running -> {
                    val total = transfer.totalBytes
                    if (total != null && total > 0) {
                        val percent = ((transfer.transferredBytes.toDouble() / total) * 100)
                            .toInt().coerceIn(0, 100)
                        "${getString(R.string.action_file_transfer)} $percent%"
                    } else {
                        "${getString(R.string.action_file_transfer)}…"
                    }
                }
                else -> getString(R.string.action_file_transfer)
            }
        }
    }

    private fun setupBackPressHandler() {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = leaveScreen()
            }
        )
    }

    private fun setupTabLayout() {
        binding.btnAddSession.setOnClickListener { showHostPickerDialog() }
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (renderingTabs) return
                keyRepeater.stop()
                terminalMenuPopup?.dismiss()
                val tag = tab.tag as? String ?: return
                // Attach synchronously before publishing the new active id.
                // Otherwise a physical-key or late IME callback delivered
                // before the StateFlow collector runs can target the old
                // TerminalSession while the UI already shows the new tab.
                viewModel.uiState.value.sessions
                    .firstOrNull { it.sessionId == tag }
                    ?.let(::attachTerminalSession)
                viewModel.selectSession(tag)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    private fun observeSessions() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    launch {
                        viewModel.events.collect { event ->
                            when (event) {
                                is TerminalUiEvent.SendRejected -> Toast.makeText(
                                    requireContext(),
                                    getString(R.string.send_rejected, event.hostName),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                    viewModel.uiState.collectLatest(::render)
                } finally {
                    unbindScreenObservers()
                    screenRefreshPending = true
                }
            }
        }
    }

    private fun render(state: TerminalUiState) {
        val b = _binding ?: return
        bindScreenObservers(state.sessions)
        rebuildTabsIfNeeded(state.sessions, state.activeSessionId)

        val active = state.sessions.firstOrNull { it.sessionId == state.activeSessionId }
        if (active == null) {
            if (attachedSessionId != null) b.terminalView.invalidateInputConnection()
            attachedSessionId = null
            lastRenderedActiveSessionId = null
            lastRenderedConnectionState = null
            setTerminalInputEnabled(false)
            updateTerminalMenu(null)
            return
        }

        selectTab(active.sessionId)
        attachTerminalSession(active)
        updateTabIndicators(state.sessions)
        val shouldActivateConnected =
            lastRenderedActiveSessionId != active.sessionId ||
                (active.connectionState is SSHConnectionState.Connected &&
                    lastRenderedConnectionState !is SSHConnectionState.Connected)
        applyConnectionState(active, shouldActivateConnected)
        lastRenderedActiveSessionId = active.sessionId
        lastRenderedConnectionState = active.connectionState
    }

    private fun attachTerminalSession(session: TerminalTabState) {
        val b = _binding ?: return
        if (attachedSessionId == session.sessionId) {
            // Screen callbacks are intentionally unbound while STOPPED. Draw
            // the latest emulator contents when collection resumes.
            if (screenRefreshPending) {
                screenRefreshPending = false
                b.terminalView.onScreenUpdated()
            }
            return
        }

        keyRepeater.stop()
        terminalMenuPopup?.dismiss()
        val previousSessionId = attachedSessionId
        if (previousSessionId != null) clearCtrlArmed()
        attachedSessionId = session.sessionId
        screenRefreshPending = false
        val changed = b.terminalView.attachSession(session.terminalSession)
        if (changed) {
            // attachSession invalidates the old IME generation. Ask Android
            // for a fresh InputConnection immediately; any late callback
            // from the old one is rejected by TerminalView.
            val imm = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.restartInput(b.terminalView)
        }
        b.terminalView.onScreenUpdated()
    }

    private fun bindScreenObservers(sessions: List<TerminalTabState>) {
        val currentIds = sessions.mapTo(mutableSetOf()) { it.sessionId }
        observedBridges.keys.filterNot(currentIds::contains).forEach { id ->
            observedBridges.remove(id)?.setScreenUpdater(null)
        }
        sessions.forEach { session ->
            if (observedBridges[session.sessionId] !== session.bridge) {
                observedBridges[session.sessionId]?.setScreenUpdater(null)
                observedBridges[session.sessionId] = session.bridge
                session.bridge.setScreenUpdater {
                    val b = _binding ?: return@setScreenUpdater
                    if (viewModel.uiState.value.activeSessionId == session.sessionId) {
                        b.terminalView.onScreenUpdated()
                    }
                }
            }
        }
    }

    private fun unbindScreenObservers() {
        observedBridges.values.forEach { it.setScreenUpdater(null) }
        observedBridges.clear()
    }

    private fun rebuildTabsIfNeeded(
        sessions: List<TerminalTabState>,
        activeSessionId: String?
    ) {
        val keys = sessions.map { it.sessionId to it.host.name }
        if (keys == renderedTabKeys) return
        renderedTabKeys = keys
        val selectedSessionId = activeSessionId ?: sessions.firstOrNull()?.sessionId
        renderingTabs = true
        try {
            binding.tabLayout.removeAllTabs()
            val occurrences = mutableMapOf<String, Int>()
            sessions.forEach { session ->
                val number = (occurrences[session.host.name] ?: 0) + 1
                occurrences[session.host.name] = number
                val label = if (number == 1) session.host.name else "${session.host.name} ($number)"
                binding.tabLayout.addTab(
                    binding.tabLayout.newTab().setText(label).setTag(session.sessionId),
                    session.sessionId == selectedSessionId
                )
            }
        } finally {
            renderingTabs = false
        }
    }

    private fun selectTab(sessionId: String) {
        val tab = (0 until binding.tabLayout.tabCount)
            .mapNotNull(binding.tabLayout::getTabAt)
            .firstOrNull { it.tag == sessionId } ?: return
        if (tab.isSelected) return
        renderingTabs = true
        try {
            tab.select()
        } finally {
            renderingTabs = false
        }
    }

    private fun updateTabIndicators(sessions: List<TerminalTabState>) {
        sessions.forEach { session ->
            val tab = (0 until binding.tabLayout.tabCount)
                .mapNotNull(binding.tabLayout::getTabAt)
                .firstOrNull { it.tag == session.sessionId } ?: return@forEach
            val drawableRes = when (session.connectionState) {
                SSHConnectionState.Connected -> R.drawable.status_connected
                SSHConnectionState.Connecting -> R.drawable.status_connecting
                SSHConnectionState.Disconnected -> R.drawable.status_disconnected
                is SSHConnectionState.Error,
                is SSHConnectionState.HostKeyConfirmationRequired,
                is SSHConnectionState.HostKeyChanged -> R.drawable.status_error
            }
            tab.icon = AppCompatResources.getDrawable(requireContext(), drawableRes)
            tab.contentDescription = getString(
                R.string.terminal_tab_status_desc, tab.text, sessionStatusText(session)
            )
        }
    }

    private fun applyConnectionState(
        session: TerminalTabState,
        shouldActivateConnected: Boolean
    ) {
        if (_binding == null) return
        val state = session.connectionState
        updateTerminalMenu(session)
        if (state !is SSHConnectionState.Error) lastErrorToastKey = null
        if (state !is SSHConnectionState.HostKeyConfirmationRequired &&
            state !is SSHConnectionState.HostKeyChanged) {
            dismissHostKeyDialog()
        }
        when (state) {
            SSHConnectionState.Connecting -> {
                setTerminalInputEnabled(false)
            }
            SSHConnectionState.Connected -> {
                setTerminalInputEnabled(true)
                if (shouldActivateConnected) focusTerminalInput()
            }
            is SSHConnectionState.HostKeyConfirmationRequired -> {
                setTerminalInputEnabled(false)
                showHostKeyDialog(session.sessionId, state)
            }
            is SSHConnectionState.HostKeyChanged -> {
                setTerminalInputEnabled(false)
                showHostKeyDialog(session.sessionId, state)
            }
            is SSHConnectionState.Error -> {
                setTerminalInputEnabled(false)
                val key = "${session.sessionId}|${state.message}"
                if (lastErrorToastKey != key) {
                    lastErrorToastKey = key
                    Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
                }
            }
            SSHConnectionState.Disconnected -> {
                setTerminalInputEnabled(false)
            }
        }
    }

    private fun connectedStatusText(session: TerminalTabState): String {
        val parts = mutableListOf(getString(R.string.status_connected))
        if (session.portForwardStates.isNotEmpty()) {
            val active = session.portForwardStates.count { it.status == PortForwardStatus.ACTIVE }
            val starting = session.portForwardStates.count {
                it.status == PortForwardStatus.PENDING || it.status == PortForwardStatus.STARTING
            }
            val failed = session.portForwardStates.count { it.status == PortForwardStatus.FAILED }
            parts += "映射 $active/${session.portForwardStates.size}"
            if (starting > 0) parts += "$starting 条启动中"
            if (failed > 0) parts += "$failed 条失败"
        }
        if (session.configurationStale) parts += "配置待重连"
        return parts.joinToString(" · ")
    }

    private fun openFileTransfer() {
        keyRepeater.stop()
        if (isOpeningFileTransfer) return
        val active = viewModel.uiState.value.sessions.firstOrNull {
            it.sessionId == viewModel.uiState.value.activeSessionId
        } ?: return
        if (active.connectionState !is SSHConnectionState.Connected) return
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.terminalFragment) return
        isOpeningFileTransfer = true
        runCatching {
            navController.navigate(
                TerminalFragmentDirections.actionTerminalToFileTransfer(active.sessionId)
            )
        }.onFailure {
            isOpeningFileTransfer = false
            android.util.Log.w("TerminalFragment", "File-transfer navigation was rejected")
        }
    }

    private fun showHostPickerDialog() {
        keyRepeater.stop()
        if (hostPickerJob?.isActive == true ||
            hostPickerLoading ||
            hostPickerDialog?.isShowing == true) return
        hostPickerLoading = true
        hostPickerJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val hosts = SSHToolApp.instance.hostRepository.getAllHosts().first()
                if (_binding == null) return@launch
                if (hosts.isEmpty()) {
                    Toast.makeText(requireContext(), "没有可用的主机，请先添加", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val names = hosts.map { "${it.name}  (${it.host}:${it.port})" }.toTypedArray()
                hostPickerDialog = AlertDialog.Builder(requireContext())
                    .setTitle("选择主机")
                    .setItems(names) { _, which ->
                        hostPickerJob = viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                if (viewModel.addSessionForHost(hosts[which].id) == null && _binding != null) {
                                    Toast.makeText(requireContext(), R.string.host_not_found, Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                showHostLoadFailure(e)
                            }
                        }
                    }
                    .create()
                    .also { dialog ->
                        dialog.setOnDismissListener {
                            if (hostPickerDialog === dialog) hostPickerDialog = null
                        }
                        dialog.show()
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showHostLoadFailure(e)
            } finally {
                hostPickerLoading = false
            }
        }
    }

    private fun showHostLoadFailure(error: Exception) {
        val ctx = context ?: return
        val detail = error.localizedMessage?.takeIf(String::isNotBlank)
        val message = detail?.let { "无法加载主机：$it" } ?: "无法加载主机"
        Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
    }

    private fun showHostKeyDialog(
        sessionId: String,
        state: SSHConnectionState.HostKeyConfirmationRequired
    ) {
        val key = "$sessionId|new|${state.fingerprint}"
        if (hostKeyDialogKey == key && hostKeyDialog?.isShowing == true) return
        keyRepeater.stop()
        dismissHostKeyDialog()
        hostKeyDialogKey = key
        hostKeyDialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.host_key_confirm_title)
            .setMessage(getString(
                R.string.host_key_confirm_message,
                state.host, state.port, state.algorithm, state.fingerprint
            ))
            .setNegativeButton(R.string.cancel) { _, _ -> rejectHostKey(sessionId) }
            .setPositiveButton(R.string.trust_and_connect) { _, _ ->
                viewModel.trustAndReconnect(sessionId)
            }
            .setCancelable(false)
            .create()
            .also { dialog ->
                dialog.setOnDismissListener {
                    if (hostKeyDialog === dialog) {
                        hostKeyDialog = null
                        hostKeyDialogKey = null
                    }
                }
                dialog.show()
            }
    }

    private fun showHostKeyDialog(sessionId: String, state: SSHConnectionState.HostKeyChanged) {
        val key = "$sessionId|changed|${state.fingerprint}"
        if (hostKeyDialogKey == key && hostKeyDialog?.isShowing == true) return
        keyRepeater.stop()
        dismissHostKeyDialog()
        hostKeyDialogKey = key
        hostKeyDialog = AlertDialog.Builder(requireContext())
            .setIcon(android.R.drawable.stat_sys_warning)
            .setTitle(R.string.host_key_changed_title)
            .setMessage(getString(
                R.string.host_key_changed_message,
                state.host, state.port, state.algorithm, state.fingerprint
            ))
            .setNegativeButton(R.string.cancel) { _, _ -> rejectHostKey(sessionId) }
            .setPositiveButton(R.string.trust_and_connect) { _, _ ->
                viewModel.trustAndReconnect(sessionId)
            }
            .setCancelable(false)
            .create()
            .also { dialog ->
                dialog.setOnDismissListener {
                    if (hostKeyDialog === dialog) {
                        hostKeyDialog = null
                        hostKeyDialogKey = null
                    }
                }
                dialog.show()
            }
    }

    private fun rejectHostKey(sessionId: String) {
        viewModel.disconnect(sessionId)
        Toast.makeText(requireContext(), R.string.host_key_rejected, Toast.LENGTH_SHORT).show()
    }

    private fun dismissHostKeyDialog() {
        hostKeyDialog?.dismiss()
        hostKeyDialog = null
        hostKeyDialogKey = null
    }

    private fun setTerminalInputEnabled(enabled: Boolean) {
        val b = _binding ?: return
        b.terminalView.isEnabled = enabled
        b.terminalView.isFocusable = enabled
        b.terminalView.isFocusableInTouchMode = enabled
        b.btnCtrl.isEnabled = enabled
        b.btnTab.isEnabled = enabled
        b.btnEsc.isEnabled = enabled
        b.btnPaste.isEnabled = enabled
        b.btnUp.isEnabled = enabled
        b.btnDown.isEnabled = enabled
        b.btnLeft.isEnabled = enabled
        b.btnRight.isEnabled = enabled
        b.btnPgup.isEnabled = enabled
        b.btnPgdn.isEnabled = enabled
        b.btnHome.isEnabled = enabled
        b.btnEnd.isEnabled = enabled
        b.btnDel.isEnabled = enabled
        b.btnEnter.isEnabled = enabled
        if (!enabled) {
            keyRepeater.stop()
            clearCtrlArmed()
        }
    }

    private fun sendRaw(raw: String) {
        val sessionId = viewModel.uiState.value.activeSessionId ?: return
        viewModel.send(sessionId, raw)
        // Ctrl is a one-shot modifier. Extra-key buttons already encode their
        // own escape/control sequence, so they must consume the armed state as
        // surely as a hardware or IME key does.
        if (ctrlArmed) clearCtrlArmed()
    }

    private fun sendTerminalKey(keyCode: Int) {
        val b = _binding ?: return
        if (!b.terminalView.isEnabled) return
        // Reuse Termux's key encoder so cursor/Home/End keys switch between
        // CSI and SS3 when the active remote application toggles DECCKM.
        val keyModifiers = if (ctrlArmed) KeyHandler.KEYMOD_CTRL else 0
        b.terminalView.handleKeyCode(keyCode, keyModifiers)
        if (ctrlArmed) clearCtrlArmed()
    }

    private fun pasteClipboard() {
        val sessionId = viewModel.uiState.value.activeSessionId ?: return
        runCatching {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = clipboard.primaryClip ?: return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0).coerceToText(requireContext())?.toString().orEmpty()
            if (text.isNotEmpty()) viewModel.paste(sessionId, text)
        }.onFailure {
            android.util.Log.w("TerminalFragment", "Unable to read clipboard")
            context?.let { ctx ->
                Toast.makeText(ctx, it.message ?: "无法读取剪贴板", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderCtrlState() {
        _binding?.btnCtrl?.isSelected = ctrlArmed
        _binding?.btnCtrl?.alpha = if (ctrlArmed) 1f else 0.75f
    }

    private fun clearCtrlAfterCurrentEvent() {
        val b = _binding ?: return
        b.terminalView.post {
            if (_binding === b) clearCtrlArmed()
        }
    }

    private fun clearCtrlArmed() {
        ctrlArmed = false
        renderCtrlState()
    }

    private fun focusTerminalInput() {
        val b = _binding ?: return
        if (!b.terminalView.isEnabled) return
        b.terminalView.requestFocus()
        focusRunnable?.let(b.terminalView::removeCallbacks)
        val runnable = Runnable {
            if (_binding !== b || !isAdded || !b.terminalView.isEnabled) return@Runnable
            val imm = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                ?: return@Runnable
            imm.showSoftInput(b.terminalView, InputMethodManager.SHOW_IMPLICIT)
        }
        focusRunnable = runnable
        b.terminalView.postDelayed(runnable, 100)
    }

    private fun setupImeResizeListener() {
        val root = binding.root
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            // Run for both IME show and hide; remote TerminalSession.updateSize
            // only resizes the emulator and never touches an invalid local fd.
            val b = _binding
            b?.terminalView?.post {
                if (_binding === b) b.terminalView.updateSize()
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun leaveScreen() {
        keyRepeater.stop()
        if (isLeavingScreen) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.terminalFragment) return
        isLeavingScreen = true
        val navigated = runCatching { navController.navigateUp() }
            .onFailure {
                android.util.Log.w("TerminalFragment", "Navigation was rejected")
            }
            .getOrDefault(false)
        if (navigated) {
            // Navigation commits asynchronously. Cancel a still-suspended Room
            // lookup now so it cannot create a new activity-owned SSH session
            // in the short window before this Fragment view is destroyed.
            entryHostJob?.cancel()
            hostPickerJob?.cancel()
        } else {
            isLeavingScreen = false
        }
    }

    override fun onPause() {
        keyRepeater.stop()
        terminalMenuPopup?.dismiss()
        super.onPause()
    }

    override fun onDestroyView() {
        keyRepeater.stop()
        terminalMenuPopup?.dismiss()
        terminalMenuPopup = null
        val b = _binding
        b?.root?.viewTreeObserver?.takeIf { it.isAlive }
            ?.removeOnWindowFocusChangeListener(windowFocusListener)
        b?.terminalView?.invalidateInputConnection()
        clearCtrlArmed()
        focusRunnable?.let { runnable -> b?.terminalView?.removeCallbacks(runnable) }
        focusRunnable = null
        entryHostJob = null
        hostPickerJob = null
        unbindScreenObservers()
        hostPickerDialog?.dismiss()
        hostPickerDialog = null
        hostPickerLoading = false
        dismissHostKeyDialog()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root, null)
        attachedSessionId = null
        lastRenderedActiveSessionId = null
        lastRenderedConnectionState = null
        screenRefreshPending = false
        renderedTabKeys = emptyList()
        _binding = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        isOpeningFileTransfer = false
    }

    private companion object {
        const val STATE_ENTRY_HOST_REQUEST_CONSUMED = "entry_host_request_consumed"
        const val STATE_TERMINAL_FONT_SIZE = "terminal_font_size"
    }
}
