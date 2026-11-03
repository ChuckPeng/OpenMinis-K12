package com.openminis.k12.tutoring

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

/**
 * RFC 6238 TOTP (G3/G4). Local only — no server. Same code used once.
 */
object Totp {
    const val PERIOD_SEC = 30L
    const val DIGITS = 6
    private const val ALG = "HmacSHA1"

    fun generate(secret: ByteArray, timeMs: Long = System.currentTimeMillis()): String {
        val counter = timeMs / 1000L / PERIOD_SEC
        return generateForCounter(secret, counter)
    }

    fun generateForCounter(secret: ByteArray, counter: Long): String {
        val data = ByteArray(8) { i -> ((counter shr (56 - 8 * i)) and 0xff).toByte() }
        val mac = Mac.getInstance(ALG)
        mac.init(SecretKeySpec(secret, ALG))
        val hash = mac.doFinal(data)
        val offset = hash.last().toInt() and 0x0f
        val binary =
            ((hash[offset].toInt() and 0x7f) shl 24) or
                ((hash[offset + 1].toInt() and 0xff) shl 16) or
                ((hash[offset + 2].toInt() and 0xff) shl 8) or
                (hash[offset + 3].toInt() and 0xff)
        val otp = binary % 10.0.pow(DIGITS).toInt()
        return otp.toString().padStart(DIGITS, '0')
    }

    fun verify(
        secret: ByteArray,
        code: String,
        timeMs: Long = System.currentTimeMillis(),
        window: Int = 1,
        usedCodes: MutableSet<String>,
    ): Boolean {
        val trimmed = code.trim()
        if (trimmed.length != DIGITS || trimmed.any { !it.isDigit() }) return false
        if (trimmed in usedCodes) return false
        val counter = timeMs / 1000L / PERIOD_SEC
        for (delta in -window..window) {
            if (generateForCounter(secret, counter + delta) == trimmed) {
                usedCodes.add(trimmed)
                return true
            }
        }
        return false
    }

    /** Decode a Base32 secret (no padding required). */
    fun decodeBase32(encoded: String): ByteArray {
        val clean = encoded.trim().uppercase().replace("=", "").replace(" ", "")
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0
        var bitsLeft = 0
        val out = ArrayList<Byte>()
        for (ch in clean) {
            val value = alphabet.indexOf(ch)
            require(value >= 0) { "bad base32" }
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out.add((buffer shr (bitsLeft - 8)).toByte())
                bitsLeft -= 8
            }
        }
        return out.toByteArray()
    }
}
