package com.sshtool.ui.terminal

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
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
        setupConnectionButtons()
        setupBackPressHandler()
        setupTabLayout()
        setupImeResizeListener()
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
        binding.toolbar.setNavigationOnClickListener { leaveScreen() }
        binding.toolbar.title = getString(R.string.app_name)
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
        binding.btnUp.setOnClickListener { sendTerminalKey(KeyEvent.KEYCODE_DPAD_UP) }
        binding.btnDown.setOnClickListener { sendTerminalKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        binding.btnRight.setOnClickListener { sendTerminalKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
        binding.btnLeft.setOnClickListener { sendTerminalKey(KeyEvent.KEYCODE_DPAD_LEFT) }
        binding.btnPgup.setOnClickListener { sendTerminalKey(KeyEvent.KEYCODE_PAGE_UP) }
        binding.btnPgdn.setOnClickListener { sendTerminalKey(KeyEvent.KEYCODE_PAGE_DOWN) }
        binding.btnHome.setOnClickListener { sendTerminalKey(KeyEvent.KEYCODE_MOVE_HOME) }
        binding.btnEnd.setOnClickListener { sendTerminalKey(KeyEvent.KEYCODE_MOVE_END) }
    }

    private fun setupConnectionButtons() {
        binding.btnFileTransfer.setOnClickListener { openFileTransfer() }
        binding.btnConnect.setOnClickListener {
            viewModel.uiState.value.activeSessionId?.let(viewModel::reconnect)
        }
        binding.btnDisconnect.setOnClickListener {
            viewModel.uiState.value.activeSessionId?.let(viewModel::disconnect)
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
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (renderingTabs) return
                val tag = tab.tag as? String ?: return
                if (tag == ADD_TAB_TAG) {
                    reselectActiveTab()
                    showHostPickerDialog()
                } else {
                    // Attach synchronously before publishing the new active id.
                    // Otherwise a physical-key or late IME callback delivered
                    // before the StateFlow collector runs can target the old
                    // TerminalSession while the UI already shows the new tab.
                    viewModel.uiState.value.sessions
                        .firstOrNull { it.sessionId == tag }
                        ?.let(::attachTerminalSession)
                    viewModel.selectSession(tag)
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) {
                if (!renderingTabs && tab.tag == ADD_TAB_TAG) showHostPickerDialog()
            }
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
        rebuildTabsIfNeeded(state.sessions)

        val active = state.sessions.firstOrNull { it.sessionId == state.activeSessionId }
        if (active == null) {
            if (attachedSessionId != null) b.terminalView.invalidateInputConnection()
            attachedSessionId = null
            lastRenderedActiveSessionId = null
            lastRenderedConnectionState = null
            setTerminalInputEnabled(false)
            b.toolbar.title = getString(R.string.app_name)
            b.tvStatus.text = getString(R.string.status_disconnected)
            b.btnFileTransfer.isEnabled = false
            b.btnFileTransfer.text = getString(R.string.action_file_transfer)
            return
        }

        selectTab(active.sessionId)
        attachTerminalSession(active)
        b.toolbar.title = active.host.name
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

    private fun rebuildTabsIfNeeded(sessions: List<TerminalTabState>) {
        val keys = sessions.map { it.sessionId to it.host.name }
        if (keys == renderedTabKeys) return
        renderedTabKeys = keys
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
                    false
                )
            }
            binding.tabLayout.addTab(
                binding.tabLayout.newTab().setText("+").setTag(ADD_TAB_TAG),
                sessions.isEmpty()
            )
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

    private fun reselectActiveTab() {
        viewModel.uiState.value.activeSessionId?.let(::selectTab)
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
        }
    }

    private fun applyConnectionState(
        session: TerminalTabState,
        shouldActivateConnected: Boolean
    ) {
        val b = _binding ?: return
        val state = session.connectionState
        b.btnFileTransfer.isEnabled = state is SSHConnectionState.Connected
        b.btnFileTransfer.text = when (val transfer = session.fileTransferState) {
            is FileTransferState.Running -> {
                val total = transfer.totalBytes
                if (total != null && total > 0) {
                    val percent = ((transfer.transferredBytes.toDouble() / total) * 100)
                        .toInt()
                        .coerceIn(0, 100)
                    "${getString(R.string.action_file_transfer)} $percent%"
                } else {
                    "${getString(R.string.action_file_transfer)}…"
                }
            }
            else -> getString(R.string.action_file_transfer)
        }
        if (state !is SSHConnectionState.Error) lastErrorToastKey = null
        if (state !is SSHConnectionState.HostKeyConfirmationRequired &&
            state !is SSHConnectionState.HostKeyChanged) {
            dismissHostKeyDialog()
        }
        when (state) {
            SSHConnectionState.Connecting -> {
                setToolbarVisible(true)
                b.tvStatus.text = getString(R.string.status_connecting)
                b.btnConnect.isEnabled = false
                b.btnDisconnect.isEnabled = true
                b.statusIndicator.setBackgroundResource(R.drawable.status_connecting)
                setTerminalInputEnabled(false)
            }
            SSHConnectionState.Connected -> {
                setToolbarVisible(false)
                b.tvStatus.text = connectedStatusText(session)
                b.btnConnect.isEnabled = false
                b.btnDisconnect.isEnabled = true
                b.statusIndicator.setBackgroundResource(R.drawable.status_connected)
                setTerminalInputEnabled(true)
                if (shouldActivateConnected) focusTerminalInput()
            }
            is SSHConnectionState.HostKeyConfirmationRequired -> {
                setToolbarVisible(true)
                b.tvStatus.text = getString(R.string.status_error, state.fingerprint)
                b.btnConnect.isEnabled = false
                b.btnDisconnect.isEnabled = true
                b.statusIndicator.setBackgroundResource(R.drawable.status_error)
                setTerminalInputEnabled(false)
                showHostKeyDialog(session.sessionId, state)
            }
            is SSHConnectionState.HostKeyChanged -> {
                setToolbarVisible(true)
                b.tvStatus.text = getString(R.string.status_error, state.fingerprint)
                b.btnConnect.isEnabled = false
                b.btnDisconnect.isEnabled = true
                b.statusIndicator.setBackgroundResource(R.drawable.status_error)
                setTerminalInputEnabled(false)
                showHostKeyDialog(session.sessionId, state)
            }
            is SSHConnectionState.Error -> {
                setToolbarVisible(true)
                b.tvStatus.text = getString(R.string.status_error, state.message)
                b.btnConnect.isEnabled = true
                b.btnDisconnect.isEnabled = false
                b.statusIndicator.setBackgroundResource(R.drawable.status_error)
                setTerminalInputEnabled(false)
                val key = "${session.sessionId}|${state.message}"
                if (lastErrorToastKey != key) {
                    lastErrorToastKey = key
                    Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
                }
            }
            SSHConnectionState.Disconnected -> {
                setToolbarVisible(true)
                b.tvStatus.text = getString(R.string.status_disconnected)
                b.btnConnect.isEnabled = true
                b.btnDisconnect.isEnabled = false
                b.statusIndicator.setBackgroundResource(R.drawable.status_disconnected)
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

    private fun setToolbarVisible(visible: Boolean) {
        _binding?.toolbar?.visibility = if (visible) View.VISIBLE else View.GONE
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
        if (!enabled) clearCtrlArmed()
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

    override fun onDestroyView() {
        val b = _binding
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
        const val ADD_TAB_TAG = "+"
        const val STATE_ENTRY_HOST_REQUEST_CONSUMED = "entry_host_request_consumed"
        const val STATE_TERMINAL_FONT_SIZE = "terminal_font_size"
    }
}
