package com.openminis.k12.tutoring

import com.openminis.k12.Ids
import kotlin.math.abs

/**
 * Calculation tool boundary (A4). Symja is the preferred engine when present;
 * CI and devices without it use [ArithmeticCalcEngine]. Mismatch → uncertain.
 */
sealed class CalcResult {
    data class Ok(val value: String) : CalcResult()
    data class Err(val message: String) : CalcResult()
}

fun interface CalcEngine {
    fun evaluate(expression: String): CalcResult
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
    /**
     * Prefer Symja when the class is on the classpath (optional dependency).
     * Falls back to [ArithmeticCalcEngine] — documented decision for M2 CI.
     */
    fun preferred(): CalcEngine {
        val symja = runCatching {
            Class.forName("org.matheclipse.core.eval.ExprEvaluator")
            SymjaBridge
        }.getOrNull()
        return symja ?: ArithmeticCalcEngine
    }
}

/** Reflective Symja bridge so the APK builds without bundling Symja jars yet. */
object SymjaBridge : CalcEngine {
    override fun evaluate(expression: String): CalcResult {
        return try {
            val clazz = Class.forName("org.matheclipse.core.eval.ExprEvaluator")
            val instance = clazz.getDeclaredConstructor().newInstance()
            val eval = clazz.methods.first { it.name == "evaluate" && it.parameterTypes.size == 1 }
            val result = eval.invoke(instance, expression) ?: return CalcResult.Err("null")
            CalcResult.Ok(result.toString())
        } catch (e: Exception) {
            CalcResult.Err(e.message ?: "symja")
        }
    }
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
        val match = when (tool) {
            is CalcResult.Ok -> numbersEqual(modelClaimed, tool.value)
            is CalcResult.Err -> false
        }
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
        val reason = if (tool is CalcResult.Err) {
            UncertainReason.TOOL_UNAVAILABLE
        } else {
            UncertainReason.CALC_MISMATCH
        }
        return session to TutoringMachine.reduce(session, TutoringAction.MarkUncertain(reason), nowMs)
    }

    fun numbersEqual(a: String, b: String): Boolean {
        val da = a.trim().toDoubleOrNull()
        val db = b.trim().toDoubleOrNull()
        if (da != null && db != null) return abs(da - db) < 1e-9
        return a.trim() == b.trim()
    }
}
