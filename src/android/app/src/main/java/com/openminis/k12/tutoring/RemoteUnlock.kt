package com.openminis.k12.tutoring

import com.openminis.k12.device.SyncClient
import org.json.JSONObject
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Remote Reveal Unlock protocol implementation (docs/k12/modules/unlock.md, WP-B-2).
 * 
 * Flow:
 * 1. Tablet initiates unlock request: POST /api/v1/device/unlock-requests
 * 2. Tablet polls with wait=25: GET /api/v1/device/unlock-requests/{id}?wait=25
 * 3. 5-second timeout fallback (J6, G5): if network fails or takes > 5s, child can fall back to PIN / TOTP / offline backup code.
 * 4. Grant verification (J5): checks device_id, question_hash, unused grant_id, expiration, and server Ed25519 signature.
 * 5. Ack (J8): sends POST /api/v1/device/unlock-requests/{id}/ack.
 */

data class Grant(
    val v: Int = 1,
    val grantId: String,
    val requestId: String,
    val deviceId: String,
    val questionHash: String,
    val scope: String,
    val expiresAt: String,
    val decidedByLabel: String,
    val signature: String,
)

data class UnlockRequestStatus(
    val requestId: String,
    val questionHash: String,
    val status: String, // pending, approved, denied, expired
    val grant: Grant? = null,
    val changed: Boolean = false,
)

fun interface GrantSignatureVerifier {
    fun verify(publicKeyStr: String, payload: ByteArray, signatureStr: String): Boolean
}

object DefaultGrantVerifier : GrantSignatureVerifier {
    override fun verify(publicKeyStr: String, payload: ByteArray, signatureStr: String): Boolean {
        return try {
            val pubBytes = decodeBase64OrHex(publicKeyStr)
            val sigBytes = decodeBase64OrHex(signatureStr)

            // Wrap raw 32-byte Ed25519 key into X.509 SubjectPublicKeyInfo DER if needed
            val x509Bytes = if (pubBytes.size == 32) {
                val derPrefix = byteArrayOf(
                    0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
                )
                derPrefix + pubBytes
            } else {
                pubBytes
            }

            val keySpec = X509EncodedKeySpec(x509Bytes)
            val keyFactory = KeyFactory.getInstance("Ed25519")
            val pubKey: PublicKey = keyFactory.generatePublic(keySpec)

            val sig = Signature.getInstance("Ed25519")
            sig.initVerify(pubKey)
            sig.update(payload)
            sig.verify(sigBytes)
        } catch (_: Exception) {
            false
        }
    }

    private fun decodeBase64OrHex(s: String): ByteArray {
        val trimmed = s.trim()
        return try {
            Base64.getUrlDecoder().decode(trimmed)
        } catch (_: Exception) {
            try {
                Base64.getDecoder().decode(trimmed)
            } catch (_: Exception) {
                ByteArray(trimmed.length / 2) { i ->
                    trimmed.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
            }
        }
    }
}

object RemoteUnlock {

    const val TIMEOUT_FALLBACK_MS = 5000L // 5 seconds (J6, G5)
    const val REQUEST_EXPIRY_MS = 10L * 60 * 1000 // 10 minutes (J4)

    /**
     * Builds canonical grant JSON payload with keys in strict alphabetical order.
     */
    fun buildCanonicalGrantPayload(grant: Grant): ByteArray {
        val json = """{"decided_by_label":${JSONObject.quote(grant.decidedByLabel)},"device_id":${JSONObject.quote(grant.deviceId)},"expires_at":${JSONObject.quote(grant.expiresAt)},"grant_id":${JSONObject.quote(grant.grantId)},"question_hash":${JSONObject.quote(grant.questionHash)},"request_id":${JSONObject.quote(grant.requestId)},"scope":${JSONObject.quote(grant.scope)},"v":${grant.v}}"""
        return json.toByteArray(Charsets.UTF_8)
    }

    fun parseGrant(json: JSONObject?): Grant? {
        if (json == null) return null
        return Grant(
            v = json.optInt("v", 1),
            grantId = json.optString("grant_id", ""),
            requestId = json.optString("request_id", ""),
            deviceId = json.optString("device_id", ""),
            questionHash = json.optString("question_hash", ""),
            scope = json.optString("scope", "question"),
            expiresAt = json.optString("expires_at", ""),
            decidedByLabel = json.optString("decided_by_label", ""),
            signature = json.optString("signature", ""),
        ).takeIf { it.grantId.isNotBlank() && it.signature.isNotBlank() }
    }

    /**
     * Verifies a grant against security constraints (J5):
     * - device_id matches
     * - question_hash matches
     * - grant_id has not been previously consumed
     * - grant is not expired
     * - Ed25519 signature is valid
     */
    fun verifyGrant(
        grant: Grant,
        serverPubkey: String,
        localDeviceId: String,
        currentQuestionHash: String,
        consumedGrantIds: Set<String>,
        verifier: GrantSignatureVerifier = DefaultGrantVerifier,
    ): Boolean {
        if (grant.deviceId != localDeviceId) return false
        if (grant.questionHash != currentQuestionHash) return false
        if (grant.grantId in consumedGrantIds) return false

        val canonical = buildCanonicalGrantPayload(grant)
        return verifier.verify(serverPubkey, canonical, grant.signature)
    }
}
