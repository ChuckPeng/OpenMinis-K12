package com.openminis.k12.device

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM wrapper for the 32-byte Ed25519 seed.
 * Blob layout: version byte 1, 12-byte IV, ciphertext including the 16-byte tag.
 * Exceptions carry no key material.
 */
object SeedCipher {
    private const val VERSION: Byte = 1
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    class SeedCipherException : Exception()

    fun seal(key: SecretKey, seed: ByteArray): ByteArray {
        if (seed.size != Ed25519DeviceKey.SEED_LENGTH) throw SeedCipherException()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // AndroidKeyStore generates the GCM IV. Passing one in throws
        // InvalidAlgorithmParameterException: Caller-provided IV not permitted.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val body = cipher.doFinal(seed)
        val iv = cipher.iv
        if (iv == null || iv.size != IV_LENGTH) throw SeedCipherException()
        return byteArrayOf(VERSION) + iv + body
    }

    fun open(key: SecretKey, blob: ByteArray): ByteArray {
        if (blob.size < 1 + IV_LENGTH + Ed25519DeviceKey.SEED_LENGTH + 16) throw SeedCipherException()
        if (blob[0] != VERSION) throw SeedCipherException()
        return try {
            val iv = blob.copyOfRange(1, 1 + IV_LENGTH)
            val body = blob.copyOfRange(1 + IV_LENGTH, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            val seed = cipher.doFinal(body)
            if (seed.size != Ed25519DeviceKey.SEED_LENGTH) throw SeedCipherException()
            seed
        } catch (thrown: SeedCipherException) {
            throw thrown
        } catch (_: Exception) {
            throw SeedCipherException()
        }
    }
}
