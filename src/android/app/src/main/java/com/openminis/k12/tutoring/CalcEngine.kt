package com.openminis.k12.tutoring

import com.openminis.k12.Ids
import kotlin.math.abs

/**
 * Calculation tool boundary (A4). [CalcEngines.preferred] is the on-device
 * Symja engine. [ArithmeticCalcEngine] remains for numeric smoke tests.
 * Mismatch or an unavailable tool → uncertain / `pending_check`.
 */
sealed class CalcResult {
    data class Ok(val value: String) : CalcResult()
    data class Err(val message: String) : CalcResult()
}

/** Whether two expressions are the same under the CAS, or the tool could not say. */
sealed class CalcEquivalence {
    data class Same(val canonical: String) : CalcEquivalence()
    data object Different : CalcEquivalence()
    data class Unavailable(val message: String) : CalcEquivalence()
}

fun interface CalcEngine {
    fun evaluate(expression: String): CalcResult
}

/**
 * Algebraic checks A4 requires: simplification, factoring, expression
 * equivalence, and univariate solution sets. Swapping the library means a
 * new implementation of this interface, not a new call shape.
 */
interface AlgebraCas : CalcEngine {
    fun simplify(expression: String): CalcResult
    fun factor(expression: String): CalcResult
    fun equivalent(left: String, right: String): CalcEquivalence
    fun solve(equation: String, variable: String): CalcResult

    /** Order-insensitive match of [student] against the roots of [equation]. */
    fun solutionsMatch(student: String, equation: String, variable: String): CalcEquivalence
}

/** Minimal + - * / and parentheses evaluator for smoke tests and offline A4. */
object ArithmeticCalcEngine : CalcEngine {
    override fun evaluate(expression: String): CalcResult {
        val cleaned = expression.trim().replace(" ", "")
        if (cleaned.isEmpty()) return CalcResult.Err("empty")
        return try {
            CalcResult.Ok(format(Parser(cleaned).parse()))
        } catch (e: Exception) {
            CalcResult.Err(e.message ?: "parse")
        }
    }

    private fun format(v: Double): String =
        if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

    private class Parser(private val expr: String) {
        private var i = 0

        fun parse(): Double {
            val value = parseExpr()
            if (i != expr.length) error("trailing")
            return value
        }

        private fun peek(): Char? = expr.getOrNull(i)
        private fun next(): Char = expr[i++]

        private fun parseExpr(): Double {
            var v = parseTerm()
            while (true) {
                when (peek()) {
                    '+' -> { next(); v += parseTerm() }
                    '-' -> { next(); v -= parseTerm() }
                    else -> return v
                }
            }
        }

        private fun parseTerm(): Double {
            var v = parseFactor()
            while (true) {
                when (peek()) {
                    '*' -> { next(); v *= parseFactor() }
                    '/' -> { next(); v /= parseFactor() }
                    else -> return v
                }
            }
        }

        private fun parseFactor(): Double {
            when (peek()) {
                '+' -> { next(); return parseFactor() }
                '-' -> { next(); return -parseFactor() }
                '(' -> {
                    next()
                    val v = parseExpr()
                    if (peek() != ')') error("expected )")
                    next()
                    return v
                }
            }
            val start = i
            while (peek()?.isDigit() == true || peek() == '.') next()
            if (start == i) error("expected number")
            return expr.substring(start, i).toDouble()
        }
    }
}

object CalcEngines {
    /** On-device Symja. Callers must not block the Android main thread. */
    fun preferred(): AlgebraCas = SymjaCalcEngine
}

object CalcCheck {
    fun compareModelToTool(
        modelClaimed: String,
        expression: String,
        engine: CalcEngine = CalcEngines.preferred(),
        session: TutoringState,
        eventLog: StudyEventLog? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<TutoringState, ReduceResult?> {
        val tool = engine.evaluate(expression)
        val comparison = when (tool) {
            is CalcResult.Err -> ClaimComparison.UNAVAILABLE
            is CalcResult.Ok -> compareClaim(modelClaimed, tool.value, engine)
        }
        return finish(comparison, session, eventLog, nowMs)
    }

    /**
     * Student expression against a reference form (expanded, factored, or
     * simplified). Equivalent forms match; a different value is a mismatch.
     */
    fun verifyStudentExpression(
        student: String,
        reference: String,
        engine: CalcEngine = CalcEngines.preferred(),
        session: TutoringState,
        eventLog: StudyEventLog? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<TutoringState, ReduceResult?> {
        val comparison = when (engine) {
            is AlgebraCas -> engine.equivalent(student, reference).toComparison()
            else -> ClaimComparison.UNAVAILABLE
        }
        return finish(comparison, session, eventLog, nowMs)
    }

    /** Student roots against [equation] in [variable]. Order does not matter. */
    fun verifyEquationSolutions(
        student: String,
        equation: String,
        variable: String,
        engine: CalcEngine = CalcEngines.preferred(),
        session: TutoringState,
        eventLog: StudyEventLog? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<TutoringState, ReduceResult?> {
        val comparison = when (engine) {
            is AlgebraCas -> engine.solutionsMatch(student, equation, variable).toComparison()
            else -> ClaimComparison.UNAVAILABLE
        }
        return finish(comparison, session, eventLog, nowMs)
    }

    fun numbersEqual(a: String, b: String): Boolean {
        val da = a.trim().toDoubleOrNull()
        val db = b.trim().toDoubleOrNull()
        if (da != null && db != null) return abs(da - db) < 1e-9
        return a.trim() == b.trim()
    }

    private fun compareClaim(claimed: String, toolValue: String, engine: CalcEngine): ClaimComparison {
        if (numbersEqual(claimed, toolValue)) return ClaimComparison.MATCH
        val cas = engine as? AlgebraCas ?: return ClaimComparison.DIFFER
        return cas.equivalent(claimed, toolValue).toComparison()
    }

    private fun finish(
        comparison: ClaimComparison,
        session: TutoringState,
        eventLog: StudyEventLog?,
        nowMs: Long,
    ): Pair<TutoringState, ReduceResult?> {
        val match = comparison == ClaimComparison.MATCH
        eventLog?.append(
            StudyEvent.CalcChecked(
                eventId = Ids.newId(),
                occurredAtMs = nowMs,
                sessionId = session.sessionId,
                questionHash = session.questionHash,
                match = match,
            ),
        )
        if (match) return session to null
        val reason = if (comparison == ClaimComparison.DIFFER) {
            UncertainReason.CALC_MISMATCH
        } else {
            UncertainReason.TOOL_UNAVAILABLE
        }
        return session to TutoringMachine.reduce(session, TutoringAction.MarkUncertain(reason), nowMs)
    }
}

private enum class ClaimComparison { MATCH, DIFFER, UNAVAILABLE }

private fun CalcEquivalence.toComparison(): ClaimComparison = when (this) {
    is CalcEquivalence.Same -> ClaimComparison.MATCH
    CalcEquivalence.Different -> ClaimComparison.DIFFER
    is CalcEquivalence.Unavailable -> ClaimComparison.UNAVAILABLE
}
