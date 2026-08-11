package com.sshtool.ui.editor

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
import com.sshtool.R
import com.sshtool.SSHToolApp
import com.sshtool.databinding.FragmentHostEditorBinding
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
                android.util.Log.w("HostEditorFragment", "Navigation was rejected", it)
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
                                    event.message ?: getString(R.string.error_save_failed),
                                    Toast.LENGTH_LONG
                                ).show()
                                leaveEditor()
                            }
                            HostEditorEvent.SaveSucceeded -> {
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
                                    event.message ?: getString(R.string.error_save_failed),
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
        super.onDestroyView()
        _binding = null
    }
}
