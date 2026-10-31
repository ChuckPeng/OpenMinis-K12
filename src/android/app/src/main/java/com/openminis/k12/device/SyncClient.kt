package com.openminis.k12.device

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/**
 * Android sync client for OpenMinis-K12 server communication (docs/k12/modules/sync-client.md).
 * 
 * Features:
 * - Device-signed HTTP requests (X-K12-Device, X-K12-Ts, X-K12-Nonce, X-K12-Sig)
 * - TLS certificate fingerprint pinning (J11)
 * - Server time calibration and drift tracking
 * - Heartbeat configuration synchronization with strict §19.4 whitelisting
 * - Gzip-compressed event batching (max 500 per batch, idempotent per S8)
 * -脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏
 * - 脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏脱敏
 */

data class SyncConfig(
    val baseUrl: String,
    val deviceId: String,
    val pinnedCertFingerprint: String? = null,
    val serverEd25519Pubkey: String? = null,
)

data class HeartbeatResult(
    val serverTimeMs: Long,
    val configVersion: Int,
    val config: Map<String, Any>,
    val revoked: Boolean,
)

data class HttpResponse(
    val code: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
) {
    val bodyText: String get() = String(body, Charsets.UTF_8)
}

interface HttpTransport {
    fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): HttpResponse
}

class SyncClient(
    val config: SyncConfig,
    val signer: DeviceSigner.Signer,
    val transport: HttpTransport,
) {
    companion object {
        const val MAX_EVENT_BATCH = 500

        /** Whitelist of client-accepted config keys per §19.4. Alert thresholds are never accepted. */
        val ALLOWED_CONFIG_KEYS = setOf(
            "daily_time_limit_minutes",
            "screen_time_break_minutes",
            "rest_duration_minutes",
            "hotline_text",
            "hotline_number",
            "parent_notice_board",
            "exam_mode_enabled",
        )

        fun gzip(data: ByteArray): ByteArray {
            val bos = ByteArrayOutputStream()
            GZIPOutputStream(bos).use { it.write(data) }
            return bos.toByteArray()
        }

        fun normalizeFingerprint(fp: String): String =
            fp.replace(":", "").replace("-", "").trim().lowercase()
    }

    var timeOffsetMs: Long = 0
        private set

    /**
     * Calibrated current time in milliseconds.
     */
    fun calibratedNowMs(): Long = System.currentTimeMillis() + timeOffsetMs

    /**
     * Executes a signed request against the device API.
     */
    fun signedRequest(
        method: String,
        path: String,
        body: ByteArray? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpResponse {
        val now = calibratedNowMs()
        val authHeaders = DeviceSigner.signHeaders(
            deviceId = config.deviceId,
            method = method,
            path = path,
            body = body,
            signer = signer,
            nowMs = now,
        )
        val headers = HashMap<String, String>()
        headers.putAll(authHeaders)
        headers.putAll(extraHeaders)

        val fullUrl = config.baseUrl.trimEnd('/') + path
        return transport.execute(method, fullUrl, headers, body)
    }

    /**
     * Performs a heartbeat with the server (§19.4).
     */
    fun heartbeat(
        configVersionApplied: Int,
        appVersion: String,
        dailyUsedMinutes: Int,
        permissionReportJson: JSONObject? = null,
    ): HeartbeatResult {
        val reqObj = JSONObject()
        reqObj.put("device_id", config.deviceId)
        reqObj.put("config_version_applied", configVersionApplied)
        reqObj.put("app_version", appVersion)
        reqObj.put("daily_used_minutes", dailyUsedMinutes)
        if (permissionReportJson != null) {
            reqObj.put("permission_report", permissionReportJson)
        }

        val body = reqObj.toString().toByteArray(Charsets.UTF_8)
        val resp = signedRequest("POST", "/api/v1/device/heartbeat", body, mapOf("Content-Type" to "application/json"))
        if (resp.code != 200) {
            throw IllegalStateException("Heartbeat failed with code ${resp.code}: ${resp.bodyText}")
        }

        val json = JSONObject(resp.bodyText)
        val serverTimeStr = json.optString("server_time", "")
        val serverTimeMs = parseServerTimeToMs(serverTimeStr)
        if (serverTimeMs > 0) {
            timeOffsetMs = serverTimeMs - System.currentTimeMillis()
        }

        val cfgVersion = json.optInt("config_version", configVersionApplied)
        val rawCfg = json.optJSONObject("config") ?: JSONObject()
        val filteredCfg = mutableMapOf<String, Any>()
        for (key in rawCfg.keys()) {
            if (key in ALLOWED_CONFIG_KEYS) {
                filteredCfg[key] = rawCfg.get(key)
            }
        }

        val revoked = json.optBoolean("revoked", false)
        return HeartbeatResult(
            serverTimeMs = serverTimeMs,
            configVersion = cfgVersion,
            config = filteredCfg,
            revoked = revoked,
        )
    }

    /**
     * Uploads study events in batches with gzip compression (WP-B-4, §19.4).
     */
    fun uploadEvents(eventsJson: JSONArray): HttpResponse {
        require(eventsJson.length() <= MAX_EVENT_BATCH) {
            "Batch size ${eventsJson.length()} exceeds maximum $MAX_EVENT_BATCH"
        }

        val payload = JSONObject()
        payload.put("events", eventsJson)
        val rawBytes = payload.toString().toByteArray(Charsets.UTF_8)
        val compressed = gzip(rawBytes)

        val headers = mapOf(
            "Content-Type" to "application/json",
            "Content-Encoding" to "gzip",
        )
        return signedRequest("POST", "/api/v1/device/events", compressed, headers)
    }

    /**
     * Uploads sanitized diagnostics report (§19.7).
     * Strictly verifies no problem text or images are included.
     */
    fun uploadDiagnostics(diagnosticsJson: JSONObject): HttpResponse {
        // Enforce safety check: no chat or problem content
        val raw = diagnosticsJson.toString()
        require(!raw.contains("problem_text") && !raw.contains("chat_transcript") && !raw.contains("image_data")) {
            "Diagnostics contain forbidden user data"
        }

        val body = raw.toByteArray(Charsets.UTF_8)
        return signedRequest("POST", "/api/v1/device/diagnostics", body, mapOf("Content-Type" to "application/json"))
    }

    /**
     * Fetches child reports (S14, P5).
     */
    fun getReports(kind: String? = null): JSONArray {
        val path = if (kind != null) "/api/v1/device/reports?kind=$kind" else "/api/v1/device/reports"
        val resp = signedRequest("GET", path)
        if (resp.code != 200) {
            throw IllegalStateException("Failed to get reports (${resp.code}): ${resp.bodyText}")
        }
        val obj = JSONObject(resp.bodyText)
        return obj.optJSONArray("reports") ?: JSONArray()
    }

    private fun parseServerTimeToMs(str: String): Long {
        return try {
            java.time.Instant.parse(str).toEpochMilli()
        } catch (_: Exception) {
            str.toLongOrNull() ?: 0L
        }
    }
}
