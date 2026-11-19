package com.github.kr328.clash.design.adapter

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.AdapterCustomNodeBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.service.model.CustomNode

class CustomNodeAdapter(
    private val context: Context,
    private val onClicked: (CustomNode) -> Unit,
    private val onDeleteClicked: (CustomNode) -> Unit,
    private val onToggled: (CustomNode, Boolean) -> Unit,
) : RecyclerView.Adapter<CustomNodeAdapter.Holder>() {
    class Holder(val binding: AdapterCustomNodeBinding) : RecyclerView.ViewHolder(binding.root)

    var nodes: List<CustomNode> = emptyList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(AdapterCustomNodeBinding.inflate(context.layoutInflater, parent, false))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val current = nodes[position]

        holder.binding.nameView.text = current.name
        holder.binding.addressView.text = if (current.address.isEmpty()) current.protocol
        else context.getString(R.string.format_custom_node_detail, current.protocol, current.address)
        holder.binding.enabledView.setOnCheckedChangeListener(null)
        holder.binding.enabledView.isChecked = current.enabled
        holder.binding.enabledView.setOnCheckedChangeListener { _, enabled -> onToggled(current, enabled) }
        holder.binding.root.setOnClickListener { onClicked(current) }
        holder.binding.deleteView.setOnClickListener { onDeleteClicked(current) }
    }

    override fun getItemCount(): Int = nodes.size
}
