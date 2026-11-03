package com.openminis.k12.mistakes

import com.openminis.k12.fsrs.FsrsScheduler
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.ln
import kotlin.math.pow

/**
 * Mastery `m` (reports metric 7 / S5):
 * 近 30 天首次作答，半衰期 14 天指数加权正确率 × 0.7
 * + 该点错题 FSRS 可提取率均值 × 0.3。无错题只用前一项。
 * 尝试少于 3 次不定级。
 */
object Mastery {
    const val WINDOW_DAYS = 30L
    const val HALF_LIFE_DAYS = 14.0
    const val MIN_ATTEMPTS = 3
    const val WEIGHT_ACCURACY = 0.7
    const val WEIGHT_RETRIEVABILITY = 0.3

    data class FirstAttempt(
        val correct: Boolean,
        val at: Instant,
    )

    fun score(
        attempts: List<FirstAttempt>,
        retrievabilities: List<Double>,
        now: Instant = Instant.now(),
    ): Pair<Double?, MasteryLevel> {
        if (attempts.size < MIN_ATTEMPTS) return null to MasteryLevel.UNRATED
        val cutoff = now.minus(WINDOW_DAYS, ChronoUnit.DAYS)
        val recent = attempts.filter { !it.at.isBefore(cutoff) }
        if (recent.size < MIN_ATTEMPTS) return null to MasteryLevel.UNRATED
        val weightedAcc = exponentialWeightedAccuracy(recent, now)
        val m = if (retrievabilities.isEmpty()) {
            weightedAcc
        } else {
            weightedAcc * WEIGHT_ACCURACY + retrievabilities.average() * WEIGHT_RETRIEVABILITY
        }
        return m to levelFor(m)
    }

    fun levelFor(m: Double): MasteryLevel = when {
        m >= 0.85 -> MasteryLevel.MASTERED
        m >= 0.65 -> MasteryLevel.GOOD
        m >= 0.40 -> MasteryLevel.PASS
        else -> MasteryLevel.WEAK
    }

    fun pushReason(level: MasteryLevel, dueCount: Int, prereqGaps: Int): String = buildString {
        append("掌握度=").append(level.labelZh)
        if (dueCount > 0) append("；到期复习 ").append(dueCount).append(" 题")
        if (prereqGaps > 0) append("；先修缺口 ").append(prereqGaps)
        if (level == MasteryLevel.WEAK || level == MasteryLevel.PASS) {
            append("；建议举一反三（基础/同级/稍难）")
        }
    }

    private fun exponentialWeightedAccuracy(attempts: List<FirstAttempt>, now: Instant): Double {
        val lambda = ln(2.0) / HALF_LIFE_DAYS
        var wSum = 0.0
        var cSum = 0.0
        for (a in attempts) {
            val ageDays = ChronoUnit.DAYS.between(a.at, now).toDouble().coerceAtLeast(0.0)
            val w = Math.E.pow(-lambda * ageDays)
            wSum += w
            if (a.correct) cSum += w
        }
        return if (wSum == 0.0) 0.0 else cSum / wSum
    }

    fun meanRetrievability(
        cards: List<com.openminis.k12.fsrs.FsrsCard>,
        scheduler: FsrsScheduler,
        now: Instant = Instant.now(),
    ): List<Double> = cards.map { scheduler.getCardRetrievability(it, now) }
}
