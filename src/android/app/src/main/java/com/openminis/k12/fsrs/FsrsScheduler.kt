/*
 * Ported from open-spaced-repetition/java-fsrs v1.0.0 Scheduler (MIT).
 * Copyright (c) 2025 Open Spaced Repetition
 * See LICENSE-java-fsrs.txt in this package.
 */
package com.openminis.k12.fsrs

import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Random
import kotlin.math.E
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round

/**
 * FSRS-6 scheduler with java-fsrs v1.0.0 defaults. Fuzzing off by default in
 * tests for deterministic py-fsrs vectors; production may enable it.
 */
class FsrsScheduler(
    val parameters: DoubleArray = DEFAULT_PARAMETERS.copyOf(),
    val desiredRetention: Double = 0.9,
    val learningSteps: Array<Duration> = arrayOf(Duration.ofMinutes(1), Duration.ofMinutes(10)),
    val relearningSteps: Array<Duration> = arrayOf(Duration.ofMinutes(10)),
    val maximumInterval: Int = 36500,
    val enableFuzzing: Boolean = true,
    randomSeedNumber: Int = 42,
) {
    private val decay: Double = -parameters[20]
    private val factor: Double = 0.9.pow(1.0 / decay) - 1
    private val random = Random(randomSeedNumber.toLong())

    fun getCardRetrievability(card: FsrsCard, current: Instant = Instant.now()): Double {
        val last = card.lastReview ?: return 0.0
        val elapsedDays = max(0, ChronoUnit.DAYS.between(last, current).toInt())
        val stability = card.stability ?: return 0.0
        return (1 + factor * elapsedDays / stability).pow(decay)
    }

    fun reviewCard(
        card: FsrsCard,
        rating: FsrsRating,
        reviewDatetime: Instant = Instant.now(),
        reviewDuration: Int? = null,
    ): FsrsCardAndLog {
        val c = card.copyCard()
        val daysSinceLastReview = c.lastReview?.let {
            ChronoUnit.DAYS.between(it, reviewDatetime).toInt()
        }
        var nextInterval = Duration.ZERO

        when (c.state) {
            FsrsState.LEARNING -> {
                if (c.stability == null && c.difficulty == null) {
                    c.stability = initialStability(rating)
                    c.difficulty = initialDifficulty(rating)
                } else if (daysSinceLastReview != null && daysSinceLastReview < 1) {
                    c.stability = shortTermStability(c.stability!!, rating)
                    c.difficulty = nextDifficulty(c.difficulty!!, rating)
                } else {
                    val r = getCardRetrievability(c, reviewDatetime)
                    c.stability = nextStability(c.difficulty!!, c.stability!!, r, rating)
                    c.difficulty = nextDifficulty(c.difficulty!!, rating)
                }

                if (learningSteps.isEmpty() ||
                    (c.step != null && c.step!! >= learningSteps.size &&
                        rating != FsrsRating.AGAIN)
                ) {
                    c.state = FsrsState.REVIEW
                    c.step = null
                    nextInterval = Duration.ofDays(nextIntervalDays(c.stability!!).toLong())
                } else {
                    when (rating) {
                        FsrsRating.AGAIN -> {
                            c.step = 0
                            nextInterval = learningSteps[c.step!!]
                        }
                        FsrsRating.HARD -> {
                            nextInterval = when {
                                c.step == 0 && learningSteps.size == 1 ->
                                    Duration.ofMillis(Math.round(learningSteps[0].toMillis() * 1.5))
                                c.step == 0 ->
                                    Duration.ofMillis(
                                        Math.round(
                                            (learningSteps[0].toMillis() + learningSteps[1].toMillis()) / 2.0,
                                        ),
                                    )
                                else -> learningSteps[c.step!!]
                            }
                        }
                        FsrsRating.GOOD -> {
                            if (c.step!! + 1 == learningSteps.size) {
                                c.state = FsrsState.REVIEW
                                c.step = null
                                nextInterval = Duration.ofDays(nextIntervalDays(c.stability!!).toLong())
                            } else {
                                c.step = c.step!! + 1
                                nextInterval = learningSteps[c.step!!]
                            }
                        }
                        FsrsRating.EASY -> {
                            c.state = FsrsState.REVIEW
                            c.step = null
                            nextInterval = Duration.ofDays(nextIntervalDays(c.stability!!).toLong())
                        }
                    }
                }
            }

            FsrsState.REVIEW -> {
                if (daysSinceLastReview != null && daysSinceLastReview < 1) {
                    c.stability = shortTermStability(c.stability!!, rating)
                } else {
                    c.stability = nextStability(
                        c.difficulty!!,
                        c.stability!!,
                        getCardRetrievability(c, reviewDatetime),
                        rating,
                    )
                }
                c.difficulty = nextDifficulty(c.difficulty!!, rating)
                when (rating) {
                    FsrsRating.AGAIN -> {
                        if (relearningSteps.isEmpty()) {
                            nextInterval = Duration.ofDays(nextIntervalDays(c.stability!!).toLong())
                        } else {
                            c.state = FsrsState.RELEARNING
                            c.step = 0
                            nextInterval = relearningSteps[0]
                        }
                    }
                    FsrsRating.HARD, FsrsRating.GOOD, FsrsRating.EASY -> {
                        nextInterval = Duration.ofDays(nextIntervalDays(c.stability!!).toLong())
                    }
                }
            }

            FsrsState.RELEARNING -> {
                if (daysSinceLastReview != null && daysSinceLastReview < 1) {
                    c.stability = shortTermStability(c.stability!!, rating)
                } else {
                    c.stability = nextStability(
                        c.difficulty!!,
                        c.stability!!,
                        getCardRetrievability(c, reviewDatetime),
                        rating,
                    )
                }
                c.difficulty = nextDifficulty(c.difficulty!!, rating)
                if (relearningSteps.isEmpty() ||
                    (c.step != null && c.step!! >= relearningSteps.size && rating != FsrsRating.AGAIN)
                ) {
                    c.state = FsrsState.REVIEW
                    c.step = null
                    nextInterval = Duration.ofDays(nextIntervalDays(c.stability!!).toLong())
                } else {
                    when (rating) {
                        FsrsRating.AGAIN -> {
                            c.step = 0
                            nextInterval = relearningSteps[0]
                        }
                        FsrsRating.HARD -> {
                            nextInterval = when {
                                c.step == 0 && relearningSteps.size == 1 ->
                                    Duration.ofMillis(Math.round(relearningSteps[0].toMillis() * 1.5))
                                c.step == 0 && learningSteps.size >= 2 ->
                                    Duration.ofMillis(
                                        Math.round(
                                            (learningSteps[0].toMillis() + learningSteps[1].toMillis()) / 2.0,
                                        ),
                                    )
                                else -> relearningSteps[c.step!!]
                            }
                        }
                        FsrsRating.GOOD -> {
                            if (c.step!! + 1 == relearningSteps.size) {
                                c.state = FsrsState.REVIEW
                                c.step = null
                                nextInterval = Duration.ofDays(nextIntervalDays(c.stability!!).toLong())
                            } else {
                                c.step = c.step!! + 1
                                nextInterval = relearningSteps[c.step!!]
                            }
                        }
                        FsrsRating.EASY -> {
                            c.state = FsrsState.REVIEW
                            c.step = null
                            nextInterval = Duration.ofDays(nextIntervalDays(c.stability!!).toLong())
                        }
                    }
                }
            }
        }

        if (enableFuzzing && c.state == FsrsState.REVIEW) {
            nextInterval = fuzzedInterval(nextInterval)
        }
        c.due = reviewDatetime.plus(nextInterval)
        c.lastReview = reviewDatetime
        return FsrsCardAndLog(
            c,
            FsrsReviewLog(c.cardId, rating, reviewDatetime, reviewDuration),
        )
    }

    private fun clampStability(s: Double) = max(s, STABILITY_MIN)
    private fun clampDifficulty(d: Double) = min(max(d, MIN_DIFFICULTY), MAX_DIFFICULTY)

    private fun initialStability(rating: FsrsRating): Double =
        clampStability(parameters[rating.value - 1])

    private fun initialDifficulty(rating: FsrsRating): Double {
        val d = parameters[4] - exp(parameters[5] * (rating.value - 1)) + 1
        return clampDifficulty(d)
    }

    private fun shortTermStability(stability: Double, rating: FsrsRating): Double {
        var increase = exp(parameters[17] * (rating.value - 3 + parameters[18])) *
            stability.pow(-parameters[19])
        if (rating == FsrsRating.GOOD || rating == FsrsRating.EASY) {
            increase = max(increase, 1.0)
        }
        return clampStability(stability * increase)
    }

    private fun linearDamping(deltaDifficulty: Double, difficulty: Double): Double =
        (10.0 - difficulty) * deltaDifficulty / 9.0

    private fun meanReversion(arg1: Double, arg2: Double): Double =
        parameters[7] * arg1 + (1 - parameters[7]) * arg2

    private fun nextDifficulty(difficulty: Double, rating: FsrsRating): Double {
        val arg1 = initialDifficulty(FsrsRating.EASY)
        val delta = -(parameters[6] * (rating.value - 3))
        val arg2 = difficulty + linearDamping(delta, difficulty)
        return clampDifficulty(meanReversion(arg1, arg2))
    }

    private fun nextForgetStability(difficulty: Double, stability: Double, retrievability: Double): Double {
        val longTerm = parameters[11] *
            difficulty.pow(-parameters[12]) *
            ((stability + 1).pow(parameters[13]) - 1) *
            exp((1 - retrievability) * parameters[14])
        val shortTerm = stability / exp(parameters[17] * parameters[18])
        return min(longTerm, shortTerm)
    }

    private fun nextRecallStability(
        difficulty: Double,
        stability: Double,
        retrievability: Double,
        rating: FsrsRating,
    ): Double {
        val hardPenalty = if (rating == FsrsRating.HARD) parameters[15] else 1.0
        val easyBonus = if (rating == FsrsRating.EASY) parameters[16] else 1.0
        return stability * (
            1 +
                exp(parameters[8]) *
                (11 - difficulty) *
                stability.pow(-parameters[9]) *
                (exp((1 - retrievability) * parameters[10]) - 1) *
                hardPenalty *
                easyBonus
            )
    }

    private fun nextStability(
        difficulty: Double,
        stability: Double,
        retrievability: Double,
        rating: FsrsRating,
    ): Double {
        val next = if (rating == FsrsRating.AGAIN) {
            nextForgetStability(difficulty, stability, retrievability)
        } else {
            nextRecallStability(difficulty, stability, retrievability, rating)
        }
        return clampStability(next)
    }

    private fun nextIntervalDays(stability: Double): Int {
        var days = round(
            (stability / factor) * (desiredRetention.pow(1 / decay) - 1),
        ).toInt()
        days = max(days, 1)
        days = min(days, maximumInterval)
        return days
    }

    private fun fuzzedInterval(interval: Duration): Duration {
        val intervalDays = interval.toDays().toInt()
        if (intervalDays < 2.5) return interval
        var delta = 1.0
        for (range in FUZZ_RANGES) {
            delta += range.factor * max(min(intervalDays.toDouble(), range.end - range.start), 0.0)
        }
        var minIvl = round(intervalDays - delta).toInt()
        var maxIvl = round(intervalDays + delta).toInt()
        minIvl = max(2, minIvl)
        maxIvl = min(maxIvl, maximumInterval)
        minIvl = min(minIvl, maxIvl)
        val fuzzed = (random.nextDouble() * (maxIvl - minIvl + 1)) + minIvl
        val days = min(round(fuzzed).toInt(), maximumInterval)
        return Duration.ofDays(days.toLong())
    }

    private data class FuzzRange(val start: Double, val end: Double, val factor: Double)

    companion object {
        const val STABILITY_MIN = 0.001
        private const val MIN_DIFFICULTY = 1.0
        private const val MAX_DIFFICULTY = 10.0

        /** java-fsrs v1.0.0 defaults (FSRS-6, 21 weights). */
        val DEFAULT_PARAMETERS: DoubleArray = doubleArrayOf(
            0.2172, 1.1771, 3.2602, 16.1507, 7.0114, 0.57, 2.0966, 0.0069,
            1.5261, 0.112, 1.0178, 1.849, 0.1133, 0.3127, 2.2934, 0.2191,
            3.0004, 0.7536, 0.3332, 0.1437, 0.2,
        )

        private val FUZZ_RANGES = arrayOf(
            FuzzRange(2.5, 7.0, 0.15),
            FuzzRange(7.0, 20.0, 0.1),
            FuzzRange(20.0, Double.POSITIVE_INFINITY, 0.05),
        )

        /** Deterministic scheduler for tests / py-fsrs vectors. */
        fun forTests(): FsrsScheduler = FsrsScheduler(enableFuzzing = false)
    }
}
