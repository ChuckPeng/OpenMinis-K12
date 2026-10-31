package com.openminis.k12.device

import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.util.Base64

/**
 * Device request signing per docs/k12/modules/sync-client.md and device-api.yaml.
 * 
 * 5-line sign string separated by \n:
 * Line 1: Uppercase HTTP method (e.g. GET, POST, PUT)
 * Line 2: Request path with query string (e.g. /api/v1/device/heartbeat)
 * Line 3: Timestamp in Unix milliseconds
 * Line 4: 32-character lowercase hex nonce
 * Line 5: SHA-256 of request body in lowercase hex (empty body hashes empty byte array)
 *
 * Headers produced:
 * X-K12-Device: device_id
 * X-K12-Ts: timestamp
 * X-K12-Nonce: nonce
 * X-K12-Sig: unpadded base64url signature
 */
object DeviceSigner {

    const val HEADER_DEVICE = "X-K12-Device"
    const val HEADER_TS = "X-K12-Ts"
    const val HEADER_NONCE = "X-K12-Nonce"
    const val HEADER_SIG = "X-K12-Sig"

    fun buildSignString(
        method: String,
        path: String,
        tsMs: Long,
        nonce: String,
        body: ByteArray? = null,
    ): String {
        val bodyHash = if (body == null || body.isEmpty()) {
            sha256Hex(ByteArray(0))
        } else {
            sha256Hex(body)
        }
        return listOf(
            method.trim().uppercase(),
            path.trim(),
            tsMs.toString(),
            nonce.trim().lowercase(),
            bodyHash.lowercase(),
        ).joinToString("\n")
    }

    fun sha256Hex(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun newNonce(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun interface Signer {
        fun sign(payload: ByteArray): ByteArray
    }

    class JcaSigner(
        private val privateKey: PrivateKey,
        private val algorithm: String = "Ed25519",
    ) : Signer {
        override fun sign(payload: ByteArray): ByteArray {
            val sig = Signature.getInstance(algorithm)
            sig.initSign(privateKey)
            sig.update(payload)
            return sig.sign()
        }
    }

    class MockSigner(
        val keyName: String = "mock-ed25519",
        private val signFn: (ByteArray) -> ByteArray = { data ->
            val md = MessageDigest.getInstance("SHA-256")
            md.update(keyName.toByteArray(Charsets.UTF_8))
            md.digest(data)
        },
    ) : Signer {
        override fun sign(payload: ByteArray): ByteArray = signFn(payload)
    }

    fun signHeaders(
        deviceId: String,
        method: String,
        path: String,
        body: ByteArray?,
        signer: Signer,
        nowMs: Long = System.currentTimeMillis(),
        nonce: String = newNonce(),
    ): Map<String, String> {
        val signStr = buildSignString(method, path, nowMs, nonce, body)
        val sigBytes = signer.sign(signStr.toByteArray(Charsets.UTF_8))
        val sigB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(sigBytes)
        return mapOf(
            HEADER_DEVICE to deviceId,
            HEADER_TS to nowMs.toString(),
            HEADER_NONCE to nonce,
            HEADER_SIG to sigB64,
        )
    }
}
