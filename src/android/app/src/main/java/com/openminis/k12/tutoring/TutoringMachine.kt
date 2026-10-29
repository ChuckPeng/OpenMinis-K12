package com.openminis.k12.tutoring

import com.openminis.k12.Ids

/**
 * Table-driven reveal state machine (WP-M2-1…4, G1/G2).
 * Jailbreak / social-engineering actions never mutate the layer (A7, G2).
 */
data class TutoringState(
    val sessionId: String = "",
    val questionHash: String = "",
    val stemCharCount: Int = 0,
    val stemConfirmed: Boolean = false,
    val layer: RevealLayer = RevealLayer.CONFIRM,
    val hasAttempt: Boolean = false,
    val attemptCount: Int = 0,
    val lastAttemptCorrect: Boolean? = null,
    val examMode: Boolean = false,
    val revealGranted: Boolean = false,
    val revealMethod: RevealMethod? = null,
    val stuckCount: Int = 0,
    /** 0=基础, 1=同级, 2=稍难 — A6 三档变式 / 卡住换讲法. */
    val approachIndex: Int = 0,
    val disposition: Disposition = Disposition.ACTIVE,
    /** High-grade policy; default off per tutoring.md. */
    val autoRevealAfterChain: Boolean = false,
)

sealed class TutoringAction {
    data class Start(
        val questionHash: String,
        val stemCharCount: Int = 0,
        val examMode: Boolean = false,
        val sessionId: String = Ids.newId(),
    ) : TutoringAction()

    data class ConfirmStem(val charCount: Int) : TutoringAction()
    data object DenyStem : TutoringAction()
    data object AskHelp : TutoringAction()
    data class SubmitAttempt(val correct: Boolean) : TutoringAction()
    data object RequestReveal : TutoringAction()
    data class GrantReveal(val method: RevealMethod) : TutoringAction()
    data object Jailbreak : TutoringAction()
    data class MarkUncertain(val reason: UncertainReason) : TutoringAction()
    data object CycleApproach : TutoringAction()
    data object EndWithVariants : TutoringAction()
}

data class ReduceResult(
    val state: TutoringState,
    val events: List<StudyEvent> = emptyList(),
    val rejected: Boolean = false,
    val needsParentAuth: Boolean = false,
    val message: String? = null,
)

object TutoringMachine {
    const val VARIANT_COUNT = 3
    const val FIXED_LEAK_BLOCK =
        "这一步先停在这里。请按上面的提示自己写下一步。"

    fun reduce(
        state: TutoringState,
        action: TutoringAction,
        nowMs: Long = System.currentTimeMillis(),
    ): ReduceResult = when (action) {
        is TutoringAction.Start -> start(action, nowMs)
        is TutoringAction.ConfirmStem -> confirmStem(state, action, nowMs)
        TutoringAction.DenyStem ->
            ReduceResult(state.copy(layer = RevealLayer.CONFIRM, stemConfirmed = false))
        TutoringAction.AskHelp -> askHelp(state, nowMs)
        is TutoringAction.SubmitAttempt -> submitAttempt(state, action, nowMs)
        TutoringAction.RequestReveal -> requestReveal(state)
        is TutoringAction.GrantReveal -> grantReveal(state, action.method, nowMs)
        TutoringAction.Jailbreak ->
            ReduceResult(state, rejected = true, message = "jailbreak ignored")
        is TutoringAction.MarkUncertain ->
            ReduceResult(
                state.copy(disposition = Disposition.PENDING_CHECK),
                message = uncertainMessage(action.reason),
            )
        TutoringAction.CycleApproach ->
            ReduceResult(state.copy(approachIndex = (state.approachIndex + 1) % VARIANT_COUNT))
        TutoringAction.EndWithVariants ->
            ReduceResult(state.copy(approachIndex = 0), message = "variants:基础,同级,稍难")
    }

    private fun start(action: TutoringAction.Start, nowMs: Long): ReduceResult {
        val next = TutoringState(
            sessionId = action.sessionId,
            questionHash = action.questionHash,
            stemCharCount = action.stemCharCount,
            examMode = action.examMode,
        )
        return ReduceResult(
            next,
            listOf(
                StudyEvent.SessionStarted(
                    eventId = Ids.newId(),
                    occurredAtMs = nowMs,
                    sessionId = next.sessionId,
                    questionHash = next.questionHash,
                    examMode = next.examMode,
                ),
            ),
        )
    }

    private fun confirmStem(
        state: TutoringState,
        action: TutoringAction.ConfirmStem,
        nowMs: Long,
    ): ReduceResult {
        if (state.sessionId.isBlank()) {
            return ReduceResult(state, rejected = true, message = "no session")
        }
        val next = state.copy(
            stemConfirmed = true,
            stemCharCount = action.charCount,
            layer = RevealLayer.GUIDE,
        )
        return ReduceResult(
            next,
            listOf(
                StudyEvent.StemConfirmed(
                    eventId = Ids.newId(),
                    occurredAtMs = nowMs,
                    sessionId = next.sessionId,
                    questionHash = next.questionHash,
                    charCount = action.charCount,
                ),
            ),
        )
    }

