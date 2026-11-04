package com.openminis.k12.android

import androidx.compose.runtime.mutableStateOf
import com.openminis.k12.safety.AlertLevel
import com.openminis.k12.safety.ContentSafetyGuide
import com.openminis.k12.safety.EmergencyScreenState
import com.openminis.k12.safety.SafetyAlert
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Shows the emergency screen and queues an alert for the next sync.
 * The chat text is not written to logcat.
 */
object K12SafetyRuntime {
    val screen = mutableStateOf<EmergencyScreenState?>(null)

    @Volatile
    var hotlineText: String? = null

    @Volatile
    var hotlineNumber: String? = null

    fun consider(text: String): Boolean {
        if (!K12Runtime.installed || !ContentSafetyGuide.isCrisis(text)) return false
        screen.value = ContentSafetyGuide.createStep1Screen()
        val snippet = ContentSafetyGuide.extractContextSnippet(text, 0).take(240)
        val alert = SafetyAlert(
            alertId = com.openminis.k12.Ids.newId(),
            level = AlertLevel.EMERGENCY,
            category = "self_harm",
            sessionId = "local",
            questionHash = sha256(text),
            detectedAtMs = System.currentTimeMillis(),
            snippet = snippet,
        )
        val body = JSONObject()
            .put("level", alert.level.apiValue)
            .put("category", alert.category)
            .put("occurred_at", alert.detectedAtMs)
            .put("dedupe_key", alert.questionHash)
            .put("snippet", alert.snippet)
        K12SyncRuntime.enqueueAlert(body.toString())
        return true
    }

    fun decline() {
        screen.value = ContentSafetyGuide.createStep2Screen(hotlineText, hotlineNumber)
    }

    fun dismiss() {
        screen.value = null
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }
}
