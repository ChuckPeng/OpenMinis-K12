package com.openminis.k12.android

import com.openminis.k12.mistakes.ErrorCause
import com.openminis.k12.mistakes.MistakeBook

/**
 * Local mistake book for the parent screen. Entries are keyed by the tablet
 * id and a question hash. No question text is stored.
 */
object K12StudyDesk {
    private var book = MistakeBook()

    /** Read during composition so the parent screen refreshes after a new mistake. */
    val revision = androidx.compose.runtime.mutableStateOf(0)

    @Volatile
    private var linked = false

    fun noteWrong(childId: String, questionHash: String) {
        if (childId.isBlank() || questionHash.isBlank()) return
        book.create(
            childId = childId,
            questionHash = questionHash,
            subject = "math",
            cause = ErrorCause.CONCEPT,
            kpIds = listOf("kp-add"),
            graphNodeIds = listOf("kp-add"),
        )
        linked = true
        revision.value = revision.value + 1
    }

    fun dueCount(childId: String): Int =
        if (childId.isBlank()) 0 else book.dueForReview(childId).size

    fun linkedKnowledge(): Boolean = linked

    fun reset() {
        book = MistakeBook()
        linked = false
        revision.value = revision.value + 1
    }
}
