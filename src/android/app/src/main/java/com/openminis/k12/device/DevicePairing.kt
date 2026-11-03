package com.openminis.k12.device

import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Tablet device pairing models and logic per docs/k12/modules/pairing.md.
 * 
 * Supports both QR code parsing and manual pairing code entry.
 * QR contains: public_base_url, TLS cert fingerprint, server Ed25519 pubkey, and pair code.
 */
data class PairingPayload(
    val baseUrl: String,
    val certFingerprint: String,
    val serverPubkey: String,
    val pairCode: String,
)

data class PairingRegistration(
    val pairCode: String,
    val devicePubkey: String,
    val deviceLabel: String,
    val childId: String,
)

data class PairingResponse(
    val deviceId: String,
    val status: DeviceStatus,
    val wrappedCodeSecret: String,
)

enum class DeviceStatus {
    PENDING_CONFIRM,
    ACTIVE,
    REVOKED;

    companion object {
        fun fromString(s: String?): DeviceStatus = when (s?.lowercase()?.trim()) {
            "active" -> ACTIVE
            "revoked" -> REVOKED
            else -> PENDING_CONFIRM
        }
    }
}

object DevicePairing {

    /**
     * Parses QR code content. Supports both JSON and custom URI schemes:
     * - JSON: {"base_url":"...", "cert_fingerprint":"...", "server_pubkey":"...", "pair_code":"..."}
     * - URI: k12://pair?url=...&cert=...&pubkey=...&code=...
     */
    fun parseQr(content: String): PairingPayload? {
        val trimmed = content.trim()
        if (trimmed.startsWith("{")) {
            return try {
                val json = JSONObject(trimmed)
                PairingPayload(
                    baseUrl = json.optString("base_url", json.optString("url", "")),
                    certFingerprint = json.optString("cert_fingerprint", json.optString("cert", "")),
                    serverPubkey = json.optString("server_pubkey", json.optString("pubkey", "")),
                    pairCode = json.optString("pair_code", json.optString("code", "")),
                ).takeIf { it.baseUrl.isNotBlank() && it.pairCode.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }

        if (trimmed.startsWith("k12://pair") || trimmed.startsWith("k12pair://")) {
            return try {
                val query = trimmed.substringAfter("?", "")
                val params = query.split("&").associate { param ->
                    val parts = param.split("=", limit = 2)
                    val k = URLDecoder.decode(parts[0], "UTF-8")
                    val v = if (parts.size > 1) URLDecoder.decode(parts[1], "UTF-8") else ""
                    k to v
                }
                PairingPayload(
                    baseUrl = params["url"] ?: params["base_url"] ?: "",
                    certFingerprint = params["cert"] ?: params["cert_fingerprint"] ?: "",
                    serverPubkey = params["pubkey"] ?: params["server_pubkey"] ?: "",
                    pairCode = params["code"] ?: params["pair_code"] ?: "",
                ).takeIf { it.baseUrl.isNotBlank() && it.pairCode.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }

        return null
    }

    /**
     * Formats a pairing payload into a JSON string suitable for QR code generation.
     */
    fun formatQrJson(payload: PairingPayload): String {
        val obj = JSONObject()
        obj.put("base_url", payload.baseUrl)
        obj.put("cert_fingerprint", payload.certFingerprint)
        obj.put("server_pubkey", payload.serverPubkey)
        obj.put("pair_code", payload.pairCode)
        return obj.toString()
    }

    /**
     * Formats a pairing payload into URI string.
     */
    fun formatQrUri(payload: PairingPayload): String {
        val enc = { s: String -> URLEncoder.encode(s, "UTF-8") }
        return "k12://pair?url=${enc(payload.baseUrl)}&cert=${enc(payload.certFingerprint)}&pubkey=${enc(payload.serverPubkey)}&code=${enc(payload.pairCode)}"
    }
}
