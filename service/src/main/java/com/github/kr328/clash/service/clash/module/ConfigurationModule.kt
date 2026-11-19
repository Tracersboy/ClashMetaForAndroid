package com.github.kr328.clash.service.clash.module

import android.app.Service
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.store.LocalConfigStore
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.sendProfileLoaded
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import java.util.*

class ConfigurationModule(service: Service) : Module<ConfigurationModule.LoadException>(service) {
    data class LoadException(val message: String)

    private val store = ServiceStore(service)
    private val localConfig = LocalConfigStore(service)
    private val reload = Channel<Unit>(Channel.CONFLATED)

    override suspend fun run() {
        val broadcasts = receiveBroadcast {
            addAction(Intents.ACTION_PROFILE_CHANGED)
            addAction(Intents.ACTION_OVERRIDE_CHANGED)
        }

        var loaded: UUID? = null

        reload.trySend(Unit)

        while (true) {
            val changed: UUID? = select {
                broadcasts.onReceive {
                    if (it.action == Intents.ACTION_PROFILE_CHANGED)
                        UUID.fromString(it.getStringExtra(Intents.EXTRA_UUID))
                    else
                        null
                }
                reload.onReceive {
                    null
                }
            }

            try {
                val current = store.activeProfile
                    ?: throw NullPointerException("No profile selected")

                if (current == loaded && changed != null && changed != loaded)
                    continue

                loaded = current

                val active = ImportedDao().queryByUUID(current)
                    ?: throw NullPointerException("No profile selected")

                Clash.setAgeSecretKey(active.ageSecretKey?.takeIf { it.isNotBlank() })
                val settings = localConfig.read()
                val report = Clash.load(service.importedDir.resolve(active.uuid.toString()), settings.forCore()).await()
                localConfig.acceptReport(report, active.uuid, applied = true)

                val oldNames = settings.applied?.takeIf { it.profile == active.uuid.toString() }
                    ?.report?.nodeNames.orEmpty()
                val selections = SelectionDao()
                val remove = selections.querySelections(active.uuid).mapNotNull { selection ->
                    val nodeId = LocalConfigStore.selectedNode(selection.selected)
                        ?: oldNames.entries.firstOrNull { it.value == selection.selected }?.key
                    val name = if (nodeId == null) selection.selected else report.nodeNames[nodeId]
                    if (name == null || !Clash.patchSelector(selection.proxy, name, closeConnections = false)) {
                        selection.proxy
                    } else {
                        if (nodeId != null && LocalConfigStore.selectedNode(selection.selected) == null) {
                            selections.setSelected(selection.copy(selected = LocalConfigStore.selectionForNode(nodeId)))
                        }
                        null
                    }
                }
                selections.removeSelections(active.uuid, remove)

                StatusProvider.currentProfile = active.name

                service.sendProfileLoaded(current)

                Log.d("Profile ${active.name} loaded")
            } catch (e: Exception) {
                return enqueueEvent(LoadException(e.message ?: "Unknown"))
            }
        }
    }
}
