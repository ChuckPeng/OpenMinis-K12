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
        val result = TutoringMachine.reduce(state, action, nowMs)
        if (action is TutoringAction.Jailbreak) {
            // G2 / A7: layer must not move.
            return result.copy(state = state)
        }
        if (!result.rejected) {
            state = result.state
            events.appendAll(result.events)
        }
        return result.copy(state = state)
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
