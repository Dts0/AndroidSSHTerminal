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
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.sshtool.R
import com.sshtool.SSHToolApp
import com.sshtool.databinding.FragmentTerminalBinding
import com.sshtool.ssh.SSHConnectionListener
import com.sshtool.ssh.SSHConnectionManager
import com.sshtool.ssh.SSHConnectionState
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.launch

class TerminalFragment : Fragment(), SSHConnectionListener, TerminalInputView.Callback {

    private var _binding: FragmentTerminalBinding? = null
    private val binding get() = _binding!!
    private var isLeavingScreen = false

    private val args: TerminalFragmentArgs by navArgs()
    private val viewModel: TerminalViewModel by viewModels {
        TerminalViewModelFactory(SSHToolApp.instance.hostRepository)
    }

    private var hostId: Long = -1L
    private val handler = Handler(Looper.getMainLooper())
    private var ctrlArmed = false
    private var terminalFontSize = 14f
    private lateinit var terminalBridge: SshTerminalSession
    private lateinit var terminalSession: TerminalSession

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
        hostId = args.hostId
        setupToolbar()
        setupTerminalView()
        setupTerminalInput()
        setupConnectionStatus()
        setupBackPressHandler()
        connectToHost()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { leaveScreen() }
        binding.toolbar.title = getString(R.string.app_name)
    }

    private fun setupTerminalView() {
        binding.terminalView.setTextSize(terminalFontSize.toInt())
        terminalBridge = SshTerminalSession(
            context = requireContext().applicationContext,
            outputWriter = { data -> sendRawToTerminal(data) },
            screenUpdater = { binding.terminalView.onScreenUpdated() }
        )
        terminalSession = terminalBridge.create()
        binding.terminalView.attachSession(terminalSession)
        binding.terminalView.setTerminalViewClient(object : TerminalViewClient {
            override fun onScale(scale: Float): Float {
                val newSize = (14f * scale).coerceIn(8f, 32f)
                if (kotlin.math.abs(newSize - terminalFontSize) >= 0.1f) {
                    terminalFontSize = newSize
                    binding.terminalView.setTextSize(terminalFontSize.toInt())
                    binding.terminalView.onScreenUpdated()
                }
                return terminalFontSize / 14f
            }
            override fun onSingleTapUp(e: MotionEvent) {
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
            override fun logError(tag: String, message: String) = Unit
            override fun logWarn(tag: String, message: String) = Unit
            override fun logInfo(tag: String, message: String) = Unit
            override fun logDebug(tag: String, message: String) = Unit
            override fun logVerbose(tag: String, message: String) = Unit
            override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = Unit
            override fun logStackTrace(tag: String, e: Exception) = Unit
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
            if (SSHConnectionManager.isConnected()) SSHConnectionManager.disconnect() else connectToHost()
        }
        binding.btnDisconnect.setOnClickListener { SSHConnectionManager.disconnect() }
    }

    private fun setupBackPressHandler() {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = leaveScreen()
            }
        )
    }

    private fun connectToHost() {
        viewLifecycleOwner.lifecycleScope.launch {
            val host = viewModel.getHost(hostId)
            if (host == null) {
                Toast.makeText(requireContext(), R.string.host_not_found, Toast.LENGTH_SHORT).show()
                findNavController().navigateUp()
                return@launch
            }
            binding.toolbar.title = host.name
            binding.tvStatus.text = getString(R.string.status_connecting_host, host.host)
            binding.btnConnect.isEnabled = false
            SSHConnectionManager.connect(host, SSHToolApp.instance.hostRepository, this@TerminalFragment)
        }
    }

    override fun onStateChanged(state: SSHConnectionState) {
        handler.post {
            if (!isAdded || _binding == null) return@post
            when (state) {
                is SSHConnectionState.Connecting -> {
                    binding.tvStatus.text = getString(R.string.status_connecting)
                    binding.btnConnect.isEnabled = false
                    binding.statusIndicator.setBackgroundResource(R.drawable.status_connecting)
                }
                is SSHConnectionState.Connected -> {
                    binding.tvStatus.text = getString(R.string.status_connected)
                    binding.btnConnect.isEnabled = false
                    binding.btnDisconnect.isEnabled = true
                    setTerminalInputEnabled(true)
                    binding.statusIndicator.setBackgroundResource(R.drawable.status_connected)
                    clearInputField()
                    focusTerminalInput()
                }
                is SSHConnectionState.HostKeyConfirmationRequired -> {
                    binding.tvStatus.text = getString(R.string.status_error, state.fingerprint)
                    binding.btnConnect.isEnabled = true
                    setTerminalInputEnabled(false)
                    binding.statusIndicator.setBackgroundResource(R.drawable.status_error)
                    showHostKeyDialog(state)
                }
                is SSHConnectionState.Error -> {
                    binding.tvStatus.text = getString(R.string.status_error, state.message)
                    binding.btnConnect.isEnabled = true
                    setTerminalInputEnabled(false)
                    binding.statusIndicator.setBackgroundResource(R.drawable.status_error)
                    Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
                }
                is SSHConnectionState.Disconnected -> {
                    binding.tvStatus.text = getString(R.string.status_disconnected)
                    binding.btnConnect.isEnabled = true
                    binding.btnDisconnect.isEnabled = false
                    setTerminalInputEnabled(false)
                    binding.statusIndicator.setBackgroundResource(R.drawable.status_disconnected)
                }
            }
        }
    }

    override fun onOutput(data: ByteArray) {
        handler.post {
            if (!isAdded || _binding == null) return@post
            terminalBridge.appendOutput(data)
        }
    }

    override fun onDisconnected() {
        handler.post {
            if (!isAdded || _binding == null || isLeavingScreen) return@post
            binding.tvStatus.text = getString(R.string.status_connection_closed)
            binding.btnConnect.isEnabled = true
            binding.btnDisconnect.isEnabled = false
            setTerminalInputEnabled(false)
        }
    }

    private fun showHostKeyDialog(state: SSHConnectionState.HostKeyConfirmationRequired) {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.host_key_confirm_title)
            .setMessage(getString(R.string.host_key_confirm_message, state.host, state.port, state.algorithm, state.fingerprint))
            .setNegativeButton(R.string.cancel) { _, _ ->
                Toast.makeText(requireContext(), R.string.host_key_rejected, Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton(R.string.trust_and_connect) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    SSHConnectionManager.trustCurrentHostAndReconnect(SSHToolApp.instance.hostRepository)
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
        if (!SSHConnectionManager.isConnected()) return
        val payload = if (ctrlArmed && text.isNotEmpty()) {
            ctrlArmed = false
            binding.btnCtrl.isSelected = false
            binding.btnCtrl.alpha = 0.75f
            text.map { ch ->
                val asciiCode = ch.code and 0x7F
                if (asciiCode in 64..95 || asciiCode in 97..122) (asciiCode and 0x1F).toChar() else ch
            }.joinToString(separator = "")
        } else text
        sendRawToTerminal(payload)
    }

    private fun sendRawToTerminal(raw: String) {
        if (!isAdded || _binding == null || !SSHConnectionManager.isConnected()) return
        SSHConnectionManager.getCurrentConnection()?.send(raw)
    }

    private fun clearInputField() {
        binding.etInput.clearInternalText()
    }

    private fun focusTerminalInput() {
        if (!binding.etInput.isEnabled) return
        binding.etInput.requestFocus()
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.etInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun pasteClipboard() {
        if (!isAdded || _binding == null || !SSHConnectionManager.isConnected()) return
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(requireContext())?.toString().orEmpty()
        if (text.isNotEmpty()) sendTextToTerminal(text)
    }

    override fun onTextInput(text: String) {
        sendTextToTerminal(text)
        clearInputField()
    }

    override fun onEnter() {
        sendRawToTerminal("\r")
        clearInputField()
    }

    override fun onBackspace() {
        sendRawToTerminal("\u007F")
    }

    private fun leaveScreen() {
        if (isLeavingScreen) return
        isLeavingScreen = true
        SSHConnectionManager.disconnect()
        findNavController().navigateUp()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        if (::terminalSession.isInitialized) terminalSession.finishIfRunning()
        _binding = null
    }
}
