package com.github.kr328.clash.service.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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
) {
    fun toConfig(): JsonObject = buildJsonObject {
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
