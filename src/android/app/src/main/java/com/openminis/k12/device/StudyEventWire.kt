package com.openminis.k12.device

import com.openminis.k12.tutoring.RevealMethod
import com.openminis.k12.tutoring.StudyEvent

/** Wire shape for POST /api/v1/device/events. Payload is hashes and enums only. */
fun StudyEvent.toPending(seq: Int = 0): PendingStudyEvent {
    val (type, payload) = when (this) {
        is StudyEvent.SessionStarted -> "session_start" to mapOf(
            "session_id" to sessionId,
            "mode" to "拍题",
        )
        is StudyEvent.StemConfirmed -> "stem_confirmed" to mapOf(
            "question_hash" to questionHash,
            "char_count" to charCount,
        )
        is StudyEvent.Attempt -> "attempt" to mapOf(
            "question_hash" to questionHash,
            "source" to source,
            "first_try" to firstTry,
            "correct" to correct,
            "hint_max" to hintMax,
        )
        is StudyEvent.HintUsed -> "hint_used" to mapOf(
            "question_hash" to questionHash,
            "level" to layer.name.lowercase(),
        )
        is StudyEvent.AnswerReveal -> "answer_reveal" to mapOf(
            "question_hash" to questionHash,
            "method" to when (method) {
                RevealMethod.PIN -> "pin"
                RevealMethod.TOTP -> "totp"
                RevealMethod.UNLOCK_CODE -> "backup_code"
                RevealMethod.REMOTE -> "remote"
            },
            "hints_before" to hintsUsed,
        )
        is StudyEvent.CalcChecked -> "calc_checked" to mapOf(
            "question_hash" to questionHash,
            "match" to match,
        )
    }
    return PendingStudyEvent(
        eventId = eventId,
        seq = seq,
        type = type,
        occurredAtMs = occurredAtMs,
        payload = payload,
    )
}
