package com.openminis.k12.device

import com.openminis.k12.LimitEdits

/** Fields taken from a whitelisted heartbeat config. Absent means "not in this payload". */
data class ServerPolicyPatch(
    val dailyLimitMinutes: Int? = null,
    val weekendExtraMinutes: Int? = null,
    val breakMinutes: Int? = null,
    val turnLimit: Int? = null,
    val imageLimit: Int? = null,
    val companionLimitMinutes: Int? = null,
    val stageStored: String? = null,
    val revealPolicy: String? = null,
    val hotlineText: String? = null,
    val hotlineNumber: String? = null,
    val lateNightStart: String? = null,
    val lateNightEnd: String? = null,
    val studyDayMinMinutes: Int? = null,
    val examMode: Boolean? = null,
    val notice: String? = null,
)

data class LimitTriple(val daily: Int, val weekend: Int, val breakMin: Int)

fun serverPolicyPatch(fields: Map<String, Any?>): ServerPolicyPatch {
    fun num(vararg keys: String): Int? {
        for (key in keys) {
            val value = fields[key] ?: continue
            val parsed = when (value) {
                is Number -> value.toInt()
                is String -> value.toIntOrNull()
                else -> null
            }
            if (parsed != null) return parsed
        }
        return null
    }
    val reveal = fields["reveal_policy"]?.toString()?.take(2000)
    return ServerPolicyPatch(
        dailyLimitMinutes = num("daily_limit_minutes", "daily_time_limit_minutes"),
        weekendExtraMinutes = num("weekend_extra_minutes"),
        breakMinutes = num("break_reminder_minutes", "screen_time_break_minutes", "rest_duration_minutes"),
        turnLimit = num("turn_limit")?.takeIf { it in 1..10_000 },
        imageLimit = num("image_limit")?.takeIf { it in 1..10_000 },
        companionLimitMinutes = num("companion_limit_minutes")?.takeIf { it in 1..10_000 },
        stageStored = fields["stage"] as? String,
        revealPolicy = reveal,
        hotlineText = fields["hotline_text"] as? String,
        hotlineNumber = fields["hotline_number"] as? String,
        lateNightStart = fields["late_night_start"] as? String,
        lateNightEnd = fields["late_night_end"] as? String,
        studyDayMinMinutes = num("study_day_min_minutes"),
        examMode = fields["exam_mode_enabled"] as? Boolean,
        notice = fields["parent_notice_board"] as? String,
    )
}

/** Merges server time limits onto the current triple. Out-of-range input is ignored. */
fun mergeServerLimits(current: LimitTriple, patch: ServerPolicyPatch): LimitTriple? {
    if (patch.dailyLimitMinutes == null && patch.weekendExtraMinutes == null && patch.breakMinutes == null) {
        return null
    }
    val next = LimitTriple(
        daily = patch.dailyLimitMinutes ?: current.daily,
        weekend = patch.weekendExtraMinutes ?: current.weekend,
        breakMin = patch.breakMinutes ?: current.breakMin,
    )
    return next.takeIf { LimitEdits.acceptable(it.daily, it.weekend, it.breakMin) }
}
