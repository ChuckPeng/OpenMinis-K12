package com.openminis.k12.android

import com.openminis.app.network.NetworkHttp
import com.openminis.k12.device.CertificatePinException
import com.openminis.k12.device.HttpResponse
import com.openminis.k12.device.HttpTransport
import com.openminis.k12.device.SyncClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

/**
 * Device API transport. Uses the app network client. Does not log bodies.
 * A pinned fingerprint that does not match the peer certificate aborts the call.
 */
class OkHttpSyncTransport(
    private val pinnedFingerprint: String?,
) : HttpTransport {
    override fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): HttpResponse {
        val base = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
        val client = NetworkHttp.client("k12", base)
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) -> builder.header(name, value) }
        val media = "application/json".toMediaType()
        when (method.trim().uppercase()) {
            "GET" -> builder.get()
            "POST" -> builder.post((body ?: ByteArray(0)).toRequestBody(media))
            "PUT" -> builder.put((body ?: ByteArray(0)).toRequestBody(media))
            else -> builder.method(method, body?.toRequestBody(media))
        }
        client.newCall(builder.build()).execute().use { response ->
            val want = pinnedFingerprint?.let { SyncClient.normalizeFingerprint(it) }.orEmpty()
            if (want.isNotEmpty()) {
                val peers = response.handshake?.peerCertificates.orEmpty()
                val matched = peers.any { cert ->
                    cert is X509Certificate &&
                        SyncClient.normalizeFingerprint(sha256Hex(cert.encoded)) == want
                }
                if (!matched) throw CertificatePinException()
            }
            val bytes = response.body?.bytes() ?: ByteArray(0)
            val responseHeaders = response.headers.toMultimap().mapValues { it.value.joinToString(",") }
            return HttpResponse(response.code, responseHeaders, bytes)
        }
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
