package com.github.kr328.clash

import com.github.kr328.clash.design.CustomNodesDesign
import com.github.kr328.clash.service.model.CustomNode
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.sendOverrideChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.UUID

class CustomNodesActivity : BaseActivity<CustomNodesDesign>() {
    override suspend fun main() {
        val store = ServiceStore(this)
        var nodes = withContext(Dispatchers.IO) { store.customNodes }
        val design = CustomNodesDesign(this)

        design.patchNodes(nodes)
        setContentDesign(design)

        suspend fun persist(updated: List<CustomNode>) {
            withContext(Dispatchers.IO) {
                store.customNodes = updated
            }

            nodes = updated
            design.patchNodes(nodes)
            sendOverrideChanged()
        }

        while (isActive) {
            select<Unit> {
                events.onReceive {}
                design.requests.onReceive {
                    when (val request = it) {
                        CustomNodesDesign.Request.Create -> {
                            val node = design.requestNode(
                                initial = null,
                                existingNames = nodes.mapTo(mutableSetOf(), CustomNode::name),
                            )

                            if (node != null) {
                                persist(nodes + node.copy(id = UUID.randomUUID().toString()))
                            }
                        }

                        is CustomNodesDesign.Request.Edit -> {
                            val node = design.requestNode(
                                initial = request.node,
                                existingNames = nodes
                                    .filterNot { current -> current.id == request.node.id }
                                    .mapTo(mutableSetOf(), CustomNode::name),
                            )

                            if (node != null) {
                                persist(nodes.map { current ->
                                    if (current.id == node.id) node else current
                                })
                            }
                        }

                        is CustomNodesDesign.Request.Delete -> {
                            if (design.confirmDelete(request.node)) {
                                persist(nodes.filterNot { current -> current.id == request.node.id })
                            }
                        }
                    }
                }
            }
        }
    }
}
