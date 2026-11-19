package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.widget.doOnTextChanged
import com.github.kr328.clash.design.adapter.CustomNodeAdapter
import com.github.kr328.clash.design.databinding.CustomNodeDialogBinding
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
        object Create : Request()
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
        PopupMenu(context, binding.addView).apply {
            menu.add(R.string.new_custom_node).setOnMenuItemClickListener {
                requests.trySend(Request.Create)
                true
            }
            menu.add(R.string.import_custom_nodes).setOnMenuItemClickListener {
                requests.trySend(Request.Import)
                true
            }
        }.show()
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

    suspend fun requestNode(initial: CustomNode?): CustomNode? {
        return suspendCancellableCoroutine { continuation ->
            val binding = CustomNodeDialogBinding.inflate(context.layoutInflater, context.root, false)
            val source = initial ?: CustomNode()

            binding.nameView.setText(source.name)
            binding.serverView.setText(source.server)
            binding.portView.setText(source.port.toString())
            binding.passwordView.setText(source.password)
            binding.clientFingerprintView.setText(source.clientFingerprint)
            binding.sniView.setText(source.sni)
            binding.alpnView.setText(source.alpn.joinToString(", "))
            binding.udpView.isChecked = source.udp
            binding.skipCertVerifyView.isChecked = source.skipCertVerify

            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle(if (initial == null) R.string.new_custom_node else R.string.edit_custom_node)
                .setView(binding.root)
                .setNegativeButton(R.string.cancel) { _, _ -> }
                .setPositiveButton(R.string.save) { _, _ ->
                    val name = binding.nameView.text?.toString()?.trim().orEmpty()
                    val result = source.copy(
                        name = name,
                        conflictBase = source.conflictBase.takeIf { source.name == name },
                        server = binding.serverView.text?.toString()?.trim().orEmpty(),
                        port = binding.portView.text?.toString()?.trim()?.toInt() ?: source.port,
                        password = binding.passwordView.text?.toString().orEmpty(),
                        clientFingerprint = binding.clientFingerprintView.text?.toString()?.trim().orEmpty(),
                        sni = binding.sniView.text?.toString()?.trim().orEmpty(),
                        alpn = binding.alpnView.text?.toString()
                            .orEmpty()
                            .split(Regex("[,\\s]+"))
                            .map(String::trim)
                            .filter(String::isNotEmpty),
                        udp = binding.udpView.isChecked,
                        skipCertVerify = binding.skipCertVerifyView.isChecked,
                    )

                    continuation.resume(result)
                }
                .setOnDismissListener {
                    if (continuation.isActive) continuation.resume(null)
                }
                .create()

            fun validate(): Boolean {
                val name = binding.nameView.text?.toString()?.trim().orEmpty()
                val port = binding.portView.text?.toString()?.trim()?.toIntOrNull()

                binding.nameLayout.error = when {
                    name.isBlank() -> context.getString(R.string.should_not_be_blank)
                    else -> null
                }
                binding.serverLayout.error = if (binding.serverView.text?.isBlank() != false) {
                    context.getString(R.string.should_not_be_blank)
                } else {
                    null
                }
                binding.portLayout.error = if (port == null || port !in 1..65535) {
                    context.getString(R.string.invalid_custom_node_port)
                } else {
                    null
                }
                binding.passwordLayout.error = if (binding.passwordView.text?.isBlank() != false) {
                    context.getString(R.string.should_not_be_blank)
                } else {
                    null
                }

                return binding.nameLayout.error == null &&
                        binding.serverLayout.error == null &&
                        binding.portLayout.error == null &&
                        binding.passwordLayout.error == null
            }

            fun updateValidation() {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = validate()
            }

            dialog.setOnShowListener {
                listOf(
                    binding.nameView,
                    binding.serverView,
                    binding.portView,
                    binding.passwordView,
                ).forEach { field ->
                    field.doOnTextChanged { _, _, _, _ -> updateValidation() }
                }

                updateValidation()
            }

            continuation.invokeOnCancellation { dialog.dismiss() }
            dialog.show()
        }
    }
}
