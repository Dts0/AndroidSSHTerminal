package com.sshtool.ui.hostlist

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sshtool.data.model.Host
import com.sshtool.databinding.ItemHostBinding
import java.text.SimpleDateFormat
import java.util.*

class HostListAdapter(
    private val onItemClick: (Host) -> Unit,
    private val onItemLongClick: (Host) -> Boolean,
    private val onDeleteClick: (Host) -> Unit
) : ListAdapter<Host, HostListAdapter.HostViewHolder>(HostDiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HostViewHolder {
        val binding = ItemHostBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return HostViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: HostViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    inner class HostViewHolder(
        private val binding: ItemHostBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(host: Host) {
            binding.apply {
                tvName.text = host.name
                tvHost.text = itemView.context.getString(
                    com.sshtool.R.string.host_summary,
                    host.username,
                    host.host,
                    host.port
                )
                
                // 显示认证方式
                tvAuthType.text = if (host.useKeyAuth) itemView.context.getString(com.sshtool.R.string.auth_key) else itemView.context.getString(com.sshtool.R.string.auth_password)
                
                // 显示最后连接时间
                host.lastConnected?.let { timestamp ->
                    val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                    tvLastConnected.text = dateFormat.format(Date(timestamp))
                } ?: run {
                    tvLastConnected.text = itemView.context.getString(com.sshtool.R.string.never_connected)
                }
                
                root.setOnClickListener { onItemClick(host) }
                root.setOnLongClickListener { onItemLongClick(host) }
                btnDelete.setOnClickListener { onDeleteClick(host) }
            }
        }
    }
    
    class HostDiffCallback : DiffUtil.ItemCallback<Host>() {
        override fun areItemsTheSame(oldItem: Host, newItem: Host): Boolean {
            return oldItem.id == newItem.id
        }
        
        override fun areContentsTheSame(oldItem: Host, newItem: Host): Boolean {
            return oldItem == newItem
        }
    }
}
