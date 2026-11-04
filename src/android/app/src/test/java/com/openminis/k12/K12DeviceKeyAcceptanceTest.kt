package com.openminis.k12

import com.openminis.k12.device.DeviceSeedStore
import com.openminis.k12.device.DeviceSigner
import com.openminis.k12.device.Ed25519DeviceKey
import com.openminis.k12.device.SeedCipher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.io.deleteRecursively
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Software Ed25519 must match the server's check: standard Base64 of the raw
 * 32-byte public key, and a 64-byte signature over the 5-line device string
 * (Go `ed25519.Verify`, unpadded base64url in `X-K12-Sig`).
 */
class K12DeviceKeyAcceptanceTest {

    @Test
    fun signAndVerifyMatchJavaEd25519AndTheServerHeaderFormat() {
        val material = Ed25519DeviceKey.generate()
        assertEquals(32, material.seed.size)
        assertEquals(32, material.publicKey.size)
        val decoded = Base64.getDecoder().decode(material.publicKeyBase64())
        assertArrayEquals(material.publicKey, decoded)
        assertTrue(Ed25519DeviceKey.isEd25519PublicKey(jdkPublic(material.publicKey)))

        val body = """{"pair_code":"123456","device_pubkey":"${material.publicKeyBase64()}"}"""
            .toByteArray(Charsets.UTF_8)
        val path = "/api/v1/device/pair/status?wait=0"
        val nonce = "ab".repeat(16)
        val now = 1_700_000_000_000L
        val signString = DeviceSigner.buildSignString("POST", path, now, nonce, body)
        val headers = DeviceSigner.signHeaders(
            deviceId = "dev-1",
            method = "POST",
            path = path,
            body = body,
            signer = material.signer(),
            nowMs = now,
            nonce = nonce,
        )
        val signature = Base64.getUrlDecoder().decode(headers.getValue(DeviceSigner.HEADER_SIG))
        assertEquals(64, signature.size)
        assertFalse(headers.getValue(DeviceSigner.HEADER_SIG).contains("="))
        assertTrue(jdkVerify(material.publicKey, signString.toByteArray(Charsets.UTF_8), signature))
        assertTrue(Ed25519DeviceKey.verify(material.publicKey, signString.toByteArray(Charsets.UTF_8), signature))
        assertFalse(jdkVerify(material.publicKey, "tampered".toByteArray(), signature))
        assertEquals(material.toString(), "Ed25519Material")
    }

    @Test
    fun p256PublicKeyIsNotADeviceKey() {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val p256 = generator.generateKeyPair().public
        assertFalse(Ed25519DeviceKey.isEd25519PublicKey(p256))
        assertEquals(null, Ed25519DeviceKey.rawPublicKey(p256))
    }

    @Test
    fun encryptedSeedRoundTripsAndATamperedBlobIsRejected() {
        val key = aesKey()
        val directory = Files.createTempDirectory("k12-seed").toFile()
        val file = File(directory, "k12_device_ed25519_seed")
        val store = DeviceSeedStore(file, key)
        val first = store.loadOrCreate()
        val again = store.loadOrCreate()
        assertArrayEquals(first.publicKey, again.publicKey)
        val blob = file.readBytes()
        assertTrue(blob[0] == 1.toByte())
        assertFalse(blob.containsSequence(first.seed))
        assertFalse(blob.containsSequence(first.publicKey))
        val opened = SeedCipher.open(key, blob)
        assertArrayEquals(first.seed, opened)
        opened.fill(0)

        val secondSeal = SeedCipher.seal(key, first.seed)
        assertFalse(secondSeal.contentEquals(blob))

        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte()
        file.writeBytes(blob)
        try {
            SeedCipher.open(key, blob)
            throw AssertionError("tampered blob opened")
        } catch (thrown: SeedCipher.SeedCipherException) {
            assertEquals(null, thrown.message)
        }
        val replaced = store.loadOrCreate()
        assertFalse(first.publicKey.contentEquals(replaced.publicKey))
        store.wipe()
        assertFalse(file.exists())
        val third = DeviceSeedStore(file, key).loadOrCreate()
        assertNotEquals(Base64.getEncoder().encodeToString(replaced.publicKey), third.publicKeyBase64())
        directory.deleteRecursively()
    }

    @Test
    fun signingKeySourceDropsP256AndDoesNotLogKeyMaterial() {
        val source = repoFile("src/android/app/src/main/java/com/openminis/k12/android/DeviceSigningKey.kt").readText()
        assertTrue(source.contains("k12_device_seed_aes"))
        assertTrue(source.contains("k12_device_sign"))
        assertTrue(source.contains("ECGenParameterSpec(\"ed25519\")"))
        assertTrue(source.contains("Log.w"))
        assertTrue(source.contains("catch (thrown: Throwable)"))
        assertTrue(source.contains("failureDetail(thrown)"))
        assertTrue(source.contains("thrown.javaClass.name"))
        assertTrue(source.contains("thrown.message"))
        assertTrue(source.contains("[redacted]"))
        assertFalse(source.contains("secp256r1"))
        assertFalse(source.contains("println"))
        assertFalse(source.contains("\${seed"))
        assertFalse(source.contains("\${publicKey"))
        assertTrue(source.contains("deleteAlias(SIGN_ALIAS)"))
        assertTrue(source.contains("deleteAlias(AES_ALIAS)"))
        val workflow = repoFile(".github/workflows/android.yml").readText()
        assertTrue(workflow.contains("K12DeviceKeyAcceptanceTest"))
    }

    private fun aesKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun jdkPublic(raw: ByteArray): java.security.PublicKey {
        val prefix = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
        )
        return KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(prefix + raw))
    }

    private fun jdkVerify(rawPublic: ByteArray, payload: ByteArray, signature: ByteArray): Boolean {
        val signatureEngine = Signature.getInstance("Ed25519")
        signatureEngine.initVerify(jdkPublic(rawPublic))
        signatureEngine.update(payload)
        return signatureEngine.verify(signature)
    }

    private fun ByteArray.containsSequence(needle: ByteArray): Boolean {
        if (needle.isEmpty() || size < needle.size) return false
        for (start in 0..size - needle.size) {
            if (copyOfRange(start, start + needle.size).contentEquals(needle)) return true
        }
        return false
    }

    private fun repoFile(name: String): File = listOf(
        File(name),
        File("../../$name"),
        File("../../../$name"),
    ).first { it.exists() }
}
