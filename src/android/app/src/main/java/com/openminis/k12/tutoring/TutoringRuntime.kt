package com.openminis.k12.tutoring

/**
 * Process-wide active tutoring session for H-09 prompt injection.
 * Upstream unit tests never start a session, so the fragment stays empty.
 */
object TutoringRuntime {
    @Volatile
    private var state: TutoringState = TutoringState()

    val events = StudyEventLog()

    fun snapshot(): TutoringState = state

    fun reset() {
        state = TutoringState()
        events.clear()
    }

    fun dispatch(action: TutoringAction, nowMs: Long = System.currentTimeMillis()): ReduceResult {
        // A4: load Symja off this thread before the first check. Start is the
        // session boundary; the engine itself refuses the Android main thread.
        if (action is TutoringAction.Start) SymjaCalcEngine.warmUpAsync()
        val result = TutoringMachine.reduce(state, action, nowMs)
        if (action is TutoringAction.Jailbreak) {
            // G2 / A7: layer must not move.
            return result.copy(state = state)
        }
        if (!result.rejected) {
            state = result.state
            events.appendAll(result.events)
            if (action is TutoringAction.SubmitAttempt && !action.correct) {
                val child = com.openminis.k12.android.K12Runtime.snapshot().profile.childId.orEmpty()
                runCatching {
                    com.openminis.k12.android.K12StudyDesk.noteWrong(child, state.questionHash)
                }
            }
        }
        return result.copy(state = state)
    }

    /**
     * A new child message opens a tutoring session. The text itself is not
     * stored; events keep a hash and a character count.
     */
    fun noteQuestion(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val snap = state
        if (snap.sessionId.isNotBlank() && snap.stemConfirmed) return
        val hash = sha256(trimmed).take(32)
        dispatch(TutoringAction.Start(questionHash = hash, stemCharCount = trimmed.length))
        dispatch(TutoringAction.ConfirmStem(trimmed.length))
    }

    fun noteCalc(match: Boolean) {
        val snap = state
        if (snap.sessionId.isBlank()) return
        events.append(
            StudyEvent.CalcChecked(
                eventId = com.openminis.k12.Ids.newId(),
                occurredAtMs = System.currentTimeMillis(),
                sessionId = snap.sessionId,
                questionHash = snap.questionHash,
                match = match,
            ),
        )
    }

    private fun sha256(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun promptSuffix(): String {
        val snap = state
        if (snap.sessionId.isBlank()) return ""
        return "\n\n" + TutoringPrompt.fragment(snap)
    }

    fun filterModelOutput(
        output: String,
        reference: ReferenceAnswer,
        alreadyRewritten: Boolean = false,
    ): LeakCheck = LeakGuard.inspect(output, reference, state.layer, alreadyRewritten)
}
