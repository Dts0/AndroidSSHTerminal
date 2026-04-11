package com.sshtool.ui.editor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.sshtool.R
import com.sshtool.SSHToolApp
import com.sshtool.data.model.Host
import com.sshtool.databinding.FragmentHostEditorBinding
import kotlinx.coroutines.launch

class HostEditorFragment : Fragment() {
    
    private var _binding: FragmentHostEditorBinding? = null
    private val binding get() = _binding!!
    
    private val args: HostEditorFragmentArgs by navArgs()
    private val viewModel: HostEditorViewModel by viewModels {
        HostEditorViewModelFactory(SSHToolApp.instance.hostRepository)
    }
    
    private var existingHost: Host? = null
    
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
        
        setupToolbar()
        setupAuthTypeToggle()
        setupSaveButton()
        
        // 加载现有主机数据
        if (args.hostId != -1L) {
            loadExistingHost()
        }
    }
    
    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
        
        binding.toolbar.title = getString(if (args.hostId == -1L) R.string.add_host else R.string.edit_host)
    }
    
    private fun setupAuthTypeToggle() {
        binding.toggleAuthType.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btn_password -> {
                        binding.layoutPassword.visibility = View.VISIBLE
                        binding.layoutPrivateKey.visibility = View.GONE
                    }
                    R.id.btn_key -> {
                        binding.layoutPassword.visibility = View.GONE
                        binding.layoutPrivateKey.visibility = View.VISIBLE
                    }
                }
            }
        }
    }
    
    private fun setupSaveButton() {
        binding.btnSave.setOnClickListener {
            if (validateInput()) {
                saveHost()
            }
        }
    }
    
    private fun loadExistingHost() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.getHost(args.hostId)?.let { host ->
                existingHost = host
                binding.apply {
                    etName.setText(host.name)
                    etHost.setText(host.host)
                    etPort.setText(host.port.toString())
                    etUsername.setText(host.username)
                    
                    if (host.useKeyAuth) {
                        toggleAuthType.check(R.id.btn_key)
                        etPrivateKey.setText(host.privateKey ?: "")
                        etPassphrase.setText(host.passphrase ?: "")
                        layoutPassword.hint = getString(R.string.password)
                        (layoutPrivateKey.getChildAt(0) as? com.google.android.material.textfield.TextInputLayout)
                            ?.hint = getString(R.string.private_key_optional_keep)
                        (layoutPrivateKey.getChildAt(1) as? com.google.android.material.textfield.TextInputLayout)
                            ?.hint = getString(R.string.passphrase_optional_keep)
                    } else {
                        toggleAuthType.check(R.id.btn_password)
                        etPassword.setText(host.password ?: "")
                        layoutPassword.hint = getString(R.string.password_optional_keep)
                    }
                }
            }
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

        if (useKeyAuth) {
            if (binding.etPrivateKey.text.isNullOrBlank() && existingHost?.useKeyAuth != true) {
                binding.etPrivateKey.error = getString(R.string.private_key_required)
                isValid = false
            }
        } else {
            if (binding.etPassword.text.isNullOrBlank() && existingHost?.useKeyAuth != false) {
                binding.etPassword.error = getString(R.string.password_required)
                isValid = false
            }
        }
        
        return isValid
    }
    
    private fun saveHost() {
        val useKeyAuth = binding.toggleAuthType.checkedButtonId == R.id.btn_key
        val host = Host(
            id = existingHost?.id ?: 0,
            name = binding.etName.text.toString().trim(),
            host = binding.etHost.text.toString().trim(),
            port = binding.etPort.text.toString().toInt(),
            username = binding.etUsername.text.toString().trim(),
            useKeyAuth = useKeyAuth,
            lastConnected = existingHost?.lastConnected,
            createdAt = existingHost?.createdAt ?: System.currentTimeMillis()
        ).apply {
            password = if (!useKeyAuth) {
                binding.etPassword.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: existingHost?.takeIf { !it.useKeyAuth }?.password
            } else {
                null
            }
            privateKey = if (useKeyAuth) {
                binding.etPrivateKey.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: existingHost?.takeIf { it.useKeyAuth }?.privateKey
            } else {
                null
            }
            passphrase = if (useKeyAuth) {
                binding.etPassphrase.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: existingHost?.takeIf { it.useKeyAuth }?.passphrase
            } else {
                null
            }
        }
        
        viewLifecycleOwner.lifecycleScope.launch {
            if (existingHost != null) {
                viewModel.updateHost(host)
            } else {
                viewModel.saveHost(host)
            }
            Toast.makeText(requireContext(), R.string.saved_successfully, Toast.LENGTH_SHORT).show()
            findNavController().navigateUp()
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
