package com.github.kr328.clash

import com.github.kr328.clash.design.CustomNodesDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.service.model.CustomNode
import com.github.kr328.clash.service.store.LocalConfigStore
import com.github.kr328.clash.service.store.SavedLocalConfig
import com.github.kr328.clash.service.util.sendOverrideChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.UUID

class CustomNodesActivity : BaseActivity<CustomNodesDesign>() {
    override suspend fun main() {
        val store = LocalConfigStore(this)
        val design = CustomNodesDesign(this)
        val prettyJson = Json { prettyPrint = true }

        suspend fun refresh() {
            design.patchNodes(withContext(Dispatchers.IO) { store.read().nodes })
        }
        refresh()
        setContentDesign(design)

        suspend fun applyChange(change: () -> SavedLocalConfig) {
            val running = clashRunning
            val updated = withContext(Dispatchers.IO) {
                val saved = change()
                if (!running) {
                    inspectLocalConfig(saved)?.let { (profile, report) -> store.acceptReport(report, profile, applied = false) }
                }
                store.read()
            }
            design.patchNodes(updated.nodes)
            sendOverrideChanged()
            showLocalRenameNotices()
        }

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    if (it == Event.ProfileChanged || it == Event.ProfileLoaded || it == Event.ActivityStart) refresh()
                }
                design.requests.onReceive {
                    when (val request = it) {
                        CustomNodesDesign.Request.Create -> {
                            val node = design.requestNode(null)
                            if (node != null) {
                                applyChange { store.saveNodes(listOf(node.copy(id = UUID.randomUUID().toString()))) }
                            }
                        }
                        CustomNodesDesign.Request.Import -> {
                            val input = requestLocalInput(R.string.import_custom_nodes, rules = false)
                            if (input != null) {
                                val nodes = input.nodes.map { CustomNode.fromConfig(UUID.randomUUID().toString(), it) }
                                applyChange { store.saveNodes(nodes) }
                            }
                        }
                        is CustomNodesDesign.Request.Edit -> {
                            val old = request.node
                            val updated = if (old.config == null) {
                                design.requestNode(old)
                            } else {
                                val initial = prettyJson.encodeToString(JsonObject.serializer(), old.toConfig())
                                requestLocalInput(R.string.edit_custom_node, rules = false, single = true, initial = initial)
                                    ?.nodes?.single()?.let { CustomNode.fromConfig(old.id, it) }
                                    ?.let { it.copy(conflictBase = old.conflictBase.takeIf { _ -> it.name == old.name }) }
                            }
                            if (updated != null) applyChange { store.saveNodes(listOf(updated)) }
                        }
                        is CustomNodesDesign.Request.Delete ->
                            if (design.confirmDelete(request.node)) applyChange { store.deleteNode(request.node.id) }
                        is CustomNodesDesign.Request.Toggle ->
                            applyChange { store.setEnabled(request.id, request.enabled) }
                    }
                }
            }
        }
    }
}
