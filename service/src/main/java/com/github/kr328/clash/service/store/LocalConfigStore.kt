package com.github.kr328.clash.service.store

import android.content.Context
import android.util.AtomicFile
import com.github.kr328.clash.core.model.GlobalRule
import com.github.kr328.clash.core.model.LocalConfig
import com.github.kr328.clash.core.model.LocalConfigReport
import com.github.kr328.clash.core.model.LocalProxy
import com.github.kr328.clash.core.model.NodeRename
import com.github.kr328.clash.service.model.CustomNode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.util.UUID

@Serializable
data class SavedLocalConfig(
    val nodes: List<CustomNode> = emptyList(),
    val rules: List<GlobalRule> = emptyList(),
    val renameNotices: List<NodeRename> = emptyList(),
    val applied: AppliedLocalConfig? = null,
) {
    fun forCore() = LocalConfig(
        nodes = nodes.map { LocalProxy(it.id, it.toConfig(), it.enabled, it.conflictBase) },
        rules = rules.filter(GlobalRule::enabled),
    )

    fun targetFor(rule: GlobalRule): String =
        nodes.firstOrNull { it.id == rule.nodeId }?.name ?: rule.definition.target
}

@Serializable
data class AppliedLocalConfig(val profile: String, val report: LocalConfigReport)

class LocalConfigStore(private val context: Context) {
    private val data = AtomicFile(context.filesDir.resolve("local_configuration.json"))
    private val lockFile = context.filesDir.resolve("local_configuration.lock")

    // UI edits and profile processing can run in different Android processes.
    private fun <T> locked(action: () -> T): T = synchronized(processLock) {
        RandomAccessFile(lockFile, "rw").use { file ->
            file.channel.lock().use { action() }
        }
    }

    private fun readLocked(): SavedLocalConfig {
        val bytes = try {
            data.readFully()
        } catch (_: FileNotFoundException) {
            val legacy = ServiceStore(context).legacyCustomNodes
            if (legacy.isEmpty()) return SavedLocalConfig()
            val used = mutableSetOf<String>()
            val migrated = SavedLocalConfig(nodes = legacy.map {
                if (it.id.isNotEmpty() && used.add(it.id)) it else it.copy(id = UUID.randomUUID().toString())
            })
            writeLocked(migrated)
            return migrated
        }
        return json.decodeFromString(SavedLocalConfig.serializer(), bytes.toString(Charsets.UTF_8))
    }

    private fun writeLocked(state: SavedLocalConfig) {
        val bytes = json.encodeToString(SavedLocalConfig.serializer(), state).toByteArray(Charsets.UTF_8)
        val stream = data.startWrite()
        try {
            stream.write(bytes)
            data.finishWrite(stream)
        } catch (exception: Exception) {
            data.failWrite(stream)
            throw exception
        }
    }

    fun read(): SavedLocalConfig = locked(::readLocked)

    private fun update(transform: (SavedLocalConfig) -> SavedLocalConfig): SavedLocalConfig = locked {
        val current = readLocked()
        val next = transform(current)
        if (next != current) writeLocked(next)
        next
    }

    fun saveNodes(input: List<CustomNode>): SavedLocalConfig = update { state ->
        val changes = input.mapTo(mutableSetOf()) { it.id }
        val occupied = state.nodes.filter { it.id !in changes }.mapTo(CustomNode.RESERVED_NAMES.toMutableSet()) { it.name }
        val requestedNames = input.mapTo(mutableSetOf()) { it.name }
        val notices = mutableListOf<NodeRename>()
        val saved = input.associate { node ->
            if (occupied.add(node.name)) {
                node.id to node
            } else {
                val base = node.conflictBase ?: node.name
                val name = generateSequence(1) { it + 1 }.map {
                    if (it == 1) "${base}冲突" else "${base}冲突$it"
                }.first { it !in occupied && it !in requestedNames }
                occupied.add(name)
                notices += NodeRename(node.id, node.name, name, base)
                node.id to node.copy(name = name, conflictBase = base)
            }
        }
        val previousIds = state.nodes.mapTo(mutableSetOf()) { it.id }
        val existing = state.nodes.map { node -> saved[node.id]?.copy(enabled = node.enabled) ?: node }
        state.copy(nodes = existing + saved.values.filter { it.id !in previousIds }, renameNotices = state.renameNotices + notices)
    }

    fun setEnabled(id: String, enabled: Boolean): SavedLocalConfig = update { state ->
        state.copy(nodes = state.nodes.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun deleteNode(id: String): SavedLocalConfig = update { state ->
        state.copy(nodes = state.nodes.filterNot { it.id == id })
    }

    fun editRules(edit: (List<GlobalRule>) -> List<GlobalRule>): SavedLocalConfig = update { state ->
        state.copy(rules = edit(state.rules))
    }

    fun restore(backup: LocalConfigBackup): SavedLocalConfig = update { state ->
        state.copy(nodes = backup.nodes, rules = backup.rules, renameNotices = emptyList())
    }

    fun acceptReport(report: LocalConfigReport, profile: UUID, applied: Boolean): Boolean {
        var changed = false
        update { state ->
            val occupied = state.nodes.mapTo(mutableSetOf()) { it.name }
            val byId = report.renames.associateBy { it.id }
            val notices = mutableListOf<NodeRename>()
            val nodes = state.nodes.map { node ->
                val rename = byId[node.id]
                if (rename != null && node.enabled && node.name == rename.from && occupied.add(rename.to)) {
                    occupied.remove(node.name)
                    notices += rename
                    node.copy(name = rename.to, conflictBase = rename.base)
                } else {
                    node
                }
            }
            changed = notices.isNotEmpty()
            state.copy(
                nodes = nodes,
                renameNotices = state.renameNotices + notices,
                applied = if (applied) AppliedLocalConfig(profile.toString(), report.copy(renames = emptyList())) else state.applied,
            )
        }
        return changed
    }

    fun takeRenameNotices(): List<NodeRename> {
        var notices = emptyList<NodeRename>()
        update {
            notices = it.renameNotices
            it.copy(renameNotices = emptyList())
        }
        return notices
    }

    companion object {
        private val processLock = Any()
        private val json = Json { ignoreUnknownKeys = true }
        private const val localSelectionPrefix = "\u0000cmfa-local:"

        fun selectionForNode(id: String): String = localSelectionPrefix + id

        fun selectedNode(value: String): String? =
            value.takeIf { it.startsWith(localSelectionPrefix) }?.removePrefix(localSelectionPrefix)
    }
}