    private fun submitAttempt(
        state: TutoringState,
        action: TutoringAction.SubmitAttempt,
        nowMs: Long,
    ): ReduceResult {
        if (!state.stemConfirmed) {
            return ReduceResult(state, rejected = true, message = "confirm stem first")
        }
        val firstTry = !state.hasAttempt
        val nextLayer = when {
            action.correct -> state.layer
            state.layer == RevealLayer.GUIDE -> RevealLayer.HINT
            state.layer == RevealLayer.HINT -> RevealLayer.STEP
            else -> state.layer
        }
        val stuck = if (!action.correct) state.stuckCount + 1 else 0
        val approach = if (stuck >= 2) (state.approachIndex + 1) % VARIANT_COUNT else state.approachIndex
        val next = state.copy(
            hasAttempt = true,
            attemptCount = state.attemptCount + 1,
            lastAttemptCorrect = action.correct,
            layer = nextLayer,
            stuckCount = stuck,
            approachIndex = approach,
            disposition = if (action.correct) Disposition.SOLVED else state.disposition,
        )
        val events = mutableListOf<StudyEvent>(
            StudyEvent.Attempt(
                eventId = Ids.newId(),
                occurredAtMs = nowMs,
                sessionId = next.sessionId,
                questionHash = next.questionHash,
                correct = action.correct,
                firstTry = firstTry,
                hintMax = next.layer.hintsUsed(),
            ),
        )
        if (nextLayer != state.layer && nextLayer != RevealLayer.REVEAL) {
            events += StudyEvent.HintUsed(
                eventId = Ids.newId(),
                occurredAtMs = nowMs,
                sessionId = next.sessionId,
                questionHash = next.questionHash,
                layer = nextLayer,
                hintsUsed = nextLayer.hintsUsed(),
            )
        }
        return ReduceResult(next, events)
    }

    fun allowedOutput(state: TutoringState): AllowedOutput {
        val layer = state.layer
        val full = layer == RevealLayer.REVEAL && state.revealGranted
        return AllowedOutput(
            layer = layer,
            mayShowStemOnly = layer == RevealLayer.CONFIRM || state.stemConfirmed,
            mayGuide = layer.ordinal >= RevealLayer.GUIDE.ordinal && state.stemConfirmed,
            mayHint = layer.ordinal >= RevealLayer.HINT.ordinal,
            mayStepsWithoutFinale = layer.ordinal >= RevealLayer.STEP.ordinal,
            mayFullSolution = full,
            promptFragment = TutoringPrompt.fragment(state),
        )
    }

    fun canApplyForReveal(state: TutoringState): Boolean =
        state.stemConfirmed &&
            state.hasAttempt &&
            state.lastAttemptCorrect != true &&
            state.layer.chainComplete() &&
            !state.revealGranted

    private fun askHelp(state: TutoringState, nowMs: Long): ReduceResult {
        if (!state.stemConfirmed) {
            return ReduceResult(state, rejected = true, message = "confirm stem first")
        }
        if (state.layer == RevealLayer.REVEAL) return ReduceResult(state)
        val nextLayer = when (state.layer) {
            RevealLayer.GUIDE -> RevealLayer.HINT
            RevealLayer.HINT -> RevealLayer.STEP
            RevealLayer.STEP -> RevealLayer.STEP
            else -> state.layer
        }
        val next = state.copy(layer = nextLayer, stuckCount = state.stuckCount + 1)
        val events = if (nextLayer != state.layer) {
            listOf(
                StudyEvent.HintUsed(
                    eventId = Ids.newId(),
                    occurredAtMs = nowMs,
                    sessionId = next.sessionId,
                    questionHash = next.questionHash,
                    layer = nextLayer,
                    hintsUsed = nextLayer.hintsUsed(),
                ),
            )
        } else {
            emptyList()
        }
        return ReduceResult(next, events)
    }

    private fun requestReveal(state: TutoringState): ReduceResult {
        if (!canApplyForReveal(state)) {
            return ReduceResult(
                state,
                rejected = true,
                message = "hint chain incomplete or no attempt",
            )
        }
        if (state.examMode && !state.revealGranted) {
            return ReduceResult(state, needsParentAuth = true, message = "exam mode needs parent")
        }
        if (state.autoRevealAfterChain) {
            return grantReveal(state, RevealMethod.PIN, System.currentTimeMillis())
        }
        // M2: show PIN dialog (remote later in M5b).
        return ReduceResult(state, needsParentAuth = true, message = "pin required")
    }

    private fun grantReveal(
        state: TutoringState,
        method: RevealMethod,
        nowMs: Long,
    ): ReduceResult {
        if (!state.stemConfirmed || !state.layer.chainComplete()) {
            return ReduceResult(state, rejected = true, message = "cannot reveal yet")
        }
        val next = state.copy(
            layer = RevealLayer.REVEAL,
            revealGranted = true,
            revealMethod = method,
            disposition = Disposition.REVEALED,
        )
        return ReduceResult(
            next,
            listOf(
                StudyEvent.AnswerReveal(
                    eventId = Ids.newId(),
                    occurredAtMs = nowMs,
                    sessionId = next.sessionId,
                    questionHash = next.questionHash,
                    method = method,
                    hintsUsed = next.layer.hintsUsed(),
                ),
            ),
        )
    }

    private fun uncertainMessage(reason: UncertainReason): String = when (reason) {
        UncertainReason.CALC_MISMATCH,
        UncertainReason.TOOL_UNAVAILABLE,
        UncertainReason.STEM_UNCLEAR,
        UncertainReason.OPEN_ENDED,
        UncertainReason.BEYOND_SYLLABUS,
        -> "不确定，建议问老师。（${reason.name.lowercase()}）"
    }
}
