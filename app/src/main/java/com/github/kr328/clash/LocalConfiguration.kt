package com.github.kr328.clash

import android.content.Context
import android.widget.Toast
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.bridge.ClashException
import com.github.kr328.clash.core.model.GlobalRule
import com.github.kr328.clash.core.model.LocalConfigReport
import com.github.kr328.clash.core.model.LocalInput
import com.github.kr328.clash.core.model.RuleDefinition
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.dialog.confirmLocalAction
import com.github.kr328.clash.design.dialog.requestLocalText
import com.github.kr328.clash.design.dialog.requestRuleFromConnection
import com.github.kr328.clash.service.store.LocalConfigStore
import com.github.kr328.clash.service.store.SavedLocalConfig
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.sendOverrideChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

internal suspend fun Context.inspectLocalConfig(settings: SavedLocalConfig): Pair<UUID, LocalConfigReport>? =
    withContext(Dispatchers.IO) {
        val profile = ServiceStore(this@inspectLocalConfig).activeProfile ?: return@withContext null
        val folder = importedDir.resolve(profile.toString())
        if (!folder.resolve("config.yaml").isFile) return@withContext null
        try {
            profile to Clash.load(folder, settings.forCore(), apply = false).await()
        } catch (_: ClashException) {
            null
        }
    }

internal suspend fun Context.requestLocalInput(
    title: Int,
    rules: Boolean,
    single: Boolean = false,
    initial: String = "",
): LocalInput? {
    var draft = initial
    while (true) {
        draft = requestLocalText(title, if (rules) R.string.global_rule_input_hint else R.string.custom_node_yaml_hint, draft)
            ?: return null
        val parsed = withContext(Dispatchers.IO) { Clash.parseLocalInput(draft, rules) }
        val count = if (rules) parsed.rules.size else parsed.nodes.size
        if (parsed.error == null && (!single || count == 1)) return parsed
        val message = when {
            parsed.entry > 0 -> getString(
                if (rules) R.string.invalid_global_rule_entry else R.string.invalid_custom_node_entry,
                parsed.entry,
            )
            parsed.error == null -> getString(R.string.local_configuration_one_entry)
            else -> getString(if (rules) R.string.invalid_global_rule_input else R.string.invalid_custom_node_input)
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}

internal fun newGlobalRule(
    definition: RuleDefinition,
    nodeNames: Map<String, String>,
    previous: GlobalRule? = null,
): GlobalRule {
    val nodeId = if (definition.type == "SUB-RULE") null
    else nodeNames.entries.firstOrNull { it.value == definition.target }?.key
    return GlobalRule(previous?.id ?: UUID.randomUUID().toString(), definition, nodeId)
}

internal suspend fun Context.confirmCatchAll(definitions: List<RuleDefinition>): Boolean =
    definitions.none { it.type == "MATCH" } || confirmLocalAction(
        R.string.global_rule_catch_all,
        getString(R.string.global_rule_catch_all_warning),
    )

internal suspend fun Context.addGlobalRuleFromLog(destination: String) {
    val store = LocalConfigStore(this)
    val state = withContext(Dispatchers.IO) { store.read() }
    val report = inspectLocalConfig(state)?.second
    val definition = requestRuleFromConnection(destination, report?.targets ?: listOf("DIRECT", "REJECT")) ?: return
    val parsed = withContext(Dispatchers.IO) { Clash.parseLocalInput(definition.line(), rules = true) }
    val rule = parsed.rules.singleOrNull()
    if (rule == null) {
        Toast.makeText(this, R.string.invalid_global_rule_input, Toast.LENGTH_LONG).show()
        return
    }
    withContext(Dispatchers.IO) {
        store.editRules { listOf(newGlobalRule(rule, report?.nodeNames.orEmpty())) + it }
    }
    sendOverrideChanged()
    Toast.makeText(this, R.string.global_rule_saved, Toast.LENGTH_SHORT).show()
}
