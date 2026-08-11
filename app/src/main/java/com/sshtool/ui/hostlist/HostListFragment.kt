package com.sshtool.ui.hostlist

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavDirections
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.sshtool.R
import com.sshtool.SSHToolApp
import com.sshtool.data.model.Host
import com.sshtool.databinding.FragmentHostListBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HostListFragment : Fragment() {
    
    private var _binding: FragmentHostListBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: HostListViewModel by viewModels {
        HostListViewModelFactory(SSHToolApp.instance.hostRepository)
    }
    
    private lateinit var adapter: HostListAdapter
    private var deleteDialog: AlertDialog? = null
    private var aboutMenu: PopupMenu? = null

    /**
     * Guards against double-tap navigation: Android can deliver two onClick
     * events for a single user double-tap, which would otherwise push two
     * TerminalFragment instances and open two SSH connections to the same host
     * (M10). Set on navigate, cleared on onResume.
     */
    private var isNavigating = false
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHostListBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupRecyclerView()
        setupFab()
        setupMenu()
        observeHosts()
    }
    
    private fun setupRecyclerView() {
        adapter = HostListAdapter(
            onItemClick = { host ->
                val action = HostListFragmentDirections
                    .actionHostListToTerminal(host.id)
                navigateOnce(action)
            },
            onItemLongClick = { host ->
                val action = HostListFragmentDirections
                    .actionHostListToEditor(host.id)
                navigateOnce(action)
                // Always consume the long-click, including while another
                // navigation is already gated, so it cannot fall through into
                // the regular item click callback.
                true
            },
            onDeleteClick = { host ->
                confirmDelete(host)
            }
        )
        
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@HostListFragment.adapter
        }
    }
    
    private fun setupMenu() {
        binding.btnAbout.setOnClickListener { anchor ->
            if (isNavigating || aboutMenu != null) return@setOnClickListener
            val popup = PopupMenu(requireContext(), anchor)
            aboutMenu = popup
            popup.menuInflater.inflate(R.menu.menu_host_list, popup.menu)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_about -> {
                        navigateOnce(
                            HostListFragmentDirections.actionHostListToAbout()
                        )
                        true
                    }
                    else -> false
                }
            }
            popup.setOnDismissListener {
                if (aboutMenu === popup) aboutMenu = null
            }
            popup.show()
        }
    }

    private fun setupFab() {
        binding.fabAdd.setOnClickListener {
            val action = HostListFragmentDirections.actionHostListToEditor(-1L)
            navigateOnce(action)
        }
    }
    
    private fun observeHosts() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.hosts.collectLatest { hosts ->
                        adapter.submitList(hosts)
                        binding.emptyView.visibility =
                            if (hosts.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is HostListEvent.DeleteFailed -> showDeleteFailure(event)
                        }
                    }
                }
            }
        }
    }

    private fun confirmDelete(host: Host) {
        if (deleteDialog?.isShowing == true) return
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.confirm_delete_title)
            .setMessage(getString(R.string.confirm_delete_message, host.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteHost(host)
            }
            .create()
        deleteDialog = dialog
        dialog.setOnDismissListener {
            if (deleteDialog === dialog) deleteDialog = null
        }
        dialog.show()
    }

    private fun showDeleteFailure(event: HostListEvent.DeleteFailed) {
        val detail = event.message?.takeIf { it.isNotBlank() }
            ?: getString(R.string.confirm_delete_title)
        Toast.makeText(
            requireContext(),
            getString(R.string.status_error, detail),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun navigateOnce(action: NavDirections): Boolean {
        if (isNavigating) return false
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return false
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.hostListFragment) return false
        isNavigating = true
        return runCatching {
            navController.navigate(action)
            true
        }.onFailure {
            // A lifecycle transition can save FragmentManager state between a
            // click and navigate(). Do not crash or permanently disable every
            // action on this screen when that race occurs.
            isNavigating = false
            android.util.Log.w("HostListFragment", "Navigation was rejected", it)
        }.getOrDefault(false)
    }
    
    override fun onResume() {
        super.onResume()
        // Returning to this fragment means any pending navigation completed;
        // re-enable click handling (M10 debounce).
        isNavigating = false
    }

    override fun onDestroyView() {
        deleteDialog?.dismiss()
        deleteDialog = null
        aboutMenu?.dismiss()
        aboutMenu = null
        binding.recyclerView.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
