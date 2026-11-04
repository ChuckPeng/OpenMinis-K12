package com.openminis.k12.android

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.openminis.k12.device.DeviceSeedStore
import com.openminis.k12.device.DeviceSigner
import com.openminis.k12.device.Ed25519DeviceKey
import com.openminis.k12.device.SeedCipher
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Ed25519 device key. API 33+ may keep the key in AndroidKeyStore when the
 * hardware actually supports `ed25519`. Otherwise the 32-byte seed is generated
 * in software and stored only as AES-256-GCM ciphertext. The AES key is a
 * non-exportable AndroidKeyStore key. Unpair deletes both Keystore entries and
 * the ciphertext. Key bytes are not logged.
 */
object DeviceSigningKey {
    private const val SIGN_ALIAS = "k12_device_sign"
    private const val AES_ALIAS = "k12_device_seed_aes"
    private const val SEED_FILE = "k12_device_ed25519_seed"
    private const val TAG = "K12DeviceKey"

    class Opened(
        val signer: DeviceSigner.Signer,
        val publicKeyBase64: String,
    ) {
        fun wipe() {
            (signer as? Ed25519DeviceKey.SeedSigner)?.wipe()
        }

        override fun toString(): String = "DeviceSigningKey"
    }

    @Volatile
    private var cached: Opened? = null

    @Synchronized
    fun open(context: Context): Opened? {
        cached?.let { return it }
        return try {
            val opened = loadOrCreate(context.applicationContext)
            cached = opened
            opened
        } catch (thrown: Throwable) {
            Log.w(TAG, "device key generation failed: ${failureDetail(thrown)}")
            null
        }
    }

    fun signer(context: Context): DeviceSigner.Signer? = open(context)?.signer

    fun publicKeyBase64(context: Context): String? = open(context)?.publicKeyBase64

    fun ensure(context: Context) {
        open(context)
    }

    @Synchronized
    fun delete(context: Context) {
        val opened = cached
        cached = null
        opened?.wipe()
        deleteAlias(SIGN_ALIAS)
        deleteAlias(AES_ALIAS)
        seedFile(context.applicationContext).delete()
    }

    private fun loadOrCreate(context: Context): Opened {
        val file = seedFile(context)
        if (file.isFile) {
            try {
                val seed = SeedCipher.open(aesKey(), file.readBytes())
                val opened = softwareOpened(seed)
                seed.fill(0)
                dropNonEd25519Alias()
                return opened
            } catch (thrown: Throwable) {
                Log.w(TAG, "device key unwrap failed: ${failureDetail(thrown)}")
                file.delete()
            }
        }
        dropNonEd25519Alias()
        hardwareOpened()?.let { return it }
        val store = DeviceSeedStore(file, aesKey())
        val created = store.loadOrCreate()
        return softwareOpened(created.seed).also { created.seed.fill(0) }
    }

    private fun softwareOpened(seed: ByteArray): Opened {
        val signer = Ed25519DeviceKey.SeedSigner(seed)
        val publicKey = Ed25519DeviceKey.publicFromSeed(seed)
        return Opened(signer, Base64.getEncoder().encodeToString(publicKey))
    }

    private fun hardwareOpened(): Opened? {
        val store = keyStore()
        if (!store.containsAlias(SIGN_ALIAS)) {
            if (Build.VERSION.SDK_INT < 33) return null
            if (!tryGenerateHardware()) return null
        }
        val opened = try {
            openedFromKeystore()
        } catch (thrown: Throwable) {
            Log.w(TAG, "hardware ed25519 unusable: ${failureDetail(thrown)}")
            null
        }
        if (opened != null && selfTest(opened)) return opened
        deleteAlias(SIGN_ALIAS)
        return null
    }

    private fun tryGenerateHardware(): Boolean {
        return try {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            generator.initialize(
                KeyGenParameterSpec.Builder(
                    SIGN_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec("ed25519"))
                    .build(),
            )
            generator.generateKeyPair()
            true
        } catch (thrown: Throwable) {
            Log.w(TAG, "hardware ed25519 unavailable: ${failureDetail(thrown)}")
            deleteAlias(SIGN_ALIAS)
            false
        }
    }

    private fun openedFromKeystore(): Opened? {
        val store = keyStore()
        val certificate = store.getCertificate(SIGN_ALIAS) ?: return null
        val raw = Ed25519DeviceKey.rawPublicKey(certificate.publicKey) ?: return null
        val privateKey = store.getKey(SIGN_ALIAS, null) as? PrivateKey ?: return null
        return Opened(
            DeviceSigner.JcaSigner(privateKey, "Ed25519"),
            Base64.getEncoder().encodeToString(raw),
        )
    }

    private fun selfTest(opened: Opened): Boolean {
        return try {
            val message = "k12-ed25519-self-test".toByteArray(Charsets.UTF_8)
            val signature = opened.signer.sign(message)
            val publicKey = Base64.getDecoder().decode(opened.publicKeyBase64)
            Ed25519DeviceKey.verify(publicKey, message, signature)
        } catch (thrown: Throwable) {
            Log.w(TAG, "device key self-test failed: ${failureDetail(thrown)}")
            false
        }
    }

    /** rc.3 stored ECDSA P-256 under the signing alias. That key cannot pair. */
    private fun dropNonEd25519Alias() {
        val store = keyStore()
        if (!store.containsAlias(SIGN_ALIAS)) return
        val certificate = store.getCertificate(SIGN_ALIAS)
        val publicKey = certificate?.publicKey
        if (publicKey == null || !Ed25519DeviceKey.isEd25519PublicKey(publicKey)) {
            deleteAlias(SIGN_ALIAS)
        }
    }

    private fun aesKey(): SecretKey {
        val store = keyStore()
        val existing = store.getKey(AES_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                AES_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generator.generateKey()
        return store.getKey(AES_ALIAS, null) as SecretKey
    }

    private fun seedFile(context: Context): File = File(context.noBackupFilesDir, SEED_FILE)

    private fun deleteAlias(alias: String) {
        try {
            val store = keyStore()
            if (store.containsAlias(alias)) store.deleteEntry(alias)
        } catch (thrown: Throwable) {
            Log.w(TAG, "device key delete failed: ${failureDetail(thrown)}")
        }
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /** Class and message only. Long tokens are redacted so a key cannot land in logcat. */
    private fun failureDetail(thrown: Throwable): String {
        val message = thrown.message.orEmpty().replace(KEY_LIKE, "[redacted]")
        val cause = thrown.cause
        val tail = if (cause != null && cause !== thrown) {
            val causeMessage = cause.message.orEmpty().replace(KEY_LIKE, "[redacted]")
            " caused by ${cause.javaClass.name}: $causeMessage"
        } else {
            ""
        }
        return "${thrown.javaClass.name}: $message$tail"
    }

    private val KEY_LIKE = Regex("[A-Za-z0-9+/=_\\-]{24,}")
}
