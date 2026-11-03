package com.openminis.k12

import com.openminis.k12.tutoring.AlgebraCas
import com.openminis.k12.tutoring.ArithmeticCalcEngine
import com.openminis.k12.tutoring.CalcCheck
import com.openminis.k12.tutoring.CalcEngines
import com.openminis.k12.tutoring.CalcEquivalence
import com.openminis.k12.tutoring.CalcResult
import com.openminis.k12.tutoring.Disposition
import com.openminis.k12.tutoring.StudyEvent
import com.openminis.k12.tutoring.StudyEventLog
import com.openminis.k12.tutoring.SymjaCalcEngine
import com.openminis.k12.tutoring.TutoringAction
import com.openminis.k12.tutoring.TutoringMachine
import com.openminis.k12.tutoring.TutoringRuntime
import com.openminis.k12.tutoring.TutoringState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BLOCK-CAS-1 / tutoring A4. Synthetic expressions only.
 * Symja decides equivalence, simplification, factoring, and equation roots.
 */
class K12CasA4AcceptanceTest {
    private val cas: AlgebraCas = CalcEngines.preferred()

    @After
    fun resetBudgets() {
        SymjaCalcEngine.stallForTestsMs = 0
        SymjaCalcEngine.timeoutForTestsMs = SymjaCalcEngine.DEFAULT_TIMEOUT_MS
    }

    @Test
    fun preferredEngineIsSymja() {
        assertTrue(cas is SymjaCalcEngine)
        assertEquals(CalcResult.Ok("7"), cas.evaluate("3+4"))
        assertEquals(CalcResult.Ok("0.5"), cas.evaluate("1/2"))
        assertEquals(CalcResult.Ok("2"), cas.evaluate("Sqrt(4)"))
        assertEquals(CalcResult.Ok("2"), cas.evaluate("sqrt(4)"))
        assertEquals(CalcResult.Ok("7"), ArithmeticCalcEngine.evaluate("3+4"))
    }

    @Test
    fun equivalentFormsMatchAndWrongFormsDoNot() {
        assertTrue(cas.equivalent("(x+1)^2", "x^2+2*x+1") is CalcEquivalence.Same)
        assertTrue(cas.equivalent("2(x+1)", "2*x+2") is CalcEquivalence.Same)
        assertTrue(cas.equivalent("2x", "x*2") is CalcEquivalence.Same)
        assertTrue(cas.equivalent("(x^2-1)/(x-1)", "x+1") is CalcEquivalence.Same)
        assertTrue(cas.equivalent("(x+1)^2", "x^2+1") is CalcEquivalence.Different)
        assertTrue(cas.equivalent("x^2-1", "(x-1)*(x+1)") is CalcEquivalence.Same)

        val session = startConfirmed("qh-equiv")
        val log = StudyEventLog()
        val (_, wrong) = CalcCheck.verifyStudentExpression(
            student = "x^2+1",
            reference = "(x+1)^2",
            session = session,
            eventLog = log,
        )
        assertEquals(Disposition.PENDING_CHECK, wrong!!.state.disposition)
        assertTrue(wrong.message!!.contains("不确定"))
        assertTrue(log.snapshot().any { it is StudyEvent.CalcChecked && !it.match })

        val (_, ok) = CalcCheck.verifyStudentExpression(
            student = "x^2+2*x+1",
            reference = "(x+1)^2",
            session = session,
            eventLog = StudyEventLog(),
        )
        assertTrue(ok == null)
    }

    @Test
    fun simplifyAndFactor() {
        val simplified = cas.simplify("(x^2-1)/(x-1)")
        assertTrue(simplified is CalcResult.Ok)
        assertTrue(
            cas.equivalent((simplified as CalcResult.Ok).value, "x+1") is CalcEquivalence.Same,
        )
        val factored = cas.factor("x^2-1")
        assertTrue(factored is CalcResult.Ok)
        assertTrue((factored as CalcResult.Ok).value.contains("*"))
        assertTrue(cas.equivalent(factored.value, "(x-1)*(x+1)") is CalcEquivalence.Same)
    }

    @Test
    fun equationSolutionsIgnoreOrder() {
        assertEquals(CalcResult.Ok("{2, 3}"), cas.solve("x^2-5*x+6==0", "x"))
        assertTrue(cas.solutionsMatch("3, 2", "x^2-5*x+6==0", "x") is CalcEquivalence.Same)
        assertTrue(cas.solutionsMatch("{2,3}", "x^2-5*x+6==0", "x") is CalcEquivalence.Same)
        assertTrue(cas.solutionsMatch("2", "x^2-5*x+6==0", "x") is CalcEquivalence.Different)
        assertTrue(cas.solutionsMatch("1, 6", "x^2-5*x+6==0", "x") is CalcEquivalence.Different)
        assertTrue(cas.solutionsMatch("1", "(x-1)^2==0", "x") is CalcEquivalence.Same)
        assertTrue(cas.solutionsMatch("{}", "x+1==x", "x") is CalcEquivalence.Same)
        assertTrue(cas.solutionsMatch("0", "x+1==x", "x") is CalcEquivalence.Different)
        assertTrue(cas.solutionsMatch("-I, I", "x^2+1==0", "x") is CalcEquivalence.Same)

        val session = startConfirmed("qh-solve")
        val (_, miss) = CalcCheck.verifyEquationSolutions(
            student = "1, 6",
            equation = "x^2-5*x+6==0",
            variable = "x",
            session = session,
        )
        assertEquals(Disposition.PENDING_CHECK, miss!!.state.disposition)
        val (_, hit) = CalcCheck.verifyEquationSolutions(
            student = "2, 3",
            equation = "x^2-5*x+6==0",
            variable = "x",
            session = session,
        )
        assertTrue(hit == null)
    }

