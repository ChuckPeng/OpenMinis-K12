package com.openminis.k12.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.openminis.k12.device.DeviceSigner
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey

/**
 * Device signing key in the Android Keystore. The private key never leaves
 * the keystore and is not written to logs. Deleted on unpair or reset.
 */
object DeviceSigningKey {
    private const val ALIAS = "k12_device_sign"

    fun signer(context: Context): DeviceSigner.Signer? {
        return try {
            val key = privateKey() ?: return null
            val algorithm = if (key.algorithm.equals("Ed25519", ignoreCase = true)) {
                "Ed25519"
            } else {
                "SHA256withECDSA"
            }
            DeviceSigner.JcaSigner(key, algorithm)
        } catch (_: Exception) {
            null
        }
    }

    fun ensure(context: Context) {
        try {
            context.applicationContext
            if (privateKey() != null) return
            generate()
        } catch (_: Exception) {
            // A missing key makes the sync pass report NotPaired instead of
            // sending an unsigned request.
        }
    }

    fun delete(context: Context) {
        try {
            val store = keyStore()
            if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS)
        } catch (_: Exception) {
            // The alias is already gone.
        }
    }

    private fun privateKey(): PrivateKey? {
        val store = keyStore()
        if (!store.containsAlias(ALIAS)) return null
        return store.getKey(ALIAS, null) as? PrivateKey
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun generate() {
        try {
            val generator = KeyPairGenerator.getInstance("Ed25519", "AndroidKeyStore")
            generator.initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setDigests(KeyProperties.DIGEST_NONE)
                    .build(),
            )
            generator.generateKeyPair()
            return
        } catch (_: Exception) {
            // Ed25519 is not on every API level this app supports.
        }
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        generator.generateKeyPair()
    }
}
