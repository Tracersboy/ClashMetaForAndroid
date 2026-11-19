package com.github.kr328.clash

import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.SettingsDesign
import com.github.kr328.clash.design.dialog.confirmLocalAction
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.model.CustomNode
import com.github.kr328.clash.service.store.LocalConfigBackup
import com.github.kr328.clash.service.store.LocalConfigStore
import com.github.kr328.clash.service.util.sendOverrideChanged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

class SettingsActivity : BaseActivity<SettingsDesign>() {
    override suspend fun main() {
        val design = SettingsDesign(this)

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {

                }
                design.requests.onReceive {
                    when (it) {
                        SettingsDesign.Request.StartApp ->
                            startActivity(AppSettingsActivity::class.intent)
                        SettingsDesign.Request.StartNetwork ->
                            startActivity(NetworkSettingsActivity::class.intent)
                        SettingsDesign.Request.StartCustomNodes ->
                            startActivity(CustomNodesActivity::class.intent)
                        SettingsDesign.Request.StartGlobalRules ->
                            startActivity(GlobalRulesActivity::class.intent)
                        SettingsDesign.Request.BackupAndRestore ->
                            when (design.requestBackupAction()) {
                                SettingsDesign.BackupAction.Export -> exportLocalBackup(design)
                                SettingsDesign.BackupAction.Restore -> restoreLocalBackup(design)
                                null -> Unit
                            }
                        SettingsDesign.Request.StartOverride ->
                            startActivity(OverrideSettingsActivity::class.intent)
                        SettingsDesign.Request.StartMetaFeature ->
                            startActivity(MetaFeatureSettingsActivity::class.intent)
                    }
                }
            }
        }
    }

    private suspend fun exportLocalBackup(design: SettingsDesign) {
        try {
            val document = startActivityForResult(
                ActivityResultContracts.CreateDocument("application/json"),
                "cmfa-local-configuration.json",
            ) ?: return
            withContext(Dispatchers.IO) {
                val text = LocalConfigBackup.from(LocalConfigStore(this@SettingsActivity).read()).encode()
                checkNotNull(contentResolver.openOutputStream(document, "wt")).bufferedWriter().use {
                    it.write(text)
                }
            }
            design.showToast(R.string.file_exported, ToastDuration.Long)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            design.showToast(R.string.local_backup_export_error, ToastDuration.Long)
        }
    }

    private suspend fun restoreLocalBackup(design: SettingsDesign) {
        try {
            val document = startActivityForResult(
                ActivityResultContracts.OpenDocument(),
                arrayOf("application/json", "text/plain", "application/octet-stream"),
            ) ?: return
            val backup = withContext(Dispatchers.IO) {
                val text = checkNotNull(contentResolver.openInputStream(document)).bufferedReader().use {
                    it.readText()
                }
                LocalConfigBackup.decode(text).also { validateBackup(it) }
            }
            val message = getString(R.string.local_backup_restore_preview, backup.nodes.size, backup.rules.size)
            if (!confirmLocalAction(R.string.local_backup_restore, message)) return

            withContext(Dispatchers.IO) {
                LocalConfigStore(this@SettingsActivity).restore(backup)
            }
            sendOverrideChanged()
            design.showToast(R.string.local_backup_restored, ToastDuration.Short)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            design.showToast(R.string.local_backup_restore_error, ToastDuration.Long)
        }
    }

    private fun validateBackup(backup: LocalConfigBackup) {
        if (backup.nodes.isNotEmpty()) {
            val input = JsonArray(backup.nodes.map(CustomNode::toConfig)).toString()
            val parsed = Clash.parseLocalInput(input, rules = false)
            require(parsed.error == null && parsed.nodes.size == backup.nodes.size)
        }
        if (backup.rules.isNotEmpty()) {
            val input = JsonArray(backup.rules.map { JsonPrimitive(it.definition.line()) }).toString()
            val parsed = Clash.parseLocalInput(input, rules = true)
            require(parsed.error == null && parsed.rules.size == backup.rules.size)
        }
    }
}
