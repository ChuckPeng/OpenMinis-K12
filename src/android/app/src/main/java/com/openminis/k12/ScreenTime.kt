package com.openminis.k12

/**
 * This app's own foreground time. It does not read other apps or token usage.
 *
 * A backward clock step adds nothing. A single gap longer than [MAX_SPAN_MS]
 * is treated as a clock step: nothing is added, and the caller logs an anomaly
 * without usage content. Background time is not counted. Continuous use clears
 * after [REST_CLEARS_CONTINUOUS_MS] in the background.
 */
object ScreenTime {
    const val MAX_SPAN_MS = 6L * 60 * 60 * 1000
    const val REST_CLEARS_CONTINUOUS_MS = 60L * 1000
    const val EXTENSION_QUANTUM_MIN = 15
    const val PARENT_IDLE_MS = 5L * 60 * 1000

    data class Limits(val dailyMin: Int, val weekendExtraMin: Int, val breakMin: Int)

    fun limits(stage: StudyStage?, profile: Profile): Limits {
        val base = stage ?: StudyStage.PRIMARY_LOWER
        return Limits(
            dailyMin = profile.dailyLimitOverrideMin ?: base.dailyLimitMinutes(),
            weekendExtraMin = profile.weekendExtraOverrideMin ?: base.weekendExtraMinutes(),
            breakMin = profile.breakOverrideMin ?: base.breakReminderMinutes(),
        )
    }

    fun allowanceMs(stage: StudyStage?, profile: Profile, ledger: UsageLedger, epochMs: Long): Long {
        val limits = limits(stage, profile)
        val weekend = if (epochMs > 0 && StudyClock.isWeekend(epochMs)) limits.weekendExtraMin else 0
        return (limits.dailyMin + weekend) * 60_000L + ledger.extraGrantMs
    }

    fun limitReached(state: K12State, epochMs: Long): Boolean {
        val stage = state.profile.stage
        val allowance = allowanceMs(stage, state.profile, state.ledger, epochMs)
        return state.ledger.usedMs >= allowance && allowance > 0
    }

    fun breakDue(state: K12State): Boolean {
        val stage = state.profile.stage ?: return false
        val minutes = limits(stage, state.profile).breakMin
        return state.ledger.continuousMs >= minutes * 60_000L
    }

    fun remainingMs(state: K12State, epochMs: Long): Long {
        val allowance = allowanceMs(state.profile.stage, state.profile, state.ledger, epochMs)
        return (allowance - state.ledger.usedMs).coerceAtLeast(0)
    }

    fun onForeground(state: K12State, now: Long): K12State {
        val rolled = rollDay(state, now)
        val ledger = rolled.ledger
        val away = ledger.backgroundSinceMs?.let { now - it }
        val anomaly = away != null && away < 0
        val continuous = when {
            away == null -> ledger.continuousMs
            away < 0 -> ledger.continuousMs
            away >= REST_CLEARS_CONTINUOUS_MS -> 0L
            else -> ledger.continuousMs
        }
        return rolled.copy(
            ledger = ledger.copy(
                continuousMs = continuous,
                anchorMs = now,
                backgroundSinceMs = null,
            ),
            clockAnomaly = rolled.clockAnomaly || anomaly,
        )
    }

    fun onBackground(state: K12State, now: Long): K12State {
        val advanced = advance(state, now)
        return advanced.copy(
            ledger = advanced.ledger.copy(anchorMs = null, backgroundSinceMs = now),
        )
    }

    fun onTick(state: K12State, now: Long): K12State {
        if (state.ledger.anchorMs == null) return rollDay(state, now)
        return advance(state, now)
    }

    fun grant(state: K12State, now: Long, minutes: Int): K12State {
        val rolled = rollDay(state, now)
        val add = minutes.coerceAtLeast(0) * 60_000L
        return rolled.copy(ledger = rolled.ledger.copy(extraGrantMs = rolled.ledger.extraGrantMs + add))
    }

    /** Drop an in-memory foreground anchor after process death so the gap is not counted. */
    fun discardStaleAnchor(state: K12State, now: Long): K12State {
        if (state.ledger.anchorMs == null) return state
        return state.copy(
            ledger = state.ledger.copy(anchorMs = null, backgroundSinceMs = now),
        )
    }

    private fun advance(state: K12State, now: Long): K12State {
        val rolled = rollDay(state, now)
        val anchor = rolled.ledger.anchorMs ?: return rolled
        val delta = now - anchor
        if (delta < 0 || delta > MAX_SPAN_MS) {
            return rolled.copy(
                ledger = rolled.ledger.copy(anchorMs = now),
                clockAnomaly = true,
            )
        }
        val used = rolled.ledger.usedMs + delta
        val continuous = rolled.ledger.continuousMs + delta
        val nextLedger = rolled.ledger.copy(usedMs = used, continuousMs = continuous, anchorMs = now)
        val next = rolled.copy(ledger = nextLedger)
        val day = StudyClock.dayKey(now)
        val crossed = !limitReached(rolled, now) && limitReached(next, now)
        val profile = if (crossed) next.profile.copy(limitReachedDay = day) else next.profile
        return next.copy(profile = profile)
    }

    private fun rollDay(state: K12State, now: Long): K12State {
        val day = StudyClock.dayKey(now)
        if (state.ledger.dayKey == day) return state
        return state.copy(
            ledger = state.ledger.copy(
                dayKey = day,
                usedMs = 0,
                extraGrantMs = 0,
                continuousMs = 0,
            ),
            profile = state.profile.copy(limitReachedDay = null),
        )
    }
}

object LimitEdits {
    fun acceptable(dailyMin: Int, weekendExtraMin: Int, breakMin: Int): Boolean =
        dailyMin in 10..180 && weekendExtraMin in 0..120 && breakMin in 5..90
}
