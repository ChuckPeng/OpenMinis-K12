package com.openminis.k12.backup

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Tablet end-to-end encrypted backup packaging per §19.6.
 *
 * Binary container format:
 * - Magic (4 bytes): 'K', '1', '2', 'B' (0x4B, 0x31, 0x32, 0x42)
 * - Version (4 bytes int): 1
 * - Salt (16 bytes)
 * - Nonce/IV (12 bytes)
 * - Encrypted ciphertext + 16-byte GCM authentication tag
 *
 * Encryption: AES-256-GCM.
 * Key derivation: PBKDF2WithHmacSHA256, 100_000 iterations, 256 bits.
 */
object DeviceBackupManager {

    val MAGIC = byteArrayOf(0x4B, 0x31, 0x32, 0x42) // "K12B"
    const val VERSION = 1
    const val SALT_LEN = 16
    const val IV_LEN = 12
    const val TAG_BITS = 128
    const val PBKDF2_ITERATIONS = 100_000
    const val KEY_BITS = 256

    data class BackupPackage(
        val payload: ByteArray,
        val sha256Hex: String,
        val kdfParamsJson: String,
    )

    fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val raw = factory.generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }

    /**
     * Encrypts plaintext backup data into the K12B container format.
     */
    fun createBackup(data: ByteArray, password: String): BackupPackage {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }

        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val ciphertext = cipher.doFinal(data)

        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        dos.write(MAGIC)
        dos.writeInt(VERSION)
        dos.write(salt)
        dos.write(iv)
        dos.write(ciphertext)
        dos.flush()

        val fullPayload = bos.toByteArray()
        val sha256Hex = MessageDigest.getInstance("SHA-256").digest(fullPayload)
            .joinToString("") { "%02x".format(it) }

        val kdfJson = JSONObject().apply {
            put("kdf", "pbkdf2")
            put("iterations", PBKDF2_ITERATIONS)
            put("salt", Base64.getEncoder().encodeToString(salt))
        }.toString()

        return BackupPackage(fullPayload, sha256Hex, kdfJson)
    }

    /**
     * Decrypts a K12B backup package.
     */
    fun restoreBackup(fullPayload: ByteArray, password: String): ByteArray {
        require(fullPayload.size >= 4 + 4 + SALT_LEN + IV_LEN) { "Backup payload too short" }

        val dis = DataInputStream(ByteArrayInputStream(fullPayload))
        val magic = ByteArray(4)
        dis.readFully(magic)
        require(magic.contentEquals(MAGIC)) { "Invalid magic bytes in backup container" }

        val version = dis.readInt()
        require(version == VERSION) { "Unsupported backup version: $version" }

        val salt = ByteArray(SALT_LEN)
        dis.readFully(salt)

        val iv = ByteArray(IV_LEN)
        dis.readFully(iv)

        val ciphertext = dis.readBytes()
        val key = deriveKey(password, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }
}
