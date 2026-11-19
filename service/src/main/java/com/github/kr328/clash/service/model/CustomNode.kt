package com.github.kr328.clash.service.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

@Serializable
data class CustomNode(
    val id: String = "",
    val name: String = "",
    val server: String = "",
    val port: Int = 443,
    val password: String = "",
    val clientFingerprint: String = "chrome",
    val udp: Boolean = true,
    val sni: String = "",
    val alpn: List<String> = listOf("h2", "http/1.1"),
    val skipCertVerify: Boolean = false,
    val enabled: Boolean = true,
    val config: JsonObject? = null,
    val conflictBase: String? = null,
) {
    val protocol: String get() = (config?.get("type") as? JsonPrimitive)?.contentOrNull ?: TYPE

    val address: String get() {
        if (config == null) return "$server:$port"
        val host = (config["server"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val remotePort = (config["port"] as? JsonPrimitive)?.contentOrNull
        return if (host.isNotEmpty() && remotePort != null) "$host:$remotePort" else host
    }

    fun toConfig(): JsonObject = config?.let { JsonObject(it + ("name" to JsonPrimitive(name))) } ?: buildJsonObject {
        put("name", name)
        put("type", TYPE)
        put("server", server)
        put("port", port)
        put("password", password)
        put("udp", udp)
        put("skip-cert-verify", skipCertVerify)

        if (clientFingerprint.isNotBlank()) {
            put("client-fingerprint", clientFingerprint)
        }
        if (sni.isNotBlank()) {
            put("sni", sni)
        }
        if (alpn.isNotEmpty()) {
            put("alpn", JsonArray(alpn.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }
    }

    companion object {
        const val TYPE = "anytls"

        fun fromConfig(id: String, config: JsonObject): CustomNode =
            CustomNode(id = id, name = (config.getValue("name") as JsonPrimitive).content, config = config)

        val RESERVED_NAMES = setOf(
            "DIRECT",
            "REJECT",
            "REJECT-DROP",
            "COMPATIBLE",
            "PASS",
            "PASS-RULE",
            "GLOBAL",
        )
    }
}
