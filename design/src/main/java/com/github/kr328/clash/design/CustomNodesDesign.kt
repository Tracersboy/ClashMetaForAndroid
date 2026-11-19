package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.adapter.CustomNodeAdapter
import com.github.kr328.clash.design.databinding.DesignCustomNodesBinding
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.applyLinearAdapter
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.patchDataSet
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.service.model.CustomNode
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class CustomNodesDesign(context: Context) : Design<CustomNodesDesign.Request>(context) {
    sealed class Request {
        object Import : Request()
        data class Edit(val node: CustomNode) : Request()
        data class Delete(val node: CustomNode) : Request()
        data class Toggle(val id: String, val enabled: Boolean) : Request()
    }

    private val binding = DesignCustomNodesBinding
        .inflate(context.layoutInflater, context.root, false)
    private val adapter = CustomNodeAdapter(
        context,
        onClicked = { requests.trySend(Request.Edit(it)) },
        onDeleteClicked = { requests.trySend(Request.Delete(it)) },
        onToggled = { node, enabled -> requests.trySend(Request.Toggle(node.id, enabled)) },
    )

    override val root: View
        get() = binding.root

    init {
        binding.self = this
        binding.activityBarLayout.applyFrom(context)
        binding.mainList.recyclerList.also {
            it.bindAppBarElevation(binding.activityBarLayout)
            it.applyLinearAdapter(context, adapter)
        }
    }

    fun requestCreate() {
        requests.trySend(Request.Import)
    }

    suspend fun patchNodes(nodes: List<CustomNode>) {
        adapter.patchDataSet(adapter::nodes, nodes, id = { it.id })
    }

    suspend fun confirmDelete(node: CustomNode): Boolean = suspendCancellableCoroutine { continuation ->
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.delete_custom_node)
            .setMessage(context.getString(R.string.delete_custom_node_warning, node.name))
            .setNegativeButton(R.string.cancel) { _, _ -> }
            .setPositiveButton(R.string.delete) { _, _ -> continuation.resume(true) }
            .setOnDismissListener {
                if (continuation.isActive) continuation.resume(false)
            }
            .show()

        continuation.invokeOnCancellation { dialog.dismiss() }
    }
}
