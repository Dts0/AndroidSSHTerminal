package com.sshtool.ui.editor

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.sshtool.R
import com.sshtool.data.model.PortForwardDirection
import com.sshtool.data.model.PortForwardRule
import com.sshtool.databinding.ItemPortForwardRuleBinding

internal class PortForwardRuleAdapter(
    private val onEdit: (Int) -> Unit,
    private val onDelete: (Int) -> Unit,
    private val onEnabledChanged: (Int, Boolean) -> Unit
) : RecyclerView.Adapter<PortForwardRuleAdapter.RuleViewHolder>() {

    private var rules: List<PortForwardRule> = emptyList()
    private var interactionEnabled: Boolean = true

    fun submitRules(newRules: List<PortForwardRule>, enabled: Boolean) {
        if (rules == newRules && interactionEnabled == enabled) return
        val oldRules = rules
        val oldInteractionEnabled = interactionEnabled
        val replacement = newRules.toList()
        val diff = DiffUtil.calculateDiff(
            object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = oldRules.size
                override fun getNewListSize(): Int = replacement.size

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val oldRule = oldRules[oldItemPosition]
                    val newRule = replacement[newItemPosition]
                    return if (oldRule.id != 0L && newRule.id != 0L) {
                        oldRule.id == newRule.id
                    } else {
                        oldRule === newRule
                    }
                }

                override fun areContentsTheSame(
                    oldItemPosition: Int,
                    newItemPosition: Int
                ): Boolean = oldRules[oldItemPosition] == replacement[newItemPosition]
            }
        )
        rules = replacement
        interactionEnabled = enabled
        diff.dispatchUpdatesTo(this)
        if (oldInteractionEnabled != enabled && itemCount > 0) {
            notifyItemRangeChanged(0, itemCount)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RuleViewHolder {
        val binding = ItemPortForwardRuleBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return RuleViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RuleViewHolder, position: Int) {
        holder.bind(rules[position])
    }

    override fun getItemCount(): Int = rules.size

    inner class RuleViewHolder(
        private val binding: ItemPortForwardRuleBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(rule: PortForwardRule) = with(binding) {
            val context = root.context
            tvDirection.setText(
                if (rule.direction == PortForwardDirection.LOCAL) {
                    R.string.port_forward_local
                } else {
                    R.string.port_forward_remote
                }
            )
            tvEndpoint.text = context.getString(
                R.string.port_forward_endpoint,
                rule.bindAddress.asEndpointAddress(),
                rule.listenPort,
                rule.targetHost.asEndpointAddress(),
                rule.targetPort
            )

            switchEnabled.setOnCheckedChangeListener(null)
            switchEnabled.isChecked = rule.enabled
            switchEnabled.isEnabled = interactionEnabled
            switchEnabled.setOnCheckedChangeListener { _, checked ->
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onEnabledChanged(position, checked)
                }
            }

            btnEdit.isEnabled = interactionEnabled
            btnDelete.isEnabled = interactionEnabled
            btnEdit.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onEdit(position)
            }
            btnDelete.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onDelete(position)
            }
        }
    }

    private fun String.asEndpointAddress(): String =
        if (':' in this && !(startsWith('[') && endsWith(']'))) "[$this]" else this
}
