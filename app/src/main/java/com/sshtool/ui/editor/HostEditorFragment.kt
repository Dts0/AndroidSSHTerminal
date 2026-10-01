package com.sshtool.ui.editor

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sshtool.R
import com.sshtool.SSHToolApp
import com.sshtool.data.model.PortForwardDirection
import com.sshtool.data.model.PortForwardRule
import com.sshtool.databinding.DialogPortForwardRuleBinding
import com.sshtool.databinding.FragmentHostEditorBinding
import com.sshtool.ui.MainActivity
import com.sshtool.util.resolveMessage
import kotlinx.coroutines.launch

class HostEditorFragment : Fragment() {
    
    private var _binding: FragmentHostEditorBinding? = null
    private val binding get() = _binding!!
    
    private val args: HostEditorFragmentArgs by navArgs()
    private val viewModel: HostEditorViewModel by viewModels {
        HostEditorViewModelFactory(SSHToolApp.instance.hostRepository)
    }
    
    private var isPopulatingForm = false
    private var isLeaving = false
    private var portForwardDialog: Dialog? = null
    private lateinit var portForwardAdapter: PortForwardRuleAdapter
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHostEditorBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        isLeaving = false
        
        setupToolbar()
        setupBackPressHandler()
        setupAuthTypeToggle()
        setupDraftTracking()
        setupAdvancedSettings()
        setupSaveButton()
        observeEditorState()
        viewModel.initialize(args.hostId)
    }
    
    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { leaveEditor() }
        
        binding.toolbar.title = getString(if (args.hostId == -1L) R.string.add_host else R.string.edit_host)
    }

    private fun setupBackPressHandler() {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = leaveEditor()
            }
        )
    }

    /** Do not cancel a cross-store mutation after the user has committed it. */
    private fun leaveEditor() {
        if (viewModel.uiState.value.isSaving || isLeaving) return
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.hostEditorFragment) return
        isLeaving = true
        val navigated = runCatching { navController.navigateUp() }
            .onFailure {
                android.util.Log.w("HostEditorFragment", "Navigation was rejected")
            }
            .getOrDefault(false)
        if (!navigated) isLeaving = false
    }
    
    private fun setupAuthTypeToggle() {
        binding.toggleAuthType.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                if (!isPopulatingForm) {
                    viewModel.updateAuthType(checkedId == R.id.btn_key)
                }
                when (checkedId) {
                    R.id.btn_password -> {
                        binding.layoutPassword.visibility = View.VISIBLE
                        binding.layoutKeyAuth.visibility = View.GONE
                    }
                    R.id.btn_key -> {
                        binding.layoutPassword.visibility = View.GONE
                        binding.layoutKeyAuth.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun setupDraftTracking() {
        binding.etName.doAfterTextChanged {
            if (!isPopulatingForm) viewModel.updateName(it?.toString().orEmpty())
        }
        binding.etHost.doAfterTextChanged {
            if (!isPopulatingForm) viewModel.updateHost(it?.toString().orEmpty())
        }
        binding.etPort.doAfterTextChanged {
            if (!isPopulatingForm) viewModel.updatePort(it?.toString().orEmpty())
        }
        binding.etUsername.doAfterTextChanged {
            if (!isPopulatingForm) viewModel.updateUsername(it?.toString().orEmpty())
        }
        binding.etPassword.doAfterTextChanged {
            if (!isPopulatingForm) viewModel.updatePassword(it?.toString().orEmpty())
        }
        binding.etPrivateKey.doAfterTextChanged {
            if (!isPopulatingForm) viewModel.updatePrivateKey(it?.toString().orEmpty())
        }
        binding.etPassphrase.doAfterTextChanged {
            if (!isPopulatingForm) viewModel.updatePassphrase(it?.toString().orEmpty())
        }
    }

    private fun setupAdvancedSettings() {
        portForwardAdapter = PortForwardRuleAdapter(
            onEdit = { index -> showPortForwardDialog(index) },
            onDelete = { index -> confirmDeletePortForwardRule(index) },
            onEnabledChanged = viewModel::setPortForwardRuleEnabled
        )
        binding.listPortForwards.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = portForwardAdapter
        }
        val toggleExpanded = View.OnClickListener { viewModel.toggleAdvancedSettings() }
        binding.headerAdvancedSettings.setOnClickListener(toggleExpanded)
        binding.btnExpandAdvanced.setOnClickListener(toggleExpanded)
        binding.btnAddPortForward.setOnClickListener { showPortForwardDialog() }
    }

    private fun showPortForwardDialog(editingIndex: Int? = null) {
        val state = viewModel.uiState.value
        if (portForwardDialog != null || state.isSaving || state.isLoading ||
            state.saveCompleted || !state.loadCompleted
        ) {
            return
        }
        val existingRule = editingIndex?.let {
            state.draft.portForwardRules.getOrNull(it)
        }
        if (editingIndex != null && existingRule == null) return

        val dialogBinding = DialogPortForwardRuleBinding.inflate(layoutInflater)
        val initialDirection = existingRule?.direction ?: PortForwardDirection.LOCAL
        dialogBinding.toggleDirection.check(
            if (initialDirection == PortForwardDirection.LOCAL) {
                R.id.btn_local_forward
            } else {
                R.id.btn_remote_forward
            }
        )
        dialogBinding.etBindAddress.setText(existingRule?.bindAddress ?: LOOPBACK_ADDRESS)
        dialogBinding.etListenPort.setText(existingRule?.listenPort?.toString().orEmpty())
        dialogBinding.etTargetHost.setText(existingRule?.targetHost ?: LOOPBACK_ADDRESS)
        dialogBinding.etTargetPort.setText(existingRule?.targetPort?.toString().orEmpty())
        dialogBinding.switchEnabled.isChecked = existingRule?.enabled ?: true

        fun selectedDirection(): PortForwardDirection =
            if (dialogBinding.toggleDirection.checkedButtonId == R.id.btn_remote_forward) {
                PortForwardDirection.REMOTE
            } else {
                PortForwardDirection.LOCAL
            }

        fun renderDirectionHint() {
            dialogBinding.tvDirectionHint.setText(
                if (selectedDirection() == PortForwardDirection.LOCAL) {
                    R.string.port_forward_local_hint
                } else {
                    R.string.port_forward_remote_hint
                }
            )
        }

        fun renderBindWarning() {
            val address = dialogBinding.etBindAddress.text?.toString().orEmpty().trim()
            dialogBinding.tvBindWarning.visibility =
                if (address.isNotEmpty() && !address.isLoopbackPortForwardAddress()) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
        }

        renderDirectionHint()
        renderBindWarning()
        dialogBinding.toggleDirection.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) renderDirectionHint()
        }
        dialogBinding.etBindAddress.doAfterTextChanged { renderBindWarning() }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(
                if (existingRule == null) {
                    R.string.port_forward_add_title
                } else {
                    R.string.port_forward_edit_title
                }
            )
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()
        portForwardDialog = dialog
        dialog.setOnDismissListener { portForwardDialog = null }
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val bindAddress = dialogBinding.etBindAddress.text
                        ?.toString().orEmpty().trim()
                    val targetHost = dialogBinding.etTargetHost.text
                        ?.toString().orEmpty().trim()
                    val listenPort = dialogBinding.etListenPort.text
                        ?.toString()?.toIntOrNull()
                    val targetPort = dialogBinding.etTargetPort.text
                        ?.toString()?.toIntOrNull()

                    dialogBinding.layoutBindAddress.error = if (bindAddress.isBlank()) {
                        getString(R.string.port_forward_bind_required)
                    } else {
                        null
                    }
                    dialogBinding.layoutTargetHost.error = if (targetHost.isBlank()) {
                        getString(R.string.port_forward_target_required)
                    } else {
                        null
                    }
                    dialogBinding.layoutListenPort.error = if (listenPort !in VALID_PORT_RANGE) {
                        getString(R.string.port_forward_invalid_listen_port)
                    } else {
                        null
                    }
                    dialogBinding.layoutTargetPort.error = if (targetPort !in VALID_PORT_RANGE) {
                        getString(R.string.port_forward_invalid_target_port)
                    } else {
                        null
                    }
                    if (bindAddress.isBlank() || targetHost.isBlank() ||
                        listenPort !in VALID_PORT_RANGE || targetPort !in VALID_PORT_RANGE
                    ) {
                        return@setOnClickListener
                    }

                    val direction = selectedDirection()
                    if (viewModel.hasDuplicatePortForward(
                            direction = direction,
                            bindAddress = bindAddress,
                            listenPort = checkNotNull(listenPort),
                            excludingIndex = editingIndex
                        )
                    ) {
                        dialogBinding.layoutListenPort.error =
                            getString(R.string.port_forward_duplicate)
                        return@setOnClickListener
                    }

                    val rule = PortForwardRule(
                        id = existingRule?.id ?: 0,
                        hostId = existingRule?.hostId ?: 0,
                        direction = direction,
                        bindAddress = bindAddress,
                        listenPort = listenPort,
                        targetHost = targetHost,
                        targetPort = checkNotNull(targetPort),
                        enabled = dialogBinding.switchEnabled.isChecked,
                        sortOrder = existingRule?.sortOrder
                            ?: viewModel.uiState.value.draft.portForwardRules.size
                    )
                    if (editingIndex == null) {
                        viewModel.addPortForwardRule(rule)
                    } else {
                        viewModel.updatePortForwardRule(editingIndex, rule)
                    }
                    dialog.dismiss()
                }
        }
        dialog.show()
    }

    private fun confirmDeletePortForwardRule(index: Int) {
        val state = viewModel.uiState.value
        if (portForwardDialog != null || state.isSaving || state.isLoading ||
            state.saveCompleted || !state.loadCompleted ||
            index !in state.draft.portForwardRules.indices
        ) {
            return
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.port_forward_delete_title)
            .setMessage(R.string.port_forward_delete_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.removePortForwardRule(index)
            }
            .create()
        portForwardDialog = dialog
        dialog.setOnDismissListener { portForwardDialog = null }
        dialog.show()
    }
    
    private fun setupSaveButton() {
        binding.btnSave.setOnClickListener {
            val state = viewModel.uiState.value
            if (state.isSaving || state.saveCompleted || state.isLoading || !state.loadCompleted) {
                return@setOnClickListener
            }
            if (validateInput()) {
                viewModel.submit()
            }
        }
    }

    private fun observeEditorState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect(::renderEditorState)
                }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            HostEditorEvent.HostNotFound -> {
                                Toast.makeText(
                                    requireContext(),
                                    R.string.host_not_found,
                                    Toast.LENGTH_SHORT
                                ).show()
                                leaveEditor()
                            }
                            is HostEditorEvent.LoadFailed -> {
                                Toast.makeText(
                                    requireContext(),
                                    requireContext().resolveMessage(event.message),
                                    Toast.LENGTH_LONG
                                ).show()
                                leaveEditor()
                            }
                            is HostEditorEvent.SaveSucceeded -> {
                                if (event.editingExisting) {
                                    (activity as? MainActivity)
                                        ?.existingTerminalViewModelOrNull()
                                        ?.markHostConfigurationStale(event.hostId)
                                }
                                Toast.makeText(
                                    requireContext(),
                                    R.string.saved_successfully,
                                    Toast.LENGTH_SHORT
                                ).show()
                                leaveEditor()
                            }
                            is HostEditorEvent.SaveFailed -> {
                                Toast.makeText(
                                    requireContext(),
                                    requireContext().resolveMessage(event.message),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun renderEditorState(state: HostEditorUiState) {
        val b = _binding ?: return
        val draft = state.draft
        isPopulatingForm = true
        try {
            b.etName.setTextIfDifferent(draft.name)
            b.etHost.setTextIfDifferent(draft.host)
            b.etPort.setTextIfDifferent(draft.port)
            b.etUsername.setTextIfDifferent(draft.username)
            b.etPassword.setTextIfDifferent(draft.password)
            b.etPrivateKey.setTextIfDifferent(draft.privateKey)
            b.etPassphrase.setTextIfDifferent(draft.passphrase)
            b.toggleAuthType.check(if (draft.useKeyAuth) R.id.btn_key else R.id.btn_password)
            b.layoutPassword.visibility = if (draft.useKeyAuth) View.GONE else View.VISIBLE
            b.layoutKeyAuth.visibility = if (draft.useKeyAuth) View.VISIBLE else View.GONE
            b.layoutPassword.hint = getString(
                if (state.canKeepPassword && !draft.useKeyAuth) {
                    R.string.password_optional_keep
                } else {
                    R.string.password
                }
            )
            if (state.canKeepPrivateKey && draft.useKeyAuth) {
                b.layoutPrivateKey.hint = getString(R.string.private_key_optional_keep)
                b.layoutPassphrase.hint = getString(R.string.passphrase_optional_keep)
            }
        } finally {
            isPopulatingForm = false
        }
        b.btnSave.isEnabled = state.loadCompleted && !state.isLoading &&
            !state.isSaving && !state.saveCompleted
        val rules = draft.portForwardRules
        val interactionEnabled = state.loadCompleted && !state.isLoading &&
            !state.isSaving && !state.saveCompleted
        b.layoutAdvancedSettings.visibility = if (state.advancedSettingsExpanded) {
            View.VISIBLE
        } else {
            View.GONE
        }
        b.btnExpandAdvanced.rotation = if (state.advancedSettingsExpanded) 180f else 0f
        b.btnExpandAdvanced.contentDescription = getString(
            if (state.advancedSettingsExpanded) {
                R.string.collapse_advanced_settings
            } else {
                R.string.expand_advanced_settings
            }
        )
        b.tvPortForwardSummary.text = if (rules.isEmpty()) {
            getString(R.string.port_forward_none)
        } else {
            getString(
                R.string.port_forward_summary,
                rules.size,
                rules.count(PortForwardRule::enabled)
            )
        }
        b.tvPortForwardEmpty.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
        b.listPortForwards.visibility = if (rules.isEmpty()) View.GONE else View.VISIBLE
        b.headerAdvancedSettings.isEnabled = !state.isSaving && !state.saveCompleted
        b.btnExpandAdvanced.isEnabled = !state.isSaving && !state.saveCompleted
        b.btnAddPortForward.isEnabled = interactionEnabled
        portForwardAdapter.submitRules(rules, interactionEnabled)
    }

    private fun android.widget.EditText.setTextIfDifferent(value: String) {
        if (text?.toString() != value) {
            setText(value)
            setSelection(value.length)
        }
    }
    
    private fun validateInput(): Boolean {
        var isValid = true
        
        if (binding.etName.text.isNullOrBlank()) {
            binding.etName.error = getString(R.string.name_required)
            isValid = false
        }
        
        if (binding.etHost.text.isNullOrBlank()) {
            binding.etHost.error = getString(R.string.host_required)
            isValid = false
        }
        
        if (binding.etUsername.text.isNullOrBlank()) {
            binding.etUsername.error = getString(R.string.username_required)
            isValid = false
        }
        
        val port = binding.etPort.text.toString().toIntOrNull()
        if (port == null || port <= 0 || port > 65535) {
            binding.etPort.error = getString(R.string.invalid_port)
            isValid = false
        }
        
        val useKeyAuth = binding.toggleAuthType.checkedButtonId == R.id.btn_key
        val state = viewModel.uiState.value

        if (useKeyAuth) {
            if (binding.etPrivateKey.text.isNullOrBlank() && !state.canKeepPrivateKey) {
                binding.etPrivateKey.error = getString(R.string.private_key_required)
                isValid = false
            }
        } else {
            if (binding.etPassword.text.isNullOrBlank() && !state.canKeepPassword) {
                binding.etPassword.error = getString(R.string.password_required)
                isValid = false
            }
        }
        
        return isValid
    }
    
    override fun onDestroyView() {
        portForwardDialog?.dismiss()
        portForwardDialog = null
        binding.listPortForwards.adapter = null
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val LOOPBACK_ADDRESS = "127.0.0.1"
        val VALID_PORT_RANGE = 1..65535
    }
}
