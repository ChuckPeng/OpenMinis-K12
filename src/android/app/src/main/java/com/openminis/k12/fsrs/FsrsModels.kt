/*
 * Ported from open-spaced-repetition/java-fsrs v1.0.0 (MIT).
 * Copyright (c) 2025 Open Spaced Repetition
 * See LICENSE-java-fsrs.txt in this package.
 *
 * Kotlin adaptation for OpenMinis-K12 (ADR-0010). No optimizer.
 */
package com.openminis.k12.fsrs

import java.time.Instant

enum class FsrsRating(val value: Int) {
    AGAIN(1),
    HARD(2),
    GOOD(3),
    EASY(4),
}

enum class FsrsState(val value: Int) {
    LEARNING(1),
    REVIEW(2),
    RELEARNING(3),
}

data class FsrsCard(
    val cardId: Int,
    var state: FsrsState = FsrsState.LEARNING,
    var step: Int? = 0,
    var stability: Double? = null,
    var difficulty: Double? = null,
    var due: Instant = Instant.now(),
    var lastReview: Instant? = null,
) {
    fun copyCard(): FsrsCard = copy(
        state = state,
        step = step,
        stability = stability,
        difficulty = difficulty,
        due = due,
        lastReview = lastReview,
    )

    companion object {
        fun newCard(cardId: Int = Instant.now().toEpochMilli().toInt(), due: Instant = Instant.now()): FsrsCard =
            FsrsCard(cardId = cardId, state = FsrsState.LEARNING, step = 0, due = due)
    }
}

data class FsrsReviewLog(
    val cardId: Int,
    val rating: FsrsRating,
    val reviewDatetime: Instant,
    val reviewDuration: Int? = null,
)

data class FsrsCardAndLog(
    val card: FsrsCard,
    val reviewLog: FsrsReviewLog,
)
