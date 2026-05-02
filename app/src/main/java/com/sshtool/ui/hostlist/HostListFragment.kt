package com.sshtool.ui.hostlist

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
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
                findNavController().navigate(action)
            },
            onItemLongClick = { host ->
                val action = HostListFragmentDirections
                    .actionHostListToEditor(host.id)
                findNavController().navigate(action)
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
        binding.btnOverflow.setOnClickListener { anchor ->
            val popup = PopupMenu(requireContext(), anchor)
            popup.menuInflater.inflate(R.menu.menu_host_list, popup.menu)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_about -> {
                        findNavController().navigate(
                            HostListFragmentDirections.actionHostListToAbout()
                        )
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }

    private fun setupFab() {
        binding.fabAdd.setOnClickListener {
            val action = HostListFragmentDirections.actionHostListToEditor(-1L)
            findNavController().navigate(action)
        }
    }
    
    private fun observeHosts() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.hosts.collectLatest { hosts ->
                adapter.submitList(hosts)
                binding.emptyView.visibility = if (hosts.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun confirmDelete(host: Host) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.confirm_delete_title)
            .setMessage(getString(R.string.confirm_delete_message, host.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteHost(host)
            }
            .show()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
