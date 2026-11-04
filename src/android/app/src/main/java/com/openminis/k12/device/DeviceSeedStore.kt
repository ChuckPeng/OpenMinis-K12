package com.openminis.k12.device

import java.io.File
import javax.crypto.SecretKey

/**
 * Persists one Ed25519 seed as an AES-GCM blob. A failed unwrap deletes the
 * blob so the next load creates a new key. The file never holds the raw seed.
 */
class DeviceSeedStore(
    private val file: File,
    private val key: SecretKey,
) {
    fun loadOrCreate(): Ed25519DeviceKey.Material {
        if (file.isFile) {
            try {
                val seed = SeedCipher.open(key, file.readBytes())
                return Ed25519DeviceKey.fromSeed(seed).also { seed.fill(0) }
            } catch (_: Exception) {
                file.delete()
            }
        }
        return create()
    }

    fun wipe() {
        if (file.exists() && !file.delete()) {
            file.writeBytes(ByteArray(0))
            file.delete()
        }
    }

    private fun create(): Ed25519DeviceKey.Material {
        val created = Ed25519DeviceKey.generate()
        file.parentFile?.mkdirs()
        file.writeBytes(SeedCipher.seal(key, created.seed))
        return created
    }
}
