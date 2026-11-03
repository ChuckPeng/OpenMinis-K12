package com.openminis.k12

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Parent PIN and backup password. Only salted hashes are stored.
 *
 * PIN length is not specified in the docs. A PIN here is 4–8 digits, distinct
 * from the server admin password (at least 12 characters with letters and digits).
 * Backup password is at least 8 characters. Matching the PIN is allowed.
 */
object Secrets {
    const val PIN_ITERATIONS = 100_000
    const val FAILURES_BEFORE_LOCK = 5
    const val LOCK_MS = 15L * 60 * 1000
    private const val KEY_BITS = 256

    fun acceptablePin(pin: String): Boolean =
        pin.length in 4..8 && pin.all { it.isDigit() }

    fun acceptableBackup(password: String): Boolean = password.length >= 8

    fun newSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    fun hash(secret: String, salt: ByteArray): String {
        val spec = PBEKeySpec(secret.toCharArray(), salt, PIN_ITERATIONS, KEY_BITS)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
        return key.encoded.toHex()
    }

    fun matches(secret: String, saltHex: String?, hashHex: String?): Boolean {
        if (saltHex.isNullOrBlank() || hashHex.isNullOrBlank()) return false
        val salt = saltHex.fromHex() ?: return false
        val actual = hash(secret, salt)
        return MessageDigest.isEqual(actual.toByteArray(), hashHex.toByteArray())
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.fromHex(): ByteArray? {
        if (length % 2 != 0) return null
        return try {
            ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (_: NumberFormatException) {
            null
        }
    }
}

enum class SensitiveArea {
    PROVIDER,
    MODEL_KEY,
    REVEAL_POLICY,
    NOTIFICATION_CHANNEL,
}

/** E1: without a verified parent session these cannot be changed. */
fun canMutate(area: SensitiveArea, parentUnlocked: Boolean): Boolean = parentUnlocked
