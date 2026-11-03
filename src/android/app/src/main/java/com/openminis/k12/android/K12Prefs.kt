package com.openminis.k12.android

import android.content.Context
import com.openminis.k12.K12State
import com.openminis.k12.OnboardingStep
import com.openminis.k12.ParentSession
import com.openminis.k12.Profile
import com.openminis.k12.StudyStage
import com.openminis.k12.UsageLedger

/** Local profile and today's usage. No plaintext PIN, no device model. */
class K12Prefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun load(): K12State {
        val profile = Profile(
            childId = prefs.getString(KEY_CHILD, null),
            deviceId = prefs.getString(KEY_DEVICE, null),
            nickname = prefs.getString(KEY_NICK, null),
            stage = StudyStage.fromStored(prefs.getString(KEY_STAGE, null)),
            onboardingStep = prefs.getString(KEY_STEP, null)?.let { stored ->
                OnboardingStep.entries.firstOrNull { it.name == stored }
            } ?: OnboardingStep.STAGE,
            onboardingComplete = prefs.getBoolean(KEY_DONE, false),
            pinSalt = prefs.getString(KEY_PIN_SALT, null),
            pinHash = prefs.getString(KEY_PIN_HASH, null),
            backupSalt = prefs.getString(KEY_BACKUP_SALT, null),
            backupHash = prefs.getString(KEY_BACKUP_HASH, null),
            pinFailures = prefs.getInt(KEY_PIN_FAILS, 0),
            pinLockedUntilMs = prefs.getLong(KEY_PIN_LOCK, 0),
            pairing = prefs.getString(KEY_PAIRING, "unpaired") ?: "unpaired",
            dailyLimitOverrideMin = prefs.getString(KEY_DAILY, null)?.toIntOrNull(),
            weekendExtraOverrideMin = prefs.getString(KEY_WEEKEND, null)?.toIntOrNull(),
            breakOverrideMin = prefs.getString(KEY_BREAK, null)?.toIntOrNull(),
            limitReachedDay = prefs.getString(KEY_LIMIT_DAY, null),
        )
        val ledger = UsageLedger(
            dayKey = prefs.getString(KEY_DAY, "") ?: "",
            usedMs = prefs.getLong(KEY_USED, 0),
            extraGrantMs = prefs.getLong(KEY_EXTRA, 0),
            continuousMs = prefs.getLong(KEY_CONTINUOUS, 0),
            anchorMs = prefs.getString(KEY_ANCHOR, null)?.toLongOrNull(),
            backgroundSinceMs = prefs.getString(KEY_BG, null)?.toLongOrNull(),
        )
        return K12State(profile = profile, ledger = ledger, parent = ParentSession())
    }

    fun save(state: K12State) {
        val p = state.profile
        val l = state.ledger
        prefs.edit()
            .putString(KEY_CHILD, p.childId)
            .putString(KEY_DEVICE, p.deviceId)
            .putString(KEY_NICK, p.nickname)
            .putString(KEY_STAGE, p.stage?.stored)
            .putString(KEY_STEP, p.onboardingStep.name)
            .putBoolean(KEY_DONE, p.onboardingComplete)
            .putString(KEY_PIN_SALT, p.pinSalt)
            .putString(KEY_PIN_HASH, p.pinHash)
            .putString(KEY_BACKUP_SALT, p.backupSalt)
            .putString(KEY_BACKUP_HASH, p.backupHash)
            .putInt(KEY_PIN_FAILS, p.pinFailures)
            .putLong(KEY_PIN_LOCK, p.pinLockedUntilMs)
            .putString(KEY_PAIRING, p.pairing)
            .putString(KEY_DAILY, p.dailyLimitOverrideMin?.toString())
            .putString(KEY_WEEKEND, p.weekendExtraOverrideMin?.toString())
            .putString(KEY_BREAK, p.breakOverrideMin?.toString())
            .putString(KEY_LIMIT_DAY, p.limitReachedDay)
            .putString(KEY_DAY, l.dayKey)
            .putLong(KEY_USED, l.usedMs)
            .putLong(KEY_EXTRA, l.extraGrantMs)
            .putLong(KEY_CONTINUOUS, l.continuousMs)
            .putString(KEY_ANCHOR, l.anchorMs?.toString())
            .putString(KEY_BG, l.backgroundSinceMs?.toString())
            .apply()
    }

    private companion object {
        const val NAME = "k12_profile"
        const val KEY_CHILD = "child_id"
        const val KEY_DEVICE = "device_id"
        const val KEY_NICK = "nickname"
        const val KEY_STAGE = "stage"
        const val KEY_STEP = "step"
        const val KEY_DONE = "done"
        const val KEY_PIN_SALT = "pin_salt"
        const val KEY_PIN_HASH = "pin_hash"
        const val KEY_BACKUP_SALT = "backup_salt"
        const val KEY_BACKUP_HASH = "backup_hash"
        const val KEY_PIN_FAILS = "pin_failures"
        const val KEY_PIN_LOCK = "pin_locked_until"
        const val KEY_PAIRING = "pairing"
        const val KEY_DAILY = "daily_override"
        const val KEY_WEEKEND = "weekend_override"
        const val KEY_BREAK = "break_override"
        const val KEY_LIMIT_DAY = "limit_day"
        const val KEY_DAY = "day_key"
        const val KEY_USED = "used_ms"
        const val KEY_EXTRA = "extra_ms"
        const val KEY_CONTINUOUS = "continuous_ms"
        const val KEY_ANCHOR = "anchor_ms"
        const val KEY_BG = "background_since_ms"
    }
}
