package com.openminis.k12.tutoring

/**
 * H-09 fragment: current layer + prohibitions only. Teaching rules stay in skills.
 */
object TutoringPrompt {
    fun fragment(state: TutoringState): String {
        if (state.sessionId.isBlank()) return ""
        val layer = state.layer.name.lowercase()
        val bans = buildList {
            if (!state.stemConfirmed) {
                add("Do not teach or solve. Only help confirm the stem.")
            }
            if (!state.revealGranted) {
                add("Do not output the final numeric answer, choice letter, or full closed-form solution.")
            }
            when (state.layer) {
                RevealLayer.CONFIRM -> add("Show recognized stem only; allow edits/retake.")
                RevealLayer.GUIDE -> add("Restate meaning and ask for the child's approach. No steps.")
                RevealLayer.HINT -> add("Give the knowledge point or first-move direction only.")
                RevealLayer.STEP -> add("Show key steps but leave the last move to the child.")
                RevealLayer.REVEAL -> add("Full solution is allowed for this question once.")
            }
            if (state.disposition == Disposition.PENDING_CHECK) {
                add("Say 不确定 and suggest 问老师. Do not invent a confident final answer.")
            }
        }
        return buildString {
            appendLine("K12 tutoring shell:")
            appendLine("- layer: $layer")
            appendLine("- stem_confirmed: ${state.stemConfirmed}")
            appendLine("- hints_used: ${state.layer.hintsUsed()}")
            appendLine("- approach_variant: ${state.approachIndex} (0基础/1同级/2稍难)")
            appendLine("- exam_mode: ${state.examMode}")
            appendLine("- reveal_granted: ${state.revealGranted}")
            appendLine("Prohibitions:")
            bans.forEach { append("- ").appendLine(it) }
        }.trimEnd()
    }
}
