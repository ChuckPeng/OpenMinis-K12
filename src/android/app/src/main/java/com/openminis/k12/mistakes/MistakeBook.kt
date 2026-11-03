package com.openminis.k12.mistakes

import com.openminis.k12.Ids
import com.openminis.k12.fsrs.FsrsCard
import com.openminis.k12.fsrs.FsrsRating
import com.openminis.k12.fsrs.FsrsScheduler
import com.openminis.k12.tutoring.Disposition
import java.time.Instant

/**
 * In-memory mistake book (B1–B3). Persisted store can wrap this later.
 * No real family data; keyed only by synthetic `child_id`.
 */
class MistakeBook(
    private val scheduler: FsrsScheduler = FsrsScheduler.forTests(),
) {
    private val byChild = mutableMapOf<String, MutableList<MistakeEntry>>()
    private val events = mutableListOf<MistakeEvent>()

    fun events(): List<MistakeEvent> = events.toList()

    fun list(childId: String): List<MistakeEntry> =
        byChild[childId]?.toList().orEmpty()

    fun dueForReview(childId: String, now: Instant = Instant.now()): List<MistakeEntry> =
        list(childId).filter {
            it.countsInMistakeStats && !it.isCorrected && !it.due.isAfter(now)
        }

    fun create(
        childId: String,
        questionHash: String,
        subject: String,
        cause: ErrorCause,
        kpIds: List<String> = emptyList(),
        graphNodeIds: List<String> = emptyList(),
        disposition: Disposition = Disposition.ACTIVE,
        sessionId: String? = null,
        now: Instant = Instant.now(),
    ): MistakeEntry {
        require(Ids.isUuidV4(childId)) { "child_id required" }
        require(questionHash.isNotBlank()) { "question_hash required" }
        if (disposition == Disposition.PENDING_CHECK) {
            // Still recordable for daily brief, but excluded from stats / FSRS queue.
        }
        val entry = MistakeEntry(
            id = Ids.newId(),
            childId = childId,
            questionHash = questionHash,
            subject = subject,
            kpIds = kpIds,
            graphNodeIds = graphNodeIds,
            cause = cause,
            disposition = disposition,
            sessionId = sessionId,
            createdAt = now,
            fsrs = FsrsCard.newCard(due = now),
        )
        byChild.getOrPut(childId) { mutableListOf() }.add(entry)
        events += MistakeEvent.Created(
            eventId = Ids.newId(),
            occurredAt = now,
            childId = childId,
            mistakeId = entry.id,
            questionHash = questionHash,
            cause = cause,
        )
        return entry
    }

    /**
     * 订正 = 未看完整解、独立重做答对一次 (mistakes.md).
     */
    fun markCorrected(
        childId: String,
        mistakeId: String,
        sawFullReveal: Boolean,
        independentRedoCorrect: Boolean,
        now: Instant = Instant.now(),
    ): MistakeEntry? {
        if (sawFullReveal || !independentRedoCorrect) return null
        val list = byChild[childId] ?: return null
        val idx = list.indexOfFirst { it.id == mistakeId } 
        if (idx < 0) return null
        val updated = list[idx].copy(correctedAt = now)
        list[idx] = updated
        events += MistakeEvent.Corrected(
            eventId = Ids.newId(),
            occurredAt = now,
            childId = childId,
            mistakeId = mistakeId,
            questionHash = updated.questionHash,
        )
        return updated
    }

    fun review(
        childId: String,
        mistakeId: String,
        rating: FsrsRating,
        now: Instant = Instant.now(),
    ): MistakeEntry? {
        val list = byChild[childId] ?: return null
        val idx = list.indexOfFirst { it.id == mistakeId }
        if (idx < 0) return null
        val current = list[idx]
        if (!current.countsInMistakeStats) return null
        val result = scheduler.reviewCard(current.fsrs, rating, now)
        val updated = current.copy(
            fsrs = result.card,
            reviewCount = current.reviewCount + 1,
            lastRating = rating,
        )
        list[idx] = updated
        events += MistakeEvent.ReviewDone(
            eventId = Ids.newId(),
            occurredAt = now,
            childId = childId,
            mistakeId = mistakeId,
            questionHash = updated.questionHash,
            rating = rating,
            nextDue = updated.due,
        )
        return updated
    }

    fun updateCause(childId: String, mistakeId: String, cause: ErrorCause): MistakeEntry? {
        val list = byChild[childId] ?: return null
        val idx = list.indexOfFirst { it.id == mistakeId }
        if (idx < 0) return null
        val updated = list[idx].copy(cause = cause)
        list[idx] = updated
        return updated
    }

    fun applyMastery(
        childId: String,
        kpId: String,
        attempts: List<Mastery.FirstAttempt>,
        now: Instant = Instant.now(),
    ): Pair<Double?, MasteryLevel> {
        val related = list(childId).filter {
            it.countsInMistakeStats && kpId in it.kpIds
        }
        val retr = Mastery.meanRetrievability(related.map { it.fsrs }, scheduler, now)
        val (score, level) = Mastery.score(attempts, retr, now)
        val dueCount = dueForReview(childId, now).count { kpId in it.kpIds }
        val reason = Mastery.pushReason(level, dueCount, prereqGaps = 0)
        val list = byChild[childId] ?: return score to level
        for (i in list.indices) {
            if (kpId in list[i].kpIds) {
                list[i] = list[i].copy(
                    mastery = level,
                    masteryScore = score,
                    pushReason = reason,
                )
            }
        }
        return score to level
    }

    fun causeShare(childId: String): Map<ErrorCause, Double> {
        val rows = list(childId).filter { it.countsInMistakeStats && it.cause.inStatsDenominator }
        if (rows.isEmpty()) return emptyMap()
        return ErrorCause.entries
            .filter { it.inStatsDenominator }
            .associateWith { cause -> rows.count { it.cause == cause }.toDouble() / rows.size }
    }

    /** 举一反三：三档变式标签，不爬网. */
    fun variantPlan(mistake: MistakeEntry): List<String> =
        listOf("基础", "同级", "稍难").map { tier ->
            "变式:$tier|母题:${mistake.questionHash.take(8)}|kp:${mistake.kpIds.joinToString(",")}"
        }
}
