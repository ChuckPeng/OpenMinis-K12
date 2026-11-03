package com.openminis.k12.safety

import org.json.JSONObject

/**
 * Content safety and emergency alert protocol (docs/k12/modules/content-safety.md).
 * 
 * Rules:
 * - F5: First emergency screen only displays "请马上告诉身边的大人。" and "不想说", without phone numbers.
 * - F6: Second screen displays parent-configured hotline if set; if empty, retains only the first sentence and no numbers.
 * - F7: Absolutely no hardcoded hotline numbers in source code or APK.
 * - H1: Three alert levels: TIP, NOTICE, EMERGENCY.
 * - H2: Offline queuing for later delivery.
 * - H3: 30-minute deduplication window for same-level alerts in a session.
 * - H4: Context snippets contain at most 2 sentences before and 2 sentences after the trigger.
 * - H6: Emergency alerts are dispatched immediately upon detection, without waiting for child response.
 */

enum class AlertLevel {
    TIP,
    NOTICE,
    EMERGENCY;

    val apiValue: String get() = name.lowercase()
}

enum class EmergencyStep {
    STEP1_TELL_ADULT,
    STEP2_DECLINED_OR_HOTLINE,
}

data class EmergencyScreenState(
    val step: EmergencyStep,
    val messageText: String,
    val showDeclineButton: Boolean,
    val hotlineText: String? = null,
    val hotlineNumber: String? = null,
)

data class SafetyAlert(
    val alertId: String,
    val level: AlertLevel,
    val category: String,
    val sessionId: String,
    val questionHash: String,
    val detectedAtMs: Long,
    val snippet: String,
)

object ContentSafetyGuide {

    const val TEXT_TELL_ADULT = "请马上告诉身边的大人。"
    const val TEXT_DECLINE_BUTTON = "不想说"
    const val DEDUPE_WINDOW_MS = 30L * 60 * 1000 // 30 minutes (H3)

    private val CRISIS_KEYWORDS = listOf(
        "自杀", "自残", "割腕", "不想活了", "跳楼", "轻生", "上吊", "安眠药", "活着没意思",
    )

    fun isCrisis(text: String): Boolean {
        val lower = text.lowercase()
        return CRISIS_KEYWORDS.any { lower.contains(it) }
    }

    /**
     * Extracts context snippet: at most 2 sentences before and 2 sentences after trigger (H4).
     */
    fun extractContextSnippet(fullText: String, triggerIndex: Int): String {
        val sentences = fullText.split(Regex("(?<=[。！？\n])")).filter { it.isNotBlank() }
        if (sentences.isEmpty()) return fullText.take(200)

        var hitIdx = -1
        var charCount = 0
        for ((idx, s) in sentences.withIndex()) {
            charCount += s.length
            if (triggerIndex < charCount) {
                hitIdx = idx
                break
            }
        }
        if (hitIdx == -1) hitIdx = sentences.size - 1

        val start = (hitIdx - 2).coerceAtLeast(0)
        val end = (hitIdx + 3).coerceAtMost(sentences.size)
        return sentences.subList(start, end).joinToString("")
    }

    /**
     * Creates step 1 emergency screen (F5).
     * Must ONLY offer "请马上告诉身边的大人。" and "不想说", NO phone number.
     */
    fun createStep1Screen(): EmergencyScreenState {
        return EmergencyScreenState(
            step = EmergencyStep.STEP1_TELL_ADULT,
            messageText = TEXT_TELL_ADULT,
            showDeclineButton = true,
            hotlineText = null,
            hotlineNumber = null,
        )
    }

    /**
     * Creates step 2 emergency screen when child declines or expresses reluctance (F6).
     * Only displays hotline if parent configured both hotlineText and hotlineNumber.
     */
    fun createStep2Screen(configuredText: String?, configuredNumber: String?): EmergencyScreenState {
        val hasConfiguredHotline = !configuredText.isNullOrBlank() && !configuredNumber.isNullOrBlank()
        return if (hasConfiguredHotline) {
            EmergencyScreenState(
                step = EmergencyStep.STEP2_DECLINED_OR_HOTLINE,
                messageText = "$TEXT_TELL_ADULT\n$configuredText",
                showDeclineButton = false,
                hotlineText = configuredText,
                hotlineNumber = configuredNumber,
            )
        } else {
            EmergencyScreenState(
                step = EmergencyStep.STEP2_DECLINED_OR_HOTLINE,
                messageText = TEXT_TELL_ADULT,
                showDeclineButton = false,
                hotlineText = null,
                hotlineNumber = null,
            )
        }
    }

    /**
     * Formats alert JSON payload for server delivery.
     */
    fun formatAlertJson(alert: SafetyAlert): JSONObject {
        val obj = JSONObject()
        obj.put("alert_id", alert.alertId)
        obj.put("level", alert.level.apiValue)
        obj.put("category", alert.category)
        obj.put("session_id", alert.sessionId)
        obj.put("question_hash", alert.questionHash)
        obj.put("detected_at", alert.detectedAtMs)
        obj.put("snippet", alert.snippet)
        return obj
    }
}
