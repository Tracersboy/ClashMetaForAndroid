package com.github.kr328.clash.service.store

import com.github.kr328.clash.core.model.GlobalRule
import com.github.kr328.clash.service.model.CustomNode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
class LocalConfigBackup private constructor(
    private val format: String,
    private val version: Int,
    val nodes: List<CustomNode>,
    val rules: List<GlobalRule>,
) {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private const val FORMAT = "cmfa-local-configuration"
        private const val VERSION = 1
        private val json = Json {
            encodeDefaults = true
            prettyPrint = true
        }

        fun from(state: SavedLocalConfig): LocalConfigBackup =
            LocalConfigBackup(FORMAT, VERSION, state.nodes, state.rules)

        fun decode(text: String): LocalConfigBackup {
            val backup = json.decodeFromString(serializer(), text.removePrefix("\uFEFF"))
            require(backup.format == FORMAT && backup.version == VERSION)

            val nodeIds = HashSet<String>()
            require(backup.nodes.all { it.id.isNotBlank() && it.name.isNotBlank() && nodeIds.add(it.id) })

            val ruleIds = HashSet<String>()
            require(backup.rules.all { it.id.isNotBlank() && ruleIds.add(it.id) })

            return backup
        }
    }
}
