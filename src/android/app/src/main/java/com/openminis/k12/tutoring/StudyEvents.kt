package com.openminis.k12.tutoring

/**
 * Local study events for M2 (WP-M2-6). Never carry question text or PII.
 * Upload shape later matches device-api; here we only keep local records.
 */
sealed class StudyEvent {
    abstract val eventId: String
    abstract val occurredAtMs: Long
    abstract val sessionId: String
    abstract val questionHash: String

    data class SessionStarted(
        override val eventId: String,
        override val occurredAtMs: Long,
        override val sessionId: String,
        override val questionHash: String,
        val examMode: Boolean,
    ) : StudyEvent()

    data class StemConfirmed(
        override val eventId: String,
        override val occurredAtMs: Long,
        override val sessionId: String,
        override val questionHash: String,
        val charCount: Int,
    ) : StudyEvent()

    data class Attempt(
        override val eventId: String,
        override val occurredAtMs: Long,
        override val sessionId: String,
        override val questionHash: String,
        val correct: Boolean,
        val firstTry: Boolean,
        val hintMax: Int,
        val source: String = "拍题",
    ) : StudyEvent()

    data class HintUsed(
        override val eventId: String,
        override val occurredAtMs: Long,
        override val sessionId: String,
        override val questionHash: String,
        val layer: RevealLayer,
        val hintsUsed: Int,
    ) : StudyEvent()

    data class AnswerReveal(
        override val eventId: String,
        override val occurredAtMs: Long,
        override val sessionId: String,
        override val questionHash: String,
        val method: RevealMethod,
        val hintsUsed: Int,
    ) : StudyEvent()

    data class CalcChecked(
        override val eventId: String,
        override val occurredAtMs: Long,
        override val sessionId: String,
        override val questionHash: String,
        val match: Boolean,
    ) : StudyEvent()
}

/** In-memory ring for unit tests and offline buffering. */
class StudyEventLog(private val capacity: Int = 500) {
    private val items = ArrayDeque<StudyEvent>()

    fun append(event: StudyEvent) {
        if (items.size >= capacity) items.removeFirst()
        items.addLast(event)
    }

    fun appendAll(events: List<StudyEvent>) {
        events.forEach(::append)
    }

    fun snapshot(): List<StudyEvent> = items.toList()

    fun clear() = items.clear()

    fun ofType(): Map<String, Int> =
        items.groupingBy { it::class.simpleName ?: "?" }.eachCount()
}
