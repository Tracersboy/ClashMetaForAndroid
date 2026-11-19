package com.github.kr328.clash.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class LocalConfig(
    val nodes: List<LocalProxy> = emptyList(),
    val rules: List<GlobalRule> = emptyList(),
)

@Serializable
data class LocalProxy(
    val id: String,
    val config: JsonObject,
    val enabled: Boolean,
    val conflictBase: String? = null,
)

@Serializable
data class RuleDefinition(
    val type: String,
    val payload: String = "",
    val target: String,
    val params: List<String> = emptyList(),
) {
    fun line(target: String = this.target): String {
        if (type == "MATCH") return "MATCH,$target"
        return (listOf(type, payload, target) + params).joinToString(",")
    }
}

@Serializable
data class GlobalRule(
    val id: String,
    val definition: RuleDefinition,
    val nodeId: String? = null,
    val enabled: Boolean = true,
)

@Serializable
data class NodeRename(
    val id: String,
    val from: String,
    val to: String,
    val base: String,
)

@Serializable
data class LocalRuleIssue(val id: String, val reason: String)

@Serializable
data class LocalConfigReport(
    val renames: List<NodeRename> = emptyList(),
    val nodeNames: Map<String, String> = emptyMap(),
    val targets: List<String> = emptyList(),
    val ruleIssues: List<LocalRuleIssue> = emptyList(),
)

@Serializable
data class LocalInput(
    val nodes: List<JsonObject> = emptyList(),
    val rules: List<RuleDefinition> = emptyList(),
    val error: String? = null,
    val entry: Int = 0,
)
