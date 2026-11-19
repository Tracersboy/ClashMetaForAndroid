package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.databinding.DesignSettingsBinding
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class SettingsDesign(context: Context) : Design<SettingsDesign.Request>(context) {
    enum class Request {
        StartApp, StartNetwork, StartCustomNodes, StartGlobalRules, BackupAndRestore, StartOverride, StartMetaFeature,
    }

    enum class BackupAction { Export, Restore }

    private val binding = DesignSettingsBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)
    }

    fun request(request: Request) {
        requests.trySend(request)
    }

    suspend fun requestBackupAction(): BackupAction? = suspendCancellableCoroutine { continuation ->
        val options = arrayOf(
            context.getString(R.string.local_backup_export),
            context.getString(R.string.local_backup_restore),
        )
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.local_backup_title)
            .setItems(options) { _, index ->
                continuation.resume(if (index == 0) BackupAction.Export else BackupAction.Restore)
            }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { if (continuation.isActive) continuation.resume(null) }
            .show()
        continuation.invokeOnCancellation { dialog.dismiss() }
    }
}
