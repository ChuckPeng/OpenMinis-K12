package com.openminis.k12.device

import org.json.JSONObject
import java.net.URI
import javax.net.ssl.SSLException

/** Chinese messages for the parent pairing form. No hostnames are embedded here. */
object PairingText {
    const val BAD_URL = "请填写以 https:// 开头的服务器地址。"
    const val BAD_CODE = "请输入 6 位配对码。"
    const val INVALID_CODE = "配对码无效、已过期或已经用过。"
    const val NETWORK = "连不上家长服务器。请检查地址和网络后再试。"
    const val TLS = "证书校验失败。请核对管理页上的证书，确认地址是 https。"
    const val CONFLICT = "这个编号已经绑定了另一台平板。请在管理页确认更换。"
    const val REJECTED = "服务器拒绝了这次配对。"
    const val DENIED = "这次配对没有通过。"
    const val NO_KEY = "无法生成这台平板的签名密钥。请再试一次。如果仍然失败，请先解除绑定后再绑定。"
    const val NO_CHILD = "还没有这台平板的编号。请先完成第一次设置。"
    const val QR_MISS = "没有识别到配对二维码。"
    const val PENDING = "已提交，等家长在管理后台/企微确认…"

    const val DEVICE_LABEL = "学生平板"

    fun hostOf(baseUrl: String): String = try {
        URI(baseUrl).host?.takeIf { it.isNotBlank() } ?: baseUrl
    } catch (_: Exception) {
        baseUrl
    }
}

sealed class PairingOutcome {
    data class Active(val endpoint: PairedEndpoint) : PairingOutcome()
    data class Pending(
        val deviceId: String,
        val baseUrl: String,
        val childId: String,
        val certFingerprint: String?,
    ) : PairingOutcome()
    data class Failed(val message: String) : PairingOutcome()
}

/**
 * Tablet side of `POST /api/v1/device/pair` and `GET /api/v1/device/pair/status`.
 * The pair call sends the device public key. The status call is signed with the
 * device id returned by the server. Active status is what the caller passes to
 * [DevicePairing.noteActivated].
 */
class PairingClient(
    private val transport: HttpTransport,
    private val signer: DeviceSigner.Signer,
    private val devicePubkey: String,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    fun submit(
        baseUrl: String,
        pairCode: String,
        childId: String,
        certFingerprint: String? = null,
        label: String = PairingText.DEVICE_LABEL,
    ): PairingOutcome {
        val url = normalizeBaseUrl(baseUrl) ?: return PairingOutcome.Failed(PairingText.BAD_URL)
        val code = normalizePairCode(pairCode) ?: return PairingOutcome.Failed(PairingText.BAD_CODE)
        if (childId.isBlank()) return PairingOutcome.Failed(PairingText.NO_CHILD)
        if (devicePubkey.isBlank()) return PairingOutcome.Failed(PairingText.NO_KEY)
        val body = JSONObject()
            .put("pair_code", code)
            .put("device_pubkey", devicePubkey)
            .put("label", label.ifBlank { PairingText.DEVICE_LABEL })
            .put("child_id", childId)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val posted = try {
            transport.execute(
                "POST",
                url + PAIR_PATH,
                mapOf("Content-Type" to "application/json"),
                body,
            )
        } catch (thrown: Throwable) {
            return PairingOutcome.Failed(classify(thrown))
        }
        if (posted.code != 200) return PairingOutcome.Failed(httpMessage(posted.code, posted.bodyText))
        val deviceId = try {
            JSONObject(posted.bodyText).optString("device_id", "")
        } catch (_: Exception) {
            ""
        }
        if (deviceId.isBlank()) return PairingOutcome.Failed(PairingText.REJECTED)
        val pending = PairingOutcome.Pending(deviceId, url, childId, certFingerprint?.takeIf { it.isNotBlank() })
        return when (val status = poll(pending, waitSeconds = 0)) {
            is PairingOutcome.Failed -> pending
            else -> status
        }
    }

    /**
     * `wait` is the server long-poll in seconds (capped at 25). `0` returns
     * the current status immediately. A network failure keeps the caller on
     * the same pending record; revoked and 401/403 do not.
     */
    fun poll(pending: PairingOutcome.Pending, waitSeconds: Int = 0): PairingOutcome {
        val wait = waitSeconds.coerceIn(0, 25)
        val path = "$STATUS_PATH?wait=$wait"
        val headers = DeviceSigner.signHeaders(
            deviceId = pending.deviceId,
            method = "GET",
            path = path,
            body = null,
            signer = signer,
            nowMs = nowMs(),
        )
        val response = try {
            transport.execute("GET", pending.baseUrl + path, headers, null)
        } catch (thrown: Throwable) {
            return PairingOutcome.Failed(classify(thrown))
        }
        if (response.code == 401 || response.code == 403) {
            return PairingOutcome.Failed(PairingText.DENIED)
        }
        if (response.code != 200) return PairingOutcome.Failed(PairingText.NETWORK)
        val status = try {
            DeviceStatus.fromString(JSONObject(response.bodyText).optString("status", ""))
        } catch (_: Exception) {
            return pending
        }
        return when (status) {
            DeviceStatus.ACTIVE -> PairingOutcome.Active(
                PairedEndpoint(
                    baseUrl = pending.baseUrl,
                    deviceId = pending.deviceId,
                    childId = pending.childId,
                    certFingerprint = pending.certFingerprint,
                ),
            )
            DeviceStatus.REVOKED -> PairingOutcome.Failed(PairingText.DENIED)
            DeviceStatus.PENDING_CONFIRM -> pending
        }
    }

    companion object {
        const val PAIR_PATH = "/api/v1/device/pair"
        const val STATUS_PATH = "/api/v1/device/pair/status"

        /**
         * Instrumented tests set this so a debug build can call the runner over
         * plain http. Release builds ignore it because BuildConfig.DEBUG is false.
         */
        @Volatile
        var allowCleartextForTests: Boolean = false

        fun normalizeBaseUrl(raw: String): String? {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            val uri = try {
                URI(trimmed)
            } catch (_: Exception) {
                return null
            }
            val scheme = uri.scheme?.lowercase()
            val https = scheme == "https"
            val cleartext = scheme == "http" && allowCleartextForTests && com.openminis.app.BuildConfig.DEBUG
            if (!https && !cleartext) return null
            if (uri.host.isNullOrBlank()) return null
            if (!uri.userInfo.isNullOrBlank()) return null
            return trimmed
        }

        fun normalizePairCode(raw: String): String? {
            val digits = raw.trim()
            return digits.takeIf { it.matches(Regex("\\d{6}")) }
        }

        fun classify(thrown: Throwable): String {
            if (thrown is CertificatePinException || thrown is SSLException) return PairingText.TLS
            val text = thrown.message.orEmpty()
            if (text.contains("cert", ignoreCase = true) || text.contains("ssl", ignoreCase = true)) {
                return PairingText.TLS
            }
            return PairingText.NETWORK
        }

        fun httpMessage(code: Int, body: String): String {
            val error = try {
                JSONObject(body).optString("error", "")
            } catch (_: Exception) {
                ""
            }
            return when {
                code == 401 || error == "invalid_pair_code" -> PairingText.INVALID_CODE
                code == 409 || error == "device_conflict" -> PairingText.CONFLICT
                error == "bad_pubkey" -> PairingText.NO_KEY
                else -> PairingText.REJECTED
            }
        }
    }
}