    @Test
    fun modelClaimUsesCasNotOnlyDecimalText() {
        val session = startConfirmed("qh-model")
        val (_, miss) = CalcCheck.compareModelToTool(
            modelClaimed = "99",
            expression = "3+4",
            session = session,
        )
        assertEquals(Disposition.PENDING_CHECK, miss!!.state.disposition)
        val (_, hit) = CalcCheck.compareModelToTool(
            modelClaimed = "14/2",
            expression = "3+4",
            session = session,
        )
        assertTrue(hit == null)
        val (_, symbolic) = CalcCheck.compareModelToTool(
            modelClaimed = "2*(x+1)",
            expression = "2x+2",
            session = session,
        )
        assertTrue(symbolic == null)
    }

    @Test
    fun rejectsEmptyProgrammingAndNonFiniteInput() {
        assertEquals("rejected", err(cas.evaluate("")))
        assertEquals("rejected", err(cas.evaluate("   ")))
        assertEquals("rejected", err(cas.evaluate("1;2")))
        assertEquals("rejected", err(cas.evaluate("While(True,1)")))
        assertEquals("rejected", err(cas.evaluate("a=1")))
        assertEquals("rejected", err(cas.evaluate("Import(\"a\")")))
        assertEquals("non-finite", err(cas.evaluate("1/0")))
        assertEquals("non-finite", err(cas.evaluate("0/0")))
        assertTrue(cas.equivalent("", "1") is CalcEquivalence.Unavailable)
    }

    @Test
    fun rejectsPathologicalInputQuickly() {
        val huge = "x+".repeat(400)
        val deep = "(".repeat(40) + "x" + ")".repeat(40)
        val tower = (1..40).joinToString("^") { "x" }
        for (input in listOf(huge, deep, tower)) {
            val started = System.nanoTime()
            val result = cas.evaluate(input)
            val ms = (System.nanoTime() - started) / 1_000_000
            assertEquals(input.take(12), "rejected", err(result))
            assertTrue("guard took ${ms}ms for ${input.length} chars", ms < 3_000)
        }
    }

    @Test
    fun timeoutDiscardsTheEngineAndTheNextCheckStillWorks() {
        assertEquals(CalcResult.Ok("2"), cas.evaluate("1+1"))
        SymjaCalcEngine.timeoutForTestsMs = 200
        SymjaCalcEngine.stallForTestsMs = 5_000
        val started = System.nanoTime()
        val timed = cas.evaluate("1+1")
        val ms = (System.nanoTime() - started) / 1_000_000
        SymjaCalcEngine.stallForTestsMs = 0
        SymjaCalcEngine.timeoutForTestsMs = SymjaCalcEngine.DEFAULT_TIMEOUT_MS
        assertEquals("timeout", err(timed))
        assertTrue("timeout returned in ${ms}ms", ms < 3_000)
        assertEquals(CalcResult.Ok("2"), cas.evaluate("1+1"))
    }

    @Test
    fun sessionStartWarmsCasOffTheCaller() {
        TutoringRuntime.reset()
        TutoringRuntime.dispatch(TutoringAction.Start(questionHash = "qh-warm", stemCharCount = 4))
        assertEquals(CalcResult.Ok("4"), cas.evaluate("2*2"))
    }

    @Test
    fun releaseMetadataMarksBlockerResolved() {
        assertEquals("1.0.0-rc.1", Version.NAME)
        assertEquals(1_000_007, Version.CODE)
        assertTrue(Version.matchesCodeFormula())
        val changelog = repoFile("CHANGELOG.md").readText()
        assertTrue(changelog.contains("## [1.0.0-rc.1]"))
        assertTrue(changelog.contains("BLOCK-CAS-1"))
        val roadmap = repoFile("docs/k12/roadmap.md").readText()
        assertTrue(roadmap.contains("BLOCK-CAS-1"))
        assertTrue(roadmap.contains("已解决"))
        val gradle = repoFile("src/android/app/build.gradle.kts").readText()
        assertTrue(gradle.contains("org.matheclipse:matheclipse-core:3.2.0"))
        val proguard = repoFile("src/android/app/proguard-rules.pro").readText()
        assertTrue(proguard.contains("org.matheclipse.**"))
        val licenses = repoFile("THIRD_PARTY_LICENSES.md").readText()
        assertTrue(licenses.contains("matheclipse-core"))
        assertTrue(licenses.contains("LGPL-3.0"))
        val workflow = repoFile(".github/workflows/android.yml").readText()
        assertTrue(workflow.contains("K12CasA4AcceptanceTest"))
    }

    private fun err(result: CalcResult): String = (result as CalcResult.Err).message

    private fun startConfirmed(hash: String): TutoringState {
        var s = TutoringMachine.reduce(
            TutoringState(),
            TutoringAction.Start(questionHash = hash, stemCharCount = 8),
        ).state
        s = TutoringMachine.reduce(s, TutoringAction.ConfirmStem(8)).state
        return s
    }

    private fun repoFile(name: String): File = listOf(
        File(name),
        File("../../$name"),
        File("../../../$name"),
    ).first { it.exists() }
}
