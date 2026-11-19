package com.github.kr328.clash

import com.github.kr328.clash.core.model.GlobalRule
import com.github.kr328.clash.core.model.LocalConfigReport
import com.github.kr328.clash.design.GlobalRulesDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.dialog.confirmLocalAction
import com.github.kr328.clash.service.store.LocalConfigStore
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.sendOverrideChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.Collections

class GlobalRulesActivity : BaseActivity<GlobalRulesDesign>() {
    override suspend fun main() {
        val local = LocalConfigStore(this)
        val profileStore = ServiceStore(this)
        val design = GlobalRulesDesign(this)
        var report: LocalConfigReport? = null

        suspend fun refresh() {
            val state = withContext(Dispatchers.IO) { local.read() }
            val active = withContext(Dispatchers.IO) { profileStore.activeProfile }
            report = if (clashRunning && state.applied?.profile == active?.toString()) {
                state.applied?.report
            } else {
                inspectLocalConfig(state)?.second
            }
            val issues = report?.ruleIssues.orEmpty().associate { it.id to it.reason }
            design.patchRules(state.rules.map { rule ->
                val issue = when (issues[rule.id].takeIf { rule.enabled }) {
                    "target" -> getString(R.string.global_rule_missing_target)
                    "rule_set" -> getString(R.string.global_rule_missing_set)
                    "sub_rule" -> getString(R.string.global_rule_missing_sub_rule)
                    "invalid" -> getString(R.string.global_rule_invalid)
                    else -> null
                }
                GlobalRulesDesign.RuleRow(rule.id, rule.definition.line(state.targetFor(rule)), issue, rule.enabled)
            })
        }

        suspend fun change(edit: (List<GlobalRule>) -> List<GlobalRule>) {
            withContext(Dispatchers.IO) { local.editRules(edit) }
            sendOverrideChanged()
            refresh()
        }

        setContentDesign(design)
        refresh()

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    if (it == Event.ActivityStart || it == Event.ProfileChanged || it == Event.ProfileLoaded || it == Event.ClashStop) {
                        refresh()
                    }
                }
                design.requests.onReceive {
                    when (val request = it) {
                        is GlobalRulesDesign.Request.Add -> {
                            val input = requestLocalInput(
                                if (request.batch) R.string.import_global_rules else R.string.new_global_rule,
                                rules = true,
                                single = !request.batch,
                            )
                            if (input != null && confirmCatchAll(input.rules)) {
                                val new = input.rules.map { newGlobalRule(it, report?.nodeNames.orEmpty()) }
                                change { new + it }
                            }
                        }
                        is GlobalRulesDesign.Request.Edit -> {
                            val state = withContext(Dispatchers.IO) { local.read() }
                            val previous = state.rules.firstOrNull { it.id == request.id } ?: return@onReceive
                            val oldTarget = state.targetFor(previous)
                            val input = requestLocalInput(
                                R.string.edit_global_rule, rules = true, single = true,
                                initial = previous.definition.line(oldTarget),
                            )
                            if (input != null && confirmCatchAll(input.rules)) {
                                val definition = input.rules.single()
                                val edited = newGlobalRule(definition, report?.nodeNames.orEmpty(), previous)
                                val preserveReference = definition.type != "SUB-RULE" && definition.target == oldTarget
                                val rule = if (preserveReference && previous.nodeId != null) edited.copy(nodeId = previous.nodeId)
                                else edited
                                change { rules -> rules.map { if (it.id == previous.id) rule else it } }
                            }
                        }
                        is GlobalRulesDesign.Request.Move -> change { rules ->
                            val from = rules.indexOfFirst { it.id == request.id }
                            val to = from + request.offset
                            if (from in rules.indices && to in rules.indices) rules.toMutableList().also { Collections.swap(it, from, to) }
                            else rules
                        }
                        is GlobalRulesDesign.Request.Toggle -> change { rules ->
                            rules.map { if (it.id == request.id) it.copy(enabled = request.enabled) else it }
                        }
                        is GlobalRulesDesign.Request.Delete ->
                            if (confirmLocalAction(R.string.delete_global_rule, getString(R.string.delete_global_rule_warning))) {
                                change { rules -> rules.filterNot { it.id == request.id } }
                            }
                    }
                }
            }
        }
    }
}
