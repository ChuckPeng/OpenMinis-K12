package com.openminis.k12.device

import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAParameterSpec
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.util.Base64

/**
 * Software Ed25519 used when AndroidKeyStore cannot hold an Ed25519 key.
 * The 32-byte seed matches Go's `crypto/ed25519` seed. Signatures are 64 raw
 * bytes. Nothing here is logged.
 */
object Ed25519DeviceKey {
    const val SEED_LENGTH = 32
    const val PUBLIC_LENGTH = 32
    const val SIGNATURE_LENGTH = 64

    private val ED25519_OID = byteArrayOf(0x06, 0x03, 0x2b, 0x65, 0x70)

    class Material(val seed: ByteArray, val publicKey: ByteArray) {
        init {
            require(seed.size == SEED_LENGTH)
            require(publicKey.size == PUBLIC_LENGTH)
        }

        fun publicKeyBase64(): String = Base64.getEncoder().encodeToString(publicKey)

        fun signer(): DeviceSigner.Signer = SeedSigner(seed)

        override fun toString(): String = "Ed25519Material"
    }

    class SeedSigner(seed: ByteArray) : DeviceSigner.Signer {
        private val seed = seed.copyOf()

        override fun sign(payload: ByteArray): ByteArray = sign(seed, payload)

        fun wipe() {
            seed.fill(0)
        }

        override fun toString(): String = "Ed25519SeedSigner"
    }

    fun generate(): Material {
        val seed = ByteArray(SEED_LENGTH)
        SecureRandom().nextBytes(seed)
        return fromSeed(seed)
    }

    fun fromSeed(seed: ByteArray): Material {
        require(seed.size == SEED_LENGTH)
        return Material(seed.copyOf(), publicFromSeed(seed))
    }

    fun publicFromSeed(seed: ByteArray): ByteArray {
        val privateKey = EdDSAPrivateKey(EdDSAPrivateKeySpec(seed.copyOf(), curve()))
        return privateKey.abyte.copyOf()
    }

    fun sign(seed: ByteArray, payload: ByteArray): ByteArray {
        val privateKey = EdDSAPrivateKey(EdDSAPrivateKeySpec(seed.copyOf(), curve()))
        val engine = EdDSAEngine(MessageDigest.getInstance("SHA-512"))
        engine.initSign(privateKey)
        engine.update(payload)
        return engine.sign()
    }

    fun verify(publicKey: ByteArray, payload: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != PUBLIC_LENGTH || signature.size != SIGNATURE_LENGTH) return false
        return try {
            val key = EdDSAPublicKey(EdDSAPublicKeySpec(publicKey, curve()))
            val engine = EdDSAEngine(MessageDigest.getInstance("SHA-512"))
            engine.initVerify(key)
            engine.update(payload)
            engine.verify(signature)
        } catch (_: Exception) {
            false
        }
    }

    /** Raw 32-byte key from an X.509 Ed25519 public key. P-256 returns null. */
    fun rawPublicKey(publicKey: PublicKey): ByteArray? {
        val encoded = publicKey.encoded ?: return null
        if (!contains(encoded, ED25519_OID)) return null
        if (encoded.size < PUBLIC_LENGTH) return null
        return encoded.copyOfRange(encoded.size - PUBLIC_LENGTH, encoded.size)
    }

    fun isEd25519PublicKey(publicKey: PublicKey): Boolean = rawPublicKey(publicKey) != null

    private fun curve(): EdDSAParameterSpec =
        EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        for (start in 0..haystack.size - needle.size) {
            var matched = true
            for (index in needle.indices) {
                if (haystack[start + index] != needle[index]) {
                    matched = false
                    break
                }
            }
            if (matched) return true
        }
        return false
    }
}
