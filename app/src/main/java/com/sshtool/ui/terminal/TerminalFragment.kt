package com.sshtool.ui.terminal

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.material.tabs.TabLayout
import com.sshtool.R
import com.sshtool.SSHToolApp
import com.sshtool.data.model.Host
import com.sshtool.databinding.FragmentTerminalBinding
import com.sshtool.ssh.SSHConnectionListener
import com.sshtool.ssh.SSHConnectionManager
import com.sshtool.ssh.SSHConnectionState
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class TerminalFragment : Fragment(), TerminalInputView.Callback {

    private var _binding: FragmentTerminalBinding? = null
    private val binding get() = _binding!!
    private var isLeavingScreen = false

    private val args: TerminalFragmentArgs by navArgs()
    private val viewModel: TerminalViewModel by viewModels {
        TerminalViewModelFactory(SSHToolApp.instance.hostRepository)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var ctrlArmed = false
    private var terminalFontSize = 14f

    private data class SessionState(
        var sessionId: String,
        var host: Host,
        var bridge: SshTerminalSession,
        var terminalSession: TerminalSession,
        var connectionState: SSHConnectionState = SSHConnectionState.Disconnected
    )

    private val sessionStates = mutableListOf<SessionState>()
    private var activeIndex = -1

    private val manager get() = SSHConnectionManager.instance

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
        setupToolbar()
        setupTerminalView()
        setupTerminalInput()
        setupConnectionStatus()
        setupBackPressHandler()
        setupTabLayout()

        val initialHostId = args.hostId
        if (initialHostId != -1L) {
            addSession(initialHostId)
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { leaveScreen() }
        binding.toolbar.title = getString(R.string.app_name)
        setToolbarVisible(true)
    }

    private fun setToolbarVisible(isVisible: Boolean) {
        binding.toolbar.visibility = if (isVisible) View.VISIBLE else View.GONE
    }

    private fun setupTerminalView() {
        binding.terminalView.setTextSize(terminalFontSize.toInt())
        binding.terminalView.setTerminalViewClient(object : TerminalViewClient {
            override fun onScale(scale: Float): Float {
                val currentBinding = _binding ?: return terminalFontSize / 14f
                val newSize = (14f * scale).coerceIn(8f, 32f)
                if (kotlin.math.abs(newSize - terminalFontSize) >= 0.1f) {
                    terminalFontSize = newSize
                    currentBinding.terminalView.setTextSize(terminalFontSize.toInt())
                    currentBinding.terminalView.onScreenUpdated()
                }
                return terminalFontSize / 14f
            }
            override fun onSingleTapUp(e: MotionEvent) {
                if (_binding == null) return
                focusTerminalInput()
            }
            override fun shouldBackButtonBeMappedToEscape(): Boolean = false
            override fun shouldEnforceCharBasedInput(): Boolean = false
            override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
            override fun isTerminalViewSelected(): Boolean = true
            override fun copyModeChanged(copyMode: Boolean) = Unit
            override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
            override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
            override fun onLongPress(event: MotionEvent): Boolean = false
            override fun readControlKey(): Boolean = ctrlArmed
            override fun readAltKey(): Boolean = false
            override fun readShiftKey(): Boolean = false
            override fun readFnKey(): Boolean = false
            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
            override fun onEmulatorSet() = Unit
            override fun onTerminalSizeChanged(columns: Int, rows: Int) {
                manager.updatePtySize(columns, rows)
            }
            override fun logError(tag: String, message: String) { android.util.Log.e(tag, message) }
            override fun logWarn(tag: String, message: String) { android.util.Log.w(tag, message) }
            override fun logInfo(tag: String, message: String) { android.util.Log.i(tag, message) }
            override fun logDebug(tag: String, message: String) { android.util.Log.d(tag, message) }
            override fun logVerbose(tag: String, message: String) { android.util.Log.v(tag, message) }
            override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { android.util.Log.e(tag, message, e) }
            override fun logStackTrace(tag: String, e: Exception) { android.util.Log.e(tag, e.toString()) }
        })
    }

    private fun setupTerminalInput() {
        binding.etInput.callback = this

        binding.btnCtrl.setOnClickListener {
            ctrlArmed = !ctrlArmed
            binding.btnCtrl.isSelected = ctrlArmed
            binding.btnCtrl.alpha = if (ctrlArmed) 1.0f else 0.75f
        }
        binding.btnTab.setOnClickListener { sendRawToTerminal("\t") }
        binding.btnEsc.setOnClickListener { sendRawToTerminal("\u001B") }
        binding.btnPaste.setOnClickListener { pasteClipboard() }
        binding.btnUp.setOnClickListener { sendRawToTerminal("\u001B[A") }
        binding.btnDown.setOnClickListener { sendRawToTerminal("\u001B[B") }
        binding.btnRight.setOnClickListener { sendRawToTerminal("\u001B[C") }
        binding.btnLeft.setOnClickListener { sendRawToTerminal("\u001B[D") }
        binding.btnPgup.setOnClickListener { sendRawToTerminal("\u001B[5~") }
        binding.btnPgdn.setOnClickListener { sendRawToTerminal("\u001B[6~") }
        binding.btnHome.setOnClickListener { sendRawToTerminal("\u001B[H") }
        binding.btnEnd.setOnClickListener { sendRawToTerminal("\u001B[F") }
    }

    private fun setupConnectionStatus() {
        binding.btnConnect.setOnClickListener {
            val state = getActiveState()
            if (state != null && manager.isSessionConnected(state.sessionId)) {
                manager.disconnect(state.sessionId)
            } else {
                reconnectActive()
            }
        }
        binding.btnDisconnect.setOnClickListener {
            getActiveState()?.let { manager.disconnect(it.sessionId) }
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
        ensureAddTab()
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (tab.tag == "+") {
                    binding.tabLayout.selectTab(null)
                    showHostPickerDialog()
                    return
                }
                val index = tab.position
                if (index < sessionStates.size) {
                    switchToSession(index)
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun showHostPickerDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val hosts = SSHToolApp.instance.hostRepository.getAllHosts().first()
            if (hosts.isEmpty()) {
                Toast.makeText(requireContext(), "没有可用的主机，请先添加", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val names = hosts.map { "${it.name}  (${it.host}:${it.port})" }.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle("选择主机")
                .setItems(names) { _, which ->
                    addSession(hosts[which].id)
                }
                .show()
        }
    }

    private fun addTabFor(host: Host, sessionId: String) {
        val displayName = dedupHostName(host.name)
        val tab = binding.tabLayout.newTab()
            .setText(displayName)
            .setTag(sessionId)
        // Insert before the "+" tab (always last)
        val insertPos = binding.tabLayout.tabCount - 1
        binding.tabLayout.addTab(tab, insertPos.coerceAtLeast(0))
        ensureAddTab()
    }

    private fun dedupHostName(name: String): String {
        val existing = (0 until binding.tabLayout.tabCount)
            .mapNotNull { binding.tabLayout.getTabAt(it)?.text?.toString() }
            .filter { it == name || it.startsWith("$name (") }
        if (existing.isEmpty()) return name
        var counter = 2
        while ("$name ($counter)" in existing) {
            counter++
        }
        return "$name ($counter)"
    }

    private fun ensureAddTab() {
        val addTabIndex = binding.tabLayout.tabCount - 1
        val lastTab = if (addTabIndex >= 0) binding.tabLayout.getTabAt(addTabIndex) else null
        if (lastTab?.tag == "+") {
            binding.tabLayout.removeTab(lastTab)
        }
        val addTab = binding.tabLayout.newTab()
            .setText("+")
            .setTag("+")
        binding.tabLayout.addTab(addTab)
        if (sessionStates.isEmpty()) {
            addTab.select()
        }
    }

    private fun addSession(hostId: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            val host = viewModel.getHost(hostId)
            if (host == null) {
                Toast.makeText(requireContext(), R.string.host_not_found, Toast.LENGTH_SHORT).show()
                if (sessionStates.isEmpty()) findNavController().navigateUp()
                return@launch
            }
            connectNewSession(host)
        }
    }

    private fun connectNewSession(host: Host) {
        val bridge = SshTerminalSession(
            context = requireContext().applicationContext,
            outputWriter = { data -> sendRawToTerminal(data) },
            screenUpdater = {
                val currentBinding = _binding ?: return@SshTerminalSession
                if (!isAdded) return@SshTerminalSession
                currentBinding.terminalView.onScreenUpdated()
            }
        )
        val terminalSession = bridge.create()

        // Register session state BEFORE connecting, so state callbacks can find it
        val s = SessionState(
            sessionId = "",
            host = host,
            bridge = bridge,
            terminalSession = terminalSession,
            connectionState = SSHConnectionState.Connecting
        )
        val idx = sessionStates.size
        sessionStates.add(s)
        addTabFor(host, "")
        activeIndex = idx
        binding.tabLayout.post {
            binding.tabLayout.getTabAt(idx)?.select()
        }
        binding.terminalView.attachSession(s.terminalSession)
        binding.toolbar.title = host.name
        applyConnectionState(SSHConnectionState.Connecting)

        val listener = object : SSHConnectionListener {
            override fun onStateChanged(state: SSHConnectionState) {
                handler.post {
                    if (!isAdded || _binding == null) return@post
                    s.connectionState = state
                    updateTabIndicator(idx, state)
                    if (idx == activeIndex) {
                        applyConnectionState(state)
                    }
                }
            }

            override fun onOutput(data: ByteArray) {
                handler.post {
                    if (!isAdded || _binding == null) return@post
                    bridge.appendOutput(data)
                }
            }

            override fun onDisconnected() {
                handler.post {
                    if (!isAdded || _binding == null || isLeavingScreen) return@post
                    s.connectionState = SSHConnectionState.Disconnected
                    updateTabIndicator(idx, SSHConnectionState.Disconnected)
                    if (idx == activeIndex) {
                        applyConnectionState(SSHConnectionState.Disconnected)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val sessionId = manager.connect(host, SSHToolApp.instance.hostRepository, listener)
            s.sessionId = sessionId
            // Update tab tag
            val tab = binding.tabLayout.getTabAt(idx)
            tab?.tag = sessionId
        }
    }

    private fun reconnectActive() {
        val state = getActiveState() ?: return
        // Clean up old terminal session
        state.terminalSession.finishIfRunning()
        state.connectionState = SSHConnectionState.Connecting
        applyConnectionState(SSHConnectionState.Connecting)

        // Create fresh bridge + terminal session for the existing state
        val newBridge = SshTerminalSession(
            context = requireContext().applicationContext,
            outputWriter = { data -> sendRawToTerminal(data) },
            screenUpdater = {
                val currentBinding = _binding ?: return@SshTerminalSession
                if (!isAdded) return@SshTerminalSession
                currentBinding.terminalView.onScreenUpdated()
            }
        )
        val newTermSession = newBridge.create()

        // Re-register the session in the manager
        viewLifecycleOwner.lifecycleScope.launch {
            val host = viewModel.getHost(state.host.id) ?: return@launch
            // Remove old connection from manager
            if (state.sessionId.isNotEmpty()) {
                manager.disconnect(state.sessionId)
            }
            // Update state with new bridge/session but same index/tab
            val idx = sessionStates.indexOf(state)
            if (idx < 0) return@launch
            
            val listener = object : SSHConnectionListener {
                override fun onStateChanged(connState: SSHConnectionState) {
                    handler.post {
                        if (!isAdded || _binding == null) return@post
                        state.connectionState = connState
                        updateTabIndicator(idx, connState)
                        if (idx == activeIndex) {
                            applyConnectionState(connState)
                        }
                    }
                }
                override fun onOutput(data: ByteArray) {
                    handler.post {
                        if (!isAdded || _binding == null) return@post
                        newBridge.appendOutput(data)
                    }
                }
                override fun onDisconnected() {
                    handler.post {
                        if (!isAdded || _binding == null || isLeavingScreen) return@post
                        state.connectionState = SSHConnectionState.Disconnected
                        updateTabIndicator(idx, SSHConnectionState.Disconnected)
                        if (idx == activeIndex) {
                            applyConnectionState(SSHConnectionState.Disconnected)
                        }
                    }
                }
            }

            val sessionId = manager.connect(host, SSHToolApp.instance.hostRepository, listener)
            // Update the existing state object in-place so listener's captured reference stays valid
            state.sessionId = sessionId
            state.bridge = newBridge
            state.terminalSession = newTermSession
            state.connectionState = SSHConnectionState.Connecting
            binding.terminalView.attachSession(newTermSession)
            val tab = binding.tabLayout.getTabAt(idx)
            tab?.tag = sessionId
        }
    }

    private fun getActiveState(): SessionState? {
        if (activeIndex < 0 || activeIndex >= sessionStates.size) return null
        return sessionStates[activeIndex]
    }

    private fun switchToSession(index: Int) {
        if (index < 0 || index >= sessionStates.size) return
        activeIndex = index
        val state = sessionStates[index]
        manager.switchSession(state.sessionId)
        binding.terminalView.attachSession(state.terminalSession)
        binding.toolbar.title = state.host.name
        // Sync with actual connection state in case listener callback was missed
        if (state.connectionState !is SSHConnectionState.Connected
            && state.sessionId.isNotEmpty()
            && manager.isSessionConnected(state.sessionId)) {
            state.connectionState = SSHConnectionState.Connected
        }
        applyConnectionState(state.connectionState)
        if (state.connectionState is SSHConnectionState.Connected) {
            focusTerminalInput()
        }
    }

    private fun applyConnectionState(state: SSHConnectionState) {
        val b = _binding ?: return
        when (state) {
            is SSHConnectionState.Connecting -> {
                setToolbarVisible(true)
                b.tvStatus.text = getString(R.string.status_connecting)
                b.btnConnect.isEnabled = false
                b.btnDisconnect.isEnabled = false
                b.statusIndicator.setBackgroundResource(R.drawable.status_connecting)
            }
            is SSHConnectionState.Connected -> {
                setToolbarVisible(false)
                b.tvStatus.text = getString(R.string.status_connected)
                b.btnConnect.isEnabled = false
                b.btnDisconnect.isEnabled = true
                setTerminalInputEnabled(true)
                b.statusIndicator.setBackgroundResource(R.drawable.status_connected)
                clearInputField()
                focusTerminalInput()
            }
            is SSHConnectionState.HostKeyConfirmationRequired -> {
                setToolbarVisible(true)
                b.tvStatus.text = getString(R.string.status_error, state.fingerprint)
                b.btnConnect.isEnabled = true
                b.btnDisconnect.isEnabled = false
                setTerminalInputEnabled(false)
                b.statusIndicator.setBackgroundResource(R.drawable.status_error)
                showHostKeyDialog(state)
            }
            is SSHConnectionState.HostKeyChanged -> {
                setToolbarVisible(true)
                b.tvStatus.text = getString(R.string.status_error, state.fingerprint)
                b.btnConnect.isEnabled = false
                b.btnDisconnect.isEnabled = false
                setTerminalInputEnabled(false)
                b.statusIndicator.setBackgroundResource(R.drawable.status_error)
                showHostKeyDialog(state)
            }
            is SSHConnectionState.Error -> {
                setToolbarVisible(true)
                b.tvStatus.text = getString(R.string.status_error, state.message)
                b.btnConnect.isEnabled = true
                b.btnDisconnect.isEnabled = false
                setTerminalInputEnabled(false)
                b.statusIndicator.setBackgroundResource(R.drawable.status_error)
                Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
            }
            is SSHConnectionState.Disconnected -> {
                setToolbarVisible(true)
                b.tvStatus.text = getString(R.string.status_disconnected)
                b.btnConnect.isEnabled = true
                b.btnDisconnect.isEnabled = false
                setTerminalInputEnabled(false)
                b.statusIndicator.setBackgroundResource(R.drawable.status_disconnected)
            }
        }
    }

    private fun updateTabIndicator(index: Int, state: SSHConnectionState) {
        val tab = binding.tabLayout.getTabAt(index) ?: return
        val colorRes = when (state) {
            is SSHConnectionState.Connected -> R.drawable.status_connected
            is SSHConnectionState.Connecting -> R.drawable.status_connecting
            is SSHConnectionState.Error,
            is SSHConnectionState.HostKeyConfirmationRequired,
            is SSHConnectionState.HostKeyChanged -> R.drawable.status_error
            is SSHConnectionState.Disconnected -> R.drawable.status_disconnected
        }
        val drawable = AppCompatResources.getDrawable(requireContext(), colorRes)
        tab.setIcon(drawable)
    }

    private fun showHostKeyDialog(state: SSHConnectionState.HostKeyConfirmationRequired) {
        if (!isAdded) return
        val activeState = getActiveState() ?: return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.host_key_confirm_title)
            .setMessage(getString(R.string.host_key_confirm_message, state.host, state.port, state.algorithm, state.fingerprint))
            .setNegativeButton(R.string.cancel) { _, _ ->
                Toast.makeText(requireContext(), R.string.host_key_rejected, Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton(R.string.trust_and_connect) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    manager.trustAndReconnect(activeState.sessionId, SSHToolApp.instance.hostRepository)
                }
            }
            .setCancelable(false)
            .show()
    }

    private fun showHostKeyDialog(state: SSHConnectionState.HostKeyChanged) {
        if (!isAdded) return
        val activeState = getActiveState() ?: return
        AlertDialog.Builder(requireContext())
            .setIcon(android.R.drawable.stat_sys_warning)
            .setTitle(R.string.host_key_changed_title)
            .setMessage(getString(R.string.host_key_changed_message, state.host, state.port, state.algorithm, state.fingerprint))
            .setNegativeButton(R.string.cancel) { _, _ ->
                Toast.makeText(requireContext(), R.string.host_key_rejected, Toast.LENGTH_SHORT).show()
            }
            // A changed key is re-pinned only via the same trust-and-reconnect
            // path; the repo re-verifies the key on the fresh handshake.
            .setPositiveButton(R.string.trust_and_connect) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    manager.trustAndReconnect(activeState.sessionId, SSHToolApp.instance.hostRepository)
                }
            }
            .setCancelable(false)
            .show()
    }

    private fun setTerminalInputEnabled(enabled: Boolean) {
        binding.etInput.isEnabled = enabled
        binding.btnCtrl.isEnabled = enabled
        binding.btnTab.isEnabled = enabled
        binding.btnEsc.isEnabled = enabled
        binding.btnPaste.isEnabled = enabled
        binding.btnUp.isEnabled = enabled
        binding.btnDown.isEnabled = enabled
        binding.btnLeft.isEnabled = enabled
        binding.btnRight.isEnabled = enabled
        binding.btnPgup.isEnabled = enabled
        binding.btnPgdn.isEnabled = enabled
        binding.btnHome.isEnabled = enabled
        binding.btnEnd.isEnabled = enabled
        if (!enabled) {
            ctrlArmed = false
            binding.btnCtrl.isSelected = false
            binding.btnCtrl.alpha = 0.75f
            clearInputField()
        }
    }

    private fun sendTextToTerminal(text: String) {
        if (!manager.isActiveConnected()) return
        val normalizedText = text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\n', '\r')
        val payload = if (ctrlArmed && normalizedText.isNotEmpty()) {
            ctrlArmed = false
            binding.btnCtrl.isSelected = false
            binding.btnCtrl.alpha = 0.75f
            normalizedText.map { ch ->
                val asciiCode = ch.code and 0x7F
                if (asciiCode in 64..95 || asciiCode in 97..122) (asciiCode and 0x1F).toChar() else ch
            }.joinToString(separator = "")
        } else normalizedText
        sendRawToTerminal(payload)
    }

    private fun sendRawToTerminal(raw: String) {
        if (!isAdded || _binding == null || !manager.isActiveConnected()) return
        manager.activeConnection?.send(raw)
    }

    private fun clearInputField() {
        _binding?.etInput?.clearInternalText()
    }

    private fun focusTerminalInput() {
        val b = _binding ?: return
        if (!b.etInput.isEnabled) return
        b.etInput.requestFocus()
        b.etInput.postDelayed({
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(b.etInput, InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    private fun pasteClipboard() {
        if (!isAdded || _binding == null || !manager.isActiveConnected()) return
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(requireContext())?.toString().orEmpty()
        if (text.isNotEmpty()) sendTextToTerminal(text)
    }

    override fun onTextInput(text: String) {
        sendTextToTerminal(text)
        clearInputField()
    }

    override fun onEnter() {
        sendTextToTerminal("\n")
        clearInputField()
    }

    override fun onBackspace() {
        sendRawToTerminal("\u007F")
    }

    private fun leaveScreen() {
        if (isLeavingScreen) return
        isLeavingScreen = true
        manager.disconnectAll()
        findNavController().navigateUp()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        sessionStates.forEach { it.terminalSession.finishIfRunning() }
        sessionStates.clear()
        _binding = null
    }
}
