package com.openminis.k12.tutoring

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Offline backup code verification for M5b (J13).
 *
 * Derives a 6-digit code via RFC 4226 dynamic truncation over HMAC-SHA256(codeSecret, "$requestId:$questionHash").
 * The tablet verifies offline without round-tripping to the server.
 *
 * Security rules (J13):
 * - Single-use per request/question
 * - 10-minute validity
 * - 5 consecutive failures lock out for 10 minutes
 */
class OfflineBackupCode(
    private val codeSecret: ByteArray,
) {
    companion object {
        const val MAX_FAILURES = 5
        const val LOCK_DURATION_MS = 10L * 60 * 1000 // 10 minutes
        const val CODE_VALIDITY_MS = 10L * 60 * 1000 // 10 minutes

        fun deriveCode(codeSecret: ByteArray, requestId: String, questionHash: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(codeSecret, "HmacSHA256"))
            val input = "$requestId:$questionHash".toByteArray(Charsets.UTF_8)
            val sum = mac.doFinal(input)
            val offset = sum.last().toInt() and 0x0f
            val binary = ((sum[offset].toInt() and 0x7f) shl 24) or
                ((sum[offset + 1].toInt() and 0xff) shl 16) or
                ((sum[offset + 2].toInt() and 0xff) shl 8) or
                (sum[offset + 3].toInt() and 0xff)
            val code = binary % 1_000_000
            return "%06d".format(code)
        }
    }

    var consecutiveFailures: Int = 0
        private set

    var lockedUntilMs: Long = 0
        private set

    private val consumedRequests = mutableSetOf<String>()

    fun isLocked(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs < lockedUntilMs

    sealed class VerifyResult {
        data object Accepted : VerifyResult()
        data class Locked(val remainingMs: Long) : VerifyResult()
        data object Expired : VerifyResult()
        data object AlreadyUsed : VerifyResult()
        data class BadCode(val attemptsRemaining: Int) : VerifyResult()
    }

    fun verify(
        code: String,
        requestId: String,
        questionHash: String,
        requestCreatedAtMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): VerifyResult {
        if (isLocked(nowMs)) {
            return VerifyResult.Locked(lockedUntilMs - nowMs)
        }

        if (requestId in consumedRequests) {
            return VerifyResult.AlreadyUsed
        }

        if (nowMs - requestCreatedAtMs > CODE_VALIDITY_MS) {
            return VerifyResult.Expired
        }

        val expected = deriveCode(codeSecret, requestId, questionHash)
        val matches = MessageDigest.isEqual(
            code.trim().toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8),
        )

        if (!matches) {
            consecutiveFailures++
            if (consecutiveFailures >= MAX_FAILURES) {
                lockedUntilMs = nowMs + LOCK_DURATION_MS
                return VerifyResult.Locked(LOCK_DURATION_MS)
            }
            return VerifyResult.BadCode(MAX_FAILURES - consecutiveFailures)
        }

        // Accepted
        consecutiveFailures = 0
        consumedRequests.add(requestId)
        return VerifyResult.Accepted
    }
}
