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
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.material.tabs.TabLayout
import com.sshtool.R
import com.sshtool.SSHToolApp
import com.sshtool.databinding.FragmentTerminalBinding
import com.sshtool.ssh.SSHConnectionState
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A view-only facade over activity-owned SSH/terminal sessions. */
class TerminalFragment : Fragment() {

    private var _binding: FragmentTerminalBinding? = null
    private val binding get() = _binding!!

    private val args: TerminalFragmentArgs by navArgs()
    private val viewModel: TerminalViewModel by activityViewModels {
        TerminalViewModelFactory(SSHToolApp.instance.hostRepository)
    }

    private var isLeavingScreen = false
    private var ctrlArmed = false
    private var terminalFontSize = 14f
    private var renderingTabs = false
    private var renderedTabKeys: List<Pair<String, String>> = emptyList()
    private var attachedSessionId: String? = null
    private val observedBridges = mutableMapOf<String, SshTerminalSession>()
    private var hostKeyDialog: AlertDialog? = null
    private var hostKeyDialogKey: String? = null
    private var hostPickerDialog: AlertDialog? = null
    private var hostPickerLoading = false
    private var lastErrorToastKey: String? = null
    private var focusRunnable: Runnable? = null

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

        viewLifecycleOwner.lifecycleScope.launch {
            val target = try {
                when {
                    args.hostId != -1L -> viewModel.ensureSessionForHost(args.hostId)
                    viewModel.uiState.value.sessions.isNotEmpty() -> {
                        viewModel.uiState.value.activeSessionId
                            ?: viewModel.uiState.value.sessions.first().sessionId.also(viewModel::selectSession)
                    }
                    else -> null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showHostLoadFailure(e)
                if (viewModel.uiState.value.sessions.isEmpty() && _binding != null) {
                    findNavController().navigateUp()
                }
                return@launch
            }
            if (target == null && viewModel.uiState.value.sessions.isEmpty() && _binding != null) {
                Toast.makeText(requireContext(), R.string.host_not_found, Toast.LENGTH_SHORT).show()
                findNavController().navigateUp()
            }
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { leaveScreen() }
        binding.toolbar.title = getString(R.string.app_name)
    }

    private fun setupTerminalView() {
        binding.terminalView.setTextSize(terminalFontSize.toInt())
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
                viewModel.uiState.value.activeSessionId?.let {
                    viewModel.resize(it, columns, rows)
                }
            }

            override fun logError(tag: String, message: String) { android.util.Log.e(tag, message) }
            override fun logWarn(tag: String, message: String) { android.util.Log.w(tag, message) }
            override fun logInfo(tag: String, message: String) { android.util.Log.i(tag, message) }
            override fun logDebug(tag: String, message: String) { android.util.Log.d(tag, message) }
            override fun logVerbose(tag: String, message: String) { android.util.Log.v(tag, message) }
            override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
                android.util.Log.e(tag, message, e)
            }
            override fun logStackTrace(tag: String, e: Exception) {
                android.util.Log.e(tag, e.toString(), e)
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
        binding.btnUp.setOnClickListener { sendRaw("\u001B[A") }
        binding.btnDown.setOnClickListener { sendRaw("\u001B[B") }
        binding.btnRight.setOnClickListener { sendRaw("\u001B[C") }
        binding.btnLeft.setOnClickListener { sendRaw("\u001B[D") }
        binding.btnPgup.setOnClickListener { sendRaw("\u001B[5~") }
        binding.btnPgdn.setOnClickListener { sendRaw("\u001B[6~") }
        binding.btnHome.setOnClickListener { sendRaw("\u001B[H") }
        binding.btnEnd.setOnClickListener { sendRaw("\u001B[F") }
    }

    private fun setupConnectionButtons() {
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
                viewModel.uiState.collectLatest(::render)
            }
        }
    }

    private fun render(state: TerminalUiState) {
        val b = _binding ?: return
        bindScreenObservers(state.sessions)
        rebuildTabsIfNeeded(state.sessions)

        val active = state.sessions.firstOrNull { it.sessionId == state.activeSessionId }
        if (active == null) {
            attachedSessionId = null
            setTerminalInputEnabled(false)
            b.toolbar.title = getString(R.string.app_name)
            b.tvStatus.text = getString(R.string.status_disconnected)
            return
        }

        selectTab(active.sessionId)
        if (attachedSessionId != active.sessionId) {
            val changed = b.terminalView.attachSession(active.terminalSession)
            attachedSessionId = active.sessionId
            if (changed) {
                // attachSession invalidates the old IME generation. Ask Android
                // for a fresh InputConnection immediately; any late callback
                // from the old one is rejected by TerminalView.
                val imm = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.restartInput(b.terminalView)
            }
            b.terminalView.onScreenUpdated()
        }
        b.toolbar.title = active.host.name
        updateTabIndicators(state.sessions)
        applyConnectionState(active)
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

    private fun applyConnectionState(session: TerminalTabState) {
        val b = _binding ?: return
        val state = session.connectionState
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
                b.tvStatus.text = getString(R.string.status_connected)
                b.btnConnect.isEnabled = false
                b.btnDisconnect.isEnabled = true
                b.statusIndicator.setBackgroundResource(R.drawable.status_connected)
                setTerminalInputEnabled(true)
                focusTerminalInput()
                syncPtySize(session)
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

    private fun showHostPickerDialog() {
        if (hostPickerLoading || hostPickerDialog?.isShowing == true) return
        hostPickerLoading = true
        viewLifecycleOwner.lifecycleScope.launch {
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
                        viewLifecycleOwner.lifecycleScope.launch {
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

    private fun pasteClipboard() {
        val sessionId = viewModel.uiState.value.activeSessionId ?: return
        runCatching {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = clipboard.primaryClip ?: return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0).coerceToText(requireContext())?.toString().orEmpty()
            if (text.isNotEmpty()) viewModel.paste(sessionId, text)
        }.onFailure {
            android.util.Log.w("TerminalFragment", "Unable to read clipboard", it)
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

    private fun syncPtySize(session: TerminalTabState) {
        val emulator = session.terminalSession.emulator ?: return
        viewModel.resize(session.sessionId, emulator.mColumns, emulator.mRows)
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
        isLeavingScreen = true
        findNavController().navigateUp()
    }

    override fun onDestroyView() {
        val b = _binding
        clearCtrlArmed()
        focusRunnable?.let { runnable -> b?.terminalView?.removeCallbacks(runnable) }
        focusRunnable = null
        observedBridges.values.forEach { it.setScreenUpdater(null) }
        observedBridges.clear()
        hostPickerDialog?.dismiss()
        hostPickerDialog = null
        hostPickerLoading = false
        dismissHostKeyDialog()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root, null)
        attachedSessionId = null
        renderedTabKeys = emptyList()
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val ADD_TAB_TAG = "+"
    }
}
