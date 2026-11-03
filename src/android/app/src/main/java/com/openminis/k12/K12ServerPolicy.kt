package com.openminis.k12

import com.openminis.k12.device.ServerPolicyPatch

/**
 * Last parental policy applied from a heartbeat. Defaults match WP-B-7 until
 * the server sends a newer config. This object does not hold an API key.
 */
object K12ServerPolicy {
    @Volatile var quota: ScreenTime.DailyQuota = ScreenTime.DailyQuota()
    @Volatile var revealPolicy: String? = null
    @Volatile var hotlineText: String? = null
    @Volatile var hotlineNumber: String? = null
    @Volatile var lateNightStart: String? = null
    @Volatile var lateNightEnd: String? = null
    @Volatile var studyDayMinMinutes: Int? = null
    @Volatile var examMode: Boolean? = null
    @Volatile var notice: String? = null

    fun apply(patch: ServerPolicyPatch) {
        quota = ScreenTime.DailyQuota(
            maxRounds = patch.turnLimit ?: quota.maxRounds,
            maxImages = patch.imageLimit ?: quota.maxImages,
            maxCompanionMinutes = patch.companionLimitMinutes ?: quota.maxCompanionMinutes,
        )
        if (patch.revealPolicy != null) revealPolicy = patch.revealPolicy
        if (patch.hotlineText != null) hotlineText = patch.hotlineText
        if (patch.hotlineNumber != null) hotlineNumber = patch.hotlineNumber
        if (patch.lateNightStart != null) lateNightStart = patch.lateNightStart
        if (patch.lateNightEnd != null) lateNightEnd = patch.lateNightEnd
        if (patch.studyDayMinMinutes != null) studyDayMinMinutes = patch.studyDayMinMinutes
        if (patch.examMode != null) examMode = patch.examMode
        if (patch.notice != null) notice = patch.notice
    }
}
