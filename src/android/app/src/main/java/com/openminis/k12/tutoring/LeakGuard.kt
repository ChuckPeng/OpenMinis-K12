package com.openminis.k12.tutoring

/**
 * Pre-reveal leak check (test-plan 泄题检查). Blocks final numbers, choice
 * letters, or full formulae that match the reference answer.
 */
data class ReferenceAnswer(
    val finalNumber: String? = null,
    val choice: String? = null,
    val formula: String? = null,
)

sealed class LeakCheck {
    data object Clean : LeakCheck()
    data class Rewrite(val reason: String) : LeakCheck()
    data class Blocked(val userMessage: String = TutoringMachine.FIXED_LEAK_BLOCK) : LeakCheck()
}

object LeakGuard {
    fun inspect(
        output: String,
        reference: ReferenceAnswer,
        layer: RevealLayer,
        alreadyRewritten: Boolean = false,
    ): LeakCheck {
        if (layer == RevealLayer.REVEAL) return LeakCheck.Clean
        val normalized = normalize(output)
        val hit = mutableListOf<String>()
        reference.finalNumber?.let { n ->
            val token = normalize(n)
            if (token.isNotEmpty() && containsNumberToken(normalized, token)) hit += "number"
        }
        reference.choice?.let { c ->
            val letter = c.trim().uppercase()
            if (letter.length == 1 && letter[0] in 'A'..'D') {
                val patterns = listOf(
                    Regex("""(?i)\b答案\s*[:：]?\s*$letter\b"""),
                    Regex("""(?i)\b选择\s*$letter\b"""),
                    Regex("""(?i)\boption\s*$letter\b"""),
                    Regex("""(?i)\b$letter\s*[.、)]"""),
                )
                if (patterns.any { it.containsMatchIn(output) }) hit += "choice"
            }
        }
        reference.formula?.let { f ->
            val token = normalize(f)
            if (token.length >= 3 && normalized.contains(token)) hit += "formula"
        }
        if (hit.isEmpty()) return LeakCheck.Clean
        return if (!alreadyRewritten) {
            LeakCheck.Rewrite(hit.joinToString(","))
        } else {
            LeakCheck.Blocked()
        }
    }

    private fun normalize(raw: String): String =
        raw.lowercase()
            .replace(Regex("""\s+"""), "")
            .replace("×", "*")
            .replace("÷", "/")
            .replace("＝", "=")

    private fun containsNumberToken(haystack: String, number: String): Boolean {
        if (number.isEmpty()) return false
        // Avoid matching "12" inside "123".
        val escaped = Regex.escape(number)
        return Regex("""(?<![\d.])$escaped(?![\d.])""").containsMatchIn(haystack)
    }
}
