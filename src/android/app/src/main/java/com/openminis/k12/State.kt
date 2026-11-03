package com.openminis.k12

import java.util.UUID

data class Profile(
    val childId: String? = null,
    val deviceId: String? = null,
    val nickname: String? = null,
    val stage: StudyStage? = null,
    val onboardingStep: OnboardingStep = OnboardingStep.STAGE,
    val onboardingComplete: Boolean = false,
    val pinSalt: String? = null,
    val pinHash: String? = null,
    val backupSalt: String? = null,
    val backupHash: String? = null,
    val pinFailures: Int = 0,
    val pinLockedUntilMs: Long = 0,
    val pairing: String = "unpaired",
    val dailyLimitOverrideMin: Int? = null,
    val weekendExtraOverrideMin: Int? = null,
    val breakOverrideMin: Int? = null,
    val limitReachedDay: String? = null,
) {
    fun hasPin(): Boolean = !pinSalt.isNullOrBlank() && !pinHash.isNullOrBlank()
}

enum class OnboardingStep { STAGE, IDENTITY, SECRETS, PERMISSIONS, DONE }

data class UsageLedger(
    val dayKey: String = "",
    val usedMs: Long = 0,
    val extraGrantMs: Long = 0,
    val continuousMs: Long = 0,
    val anchorMs: Long? = null,
    val backgroundSinceMs: Long? = null,
)

data class ParentSession(
    val unlocked: Boolean = false,
    val lastTouchMs: Long = 0,
)

data class K12State(
    val profile: Profile = Profile(),
    val ledger: UsageLedger = UsageLedger(),
    val parent: ParentSession = ParentSession(),
    val clockAnomaly: Boolean = false,
)

object Ids {
    fun isUuidV4(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return try {
            UUID.fromString(value).version() == 4
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    fun newId(): String = UUID.randomUUID().toString()
}

/** Optional local label. Empty becomes absent. Not a required name. */
fun normalizeNickname(raw: String): String? {
    val trimmed = raw.trim().replace("\n", "").replace("\r", "").take(16)
    return trimmed.ifEmpty { null }
}
