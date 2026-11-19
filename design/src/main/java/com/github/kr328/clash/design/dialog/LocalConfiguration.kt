package com.github.kr328.clash.design.dialog

import android.content.Context
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doOnTextChanged
import com.github.kr328.clash.core.model.RuleDefinition
import com.github.kr328.clash.design.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

suspend fun Context.requestLocalText(title: Int, hint: Int, initial: String = ""): String? =
    suspendCancellableCoroutine { continuation ->
        val input = EditText(this).apply {
            setHint(hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 4
            maxLines = 12
            setText(initial)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(localDialogFields(input))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ -> continuation.resume(input.text.toString()) }
            .setOnDismissListener { if (continuation.isActive) continuation.resume(null) }
            .create()
        continuation.invokeOnCancellation { dialog.dismiss() }
        dialog.show()
    }

suspend fun Context.confirmLocalAction(title: Int, message: CharSequence): Boolean =
    suspendCancellableCoroutine { continuation ->
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ -> continuation.resume(true) }
            .setOnDismissListener { if (continuation.isActive) continuation.resume(false) }
            .show()
        continuation.invokeOnCancellation { dialog.dismiss() }
    }

suspend fun Context.requestRuleFromConnection(destination: String, targets: List<String>): RuleDefinition? =
    suspendCancellableCoroutine { continuation ->
        val ipv6 = ':' in destination
        val ipv4 = !ipv6 && destination.split('.').let { parts ->
            parts.size == 4 && parts.all { it.toIntOrNull()?.let { octet -> octet in 0..255 } == true }
        }
        val types = when {
            ipv6 -> listOf("IP-CIDR6")
            ipv4 -> listOf("IP-CIDR")
            else -> listOf("DOMAIN", "DOMAIN-SUFFIX", "DOMAIN-KEYWORD")
        }
        val type = Spinner(this).apply {
            adapter = ArrayAdapter(this@requestRuleFromConnection, android.R.layout.simple_spinner_dropdown_item, types)
            contentDescription = getString(R.string.global_rule_type)
        }
        val match = EditText(this).apply {
            setHint(R.string.global_rule_match)
            setSingleLine()
            setText(when {
                ipv6 -> "$destination/128"
                ipv4 -> "$destination/32"
                else -> destination
            })
        }
        val target = AutoCompleteTextView(this).apply {
            setHint(R.string.global_rule_target)
            setSingleLine()
            threshold = 1
            setAdapter(ArrayAdapter(this@requestRuleFromConnection, android.R.layout.simple_dropdown_item_1line, targets))
            setOnClickListener { if (targets.isNotEmpty()) showDropDown() }
        }
        val label = TextView(this).apply { setText(R.string.global_rule_type) }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.new_global_rule)
            .setView(localDialogFields(label, type, match, target))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                continuation.resume(
                    RuleDefinition(type.selectedItem.toString(), match.text.toString().trim(), target.text.toString().trim())
                )
            }
            .setOnDismissListener { if (continuation.isActive) continuation.resume(null) }
            .create()

        fun update() {
            val pattern = match.text.toString().trim()
            val policy = target.text.toString().trim()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled =
                pattern.isNotBlank() && policy.isNotBlank() && ',' !in pattern && ',' !in policy
        }
        dialog.setOnShowListener {
            match.doOnTextChanged { _, _, _, _ -> update() }
            target.doOnTextChanged { _, _, _, _ -> update() }
            update()
        }
        continuation.invokeOnCancellation { dialog.dismiss() }
        dialog.show()
    }

private fun Context.localDialogFields(vararg fields: android.view.View) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    val padding = resources.getDimensionPixelSize(R.dimen.item_header_margin)
    setPadding(padding, padding, padding, 0)
    fields.forEach {
        addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }
}
