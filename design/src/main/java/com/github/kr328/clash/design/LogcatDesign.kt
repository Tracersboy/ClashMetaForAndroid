package com.github.kr328.clash.design

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.getSystemService
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.kr328.clash.core.model.LogMessage
import com.github.kr328.clash.design.adapter.LogMessageAdapter
import com.github.kr328.clash.design.databinding.DesignLogcatBinding
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogcatDesign(
    context: Context,
    private val streaming: Boolean,
    private val onRuleRequested: (String) -> Unit,
) : Design<LogcatDesign.Request>(context) {
    enum class Request {
        Close, Delete, Export
    }

    private val binding = DesignLogcatBinding
        .inflate(context.layoutInflater, context.root, false)
    private val adapter = LogMessageAdapter(context) { anchor, log ->
        val destination = connectionDestination(log.message)
        if (destination == null) {
            copyLog(log)
        } else {
            PopupMenu(context, anchor).apply {
                menu.add(R.string.copy_log).setOnMenuItemClickListener {
                    copyLog(log)
                    true
                }
                menu.add(R.string.new_global_rule).setOnMenuItemClickListener {
                    onRuleRequested(destination)
                    true
                }
            }.show()
        }
    }

    private fun copyLog(log: LogMessage) {
        launch {
            val data = ClipData.newPlainText("log_message", log.message)
            context.getSystemService<ClipboardManager>()?.setPrimaryClip(data)
            showToast(R.string.copied, ToastDuration.Short)
        }
    }

    suspend fun patchMessages(messages: List<LogMessage>, removed: Int, appended: Int) {
        withContext(Dispatchers.Main) {
            adapter.messages = messages

            adapter.notifyItemRangeInserted(adapter.messages.size, appended)
            adapter.notifyItemRangeRemoved(0, removed)

            if (streaming && binding.recyclerList.isTop) {
                binding.recyclerList.scrollToPosition(messages.size - 1)
            }
        }
    }

    override val root: View
        get() = binding.root

    init {
        binding.self = this
        binding.streaming = streaming

        binding.activityBarLayout.applyFrom(context)

        binding.recyclerList.bindAppBarElevation(binding.activityBarLayout)

        binding.recyclerList.layoutManager = LinearLayoutManager(context).apply {
            if (streaming) {
                reverseLayout = true
                stackFromEnd = true
            }
        }
        binding.recyclerList.adapter = adapter
    }
}

// The core reports the actual destination before the match description. A rule
// payload (for example a domain suffix) is not necessarily that destination.
internal fun connectionDestination(message: String): String? {
    if (!message.startsWith("[TCP] ") && !message.startsWith("[UDP] ")) return null
    val endpoint = message.substringAfter(" --> ", "").substringBefore(' ')
    val separator = endpoint.lastIndexOf(':')
    if (separator <= 0) return null
    val port = endpoint.substring(separator + 1).toIntOrNull() ?: return null
    if (port !in 1..65535) return null
    val host = endpoint.substring(0, separator).removeSurrounding("[", "]")
    return host.takeIf { it.isNotEmpty() && !it.any { ch -> ch.isWhitespace() || ch in ",/<>[]" } }
}
