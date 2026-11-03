package com.openminis.k12.tutoring

/**
 * Shell reveal layers (G1). Names match `modules/tutoring.md`:
 * confirm → guide → hint → step → reveal.
 */
enum class RevealLayer {
    CONFIRM,
    GUIDE,
    HINT,
    STEP,
    REVEAL,
    ;

    fun nextHelp(): RevealLayer = when (this) {
        CONFIRM -> CONFIRM // must confirm stem first (A1)
        GUIDE -> HINT
        HINT -> STEP
        STEP -> STEP // stay until reveal grant
        REVEAL -> REVEAL
    }

    /** How many help levels have been consumed (0–3). */
    fun hintsUsed(): Int = when (this) {
        CONFIRM -> 0
        GUIDE -> 0
        HINT -> 1
        STEP -> 2
        REVEAL -> 3
    }

    fun chainComplete(): Boolean = this == STEP || this == REVEAL
}

enum class RevealMethod {
    PIN,
    TOTP,
    UNLOCK_CODE,
    REMOTE,
}

enum class Disposition {
    ACTIVE,
    PENDING_CHECK,
    SOLVED,
    REVEALED,
    ABANDONED,
}

enum class UncertainReason {
    CALC_MISMATCH,
    STEM_UNCLEAR,
    BEYOND_SYLLABUS,
    OPEN_ENDED,
    TOOL_UNAVAILABLE,
}

/** What the model may emit at the current layer. */
data class AllowedOutput(
    val layer: RevealLayer,
    val mayShowStemOnly: Boolean,
    val mayGuide: Boolean,
    val mayHint: Boolean,
    val mayStepsWithoutFinale: Boolean,
    val mayFullSolution: Boolean,
    val promptFragment: String,
)
