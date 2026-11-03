package com.openminis.k12.mistakes

import com.openminis.k12.fsrs.FsrsCard
import com.openminis.k12.fsrs.FsrsRating
import com.openminis.k12.tutoring.Disposition
import java.time.Instant

/** 错因五类 + 未判定（不进占比分母）. */
enum class ErrorCause(val labelZh: String, val inStatsDenominator: Boolean) {
    CONCEPT("概念不清", true),
    MISREAD("审题错误", true),
    CALC("计算粗心", true),
    METHOD("不会方法", true),
    EXPRESS("表达不规范", true),
    UNKNOWN("未判定", false),
}

enum class MasteryLevel(val labelZh: String) {
    MASTERED("精通"),
    GOOD("良好"),
    PASS("合格"),
    WEAK("薄弱"),
    UNRATED("不定级"),
}

data class MistakeEntry(
    val id: String,
    val childId: String,
    val questionHash: String,
    val subject: String,
    val kpIds: List<String> = emptyList(),
    val graphNodeIds: List<String> = emptyList(),
    val cause: ErrorCause = ErrorCause.UNKNOWN,
    val disposition: Disposition = Disposition.ACTIVE,
    val sessionId: String? = null,
    /** Local-only thumbnail path; never uploaded as pixels in events. */
    val localImagePath: String? = null,
    val createdAt: Instant = Instant.now(),
    val correctedAt: Instant? = null,
    val mastery: MasteryLevel = MasteryLevel.UNRATED,
    val masteryScore: Double? = null,
    val pushReason: String? = null,
    val fsrs: FsrsCard,
    val reviewCount: Int = 0,
    val lastRating: FsrsRating? = null,
) {
    val due: Instant get() = fsrs.due
    val isCorrected: Boolean get() = correctedAt != null
    val countsInMistakeStats: Boolean
        get() = disposition != Disposition.PENDING_CHECK
}

sealed class MistakeEvent {
    abstract val eventId: String
    abstract val occurredAt: Instant
    abstract val childId: String
    abstract val mistakeId: String
    abstract val questionHash: String

    data class Created(
        override val eventId: String,
        override val occurredAt: Instant,
        override val childId: String,
        override val mistakeId: String,
        override val questionHash: String,
        val cause: ErrorCause,
    ) : MistakeEvent()

    data class Corrected(
        override val eventId: String,
        override val occurredAt: Instant,
        override val childId: String,
        override val mistakeId: String,
        override val questionHash: String,
    ) : MistakeEvent()

    data class ReviewDone(
        override val eventId: String,
        override val occurredAt: Instant,
        override val childId: String,
        override val mistakeId: String,
        override val questionHash: String,
        val rating: FsrsRating,
        val nextDue: Instant,
    ) : MistakeEvent()
}
