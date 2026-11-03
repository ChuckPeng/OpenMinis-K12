package com.openminis.k12.model

import android.content.Context
import org.json.JSONArray

/**
 * Delivered tablet-model config, including the API key, in Android Keystore
 * backed encrypted preferences. Unpair and a cleared server config wipe the key.
 */
class KeystoreModelSecretStore(context: Context) : ModelSecretStore {
    private val prefs = com.openminis.app.util.EncryptedPrefsFactory.safeCreate(
        context.applicationContext,
        PREFS,
    )

    override fun load(): StoredTabletModel? {
        if (!prefs.getBoolean(KEY_PRESENT, false)) return null
        return StoredTabletModel(
            version = prefs.getLong(KEY_VERSION, 0L),
            cleared = prefs.getBoolean(KEY_CLEARED, true),
            providerType = prefs.getString(KEY_PROVIDER, "") ?: "",
            baseUrl = prefs.getString(KEY_BASE, "") ?: "",
            modelName = prefs.getString(KEY_MODEL, "") ?: "",
            visionModelName = prefs.getString(KEY_VISION, "") ?: "",
            apiKey = prefs.getString(KEY_SECRET, "") ?: "",
            priorCaptured = prefs.getBoolean(KEY_PRIOR, false),
            priorDefaultGroupId = prefs.getString(KEY_PRIOR_GROUP, null),
            priorVisionGroupId = prefs.getString(KEY_PRIOR_VISION, null),
            priorEnabledInstanceIds = decodeIds(prefs.getString(KEY_PRIOR_ENABLED, null)),
        )
    }

    override fun save(value: StoredTabletModel) {
        prefs.edit()
            .putBoolean(KEY_PRESENT, true)
            .putLong(KEY_VERSION, value.version)
            .putBoolean(KEY_CLEARED, value.cleared)
            .putString(KEY_PROVIDER, value.providerType)
            .putString(KEY_BASE, value.baseUrl)
            .putString(KEY_MODEL, value.modelName)
            .putString(KEY_VISION, value.visionModelName)
            .putString(KEY_SECRET, value.apiKey)
            .putBoolean(KEY_PRIOR, value.priorCaptured)
            .putString(KEY_PRIOR_GROUP, value.priorDefaultGroupId)
            .putString(KEY_PRIOR_VISION, value.priorVisionGroupId)
            .putString(KEY_PRIOR_ENABLED, encodeIds(value.priorEnabledInstanceIds))
            .commit()
    }

    override fun wipe() {
        prefs.edit().clear().commit()
    }

    private fun encodeIds(ids: List<String>): String {
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        return arr.toString()
    }

    private fun decodeIds(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        const val PREFS = "k12_tablet_model"
        private const val KEY_PRESENT = "present"
        private const val KEY_VERSION = "version"
        private const val KEY_CLEARED = "cleared"
        private const val KEY_PROVIDER = "provider_type"
        private const val KEY_BASE = "base_url"
        private const val KEY_MODEL = "model_name"
        private const val KEY_VISION = "vision_model"
        private const val KEY_SECRET = "api_key"
        private const val KEY_PRIOR = "prior_captured"
        private const val KEY_PRIOR_GROUP = "prior_group"
        private const val KEY_PRIOR_VISION = "prior_vision"
        private const val KEY_PRIOR_ENABLED = "prior_enabled"
    }
}
