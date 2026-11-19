package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.databinding.AdapterGlobalRuleBinding
import com.github.kr328.clash.design.databinding.DesignGlobalRulesBinding
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.applyLinearAdapter
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.patchDataSet
import com.github.kr328.clash.design.util.root

class GlobalRulesDesign(context: Context) : Design<GlobalRulesDesign.Request>(context) {
    sealed class Request {
        data class Add(val batch: Boolean) : Request()
        data class Edit(val id: String) : Request()
        data class Delete(val id: String) : Request()
        data class Move(val id: String, val offset: Int) : Request()
        data class Toggle(val id: String, val enabled: Boolean) : Request()
    }

    data class RuleRow(val id: String, val text: String, val issue: String?, val enabled: Boolean)

    private val binding = DesignGlobalRulesBinding.inflate(context.layoutInflater, context.root, false)
    private val adapter = RuleAdapter()
    override val root: View get() = binding.root

    init {
        binding.self = this
        binding.activityBarLayout.applyFrom(context)
        binding.mainList.recyclerList.also {
            it.bindAppBarElevation(binding.activityBarLayout)
            it.applyLinearAdapter(context, adapter)
        }
    }

    fun requestCreate() {
        PopupMenu(context, binding.addView).apply {
            menu.add(R.string.new_global_rule).setOnMenuItemClickListener {
                requests.trySend(Request.Add(false))
                true
            }
            menu.add(R.string.import_global_rules).setOnMenuItemClickListener {
                requests.trySend(Request.Add(true))
                true
            }
        }.show()
    }

    suspend fun patchRules(rules: List<RuleRow>) {
        adapter.patchDataSet(adapter::rows, rules, id = { it.id })
    }

    private class Holder(val binding: AdapterGlobalRuleBinding) : RecyclerView.ViewHolder(binding.root)

    private inner class RuleAdapter : RecyclerView.Adapter<Holder>() {
        var rows: List<RuleRow> = emptyList()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(AdapterGlobalRuleBinding.inflate(context.layoutInflater, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            holder.binding.ruleText.text = row.text
            holder.binding.ruleIssue.text = row.issue
            holder.binding.ruleIssue.visibility = if (row.issue == null) View.GONE else View.VISIBLE
            holder.binding.enabledView.setOnCheckedChangeListener(null)
            holder.binding.enabledView.isChecked = row.enabled
            holder.binding.enabledView.setOnCheckedChangeListener { _, enabled ->
                requests.trySend(Request.Toggle(row.id, enabled))
            }
            holder.binding.root.setOnClickListener { requests.trySend(Request.Edit(row.id)) }
            holder.binding.actionsView.setOnClickListener { anchor ->
                val current = holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                    ?: return@setOnClickListener
                PopupMenu(context, anchor).apply {
                    menu.add(R.string.move_rule_up).apply {
                        isEnabled = current > 0
                        setOnMenuItemClickListener {
                            requests.trySend(Request.Move(row.id, -1))
                            true
                        }
                    }
                    menu.add(R.string.move_rule_down).apply {
                        isEnabled = current < rows.lastIndex
                        setOnMenuItemClickListener {
                            requests.trySend(Request.Move(row.id, 1))
                            true
                        }
                    }
                    menu.add(R.string.delete).setOnMenuItemClickListener {
                        requests.trySend(Request.Delete(row.id))
                        true
                    }
                }.show()
            }
        }

        override fun getItemCount(): Int = rows.size
    }
}
