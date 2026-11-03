package com.openminis.k12

import com.openminis.k12.fsrs.FsrsCard
import com.openminis.k12.fsrs.FsrsRating
import com.openminis.k12.fsrs.FsrsScheduler
import com.openminis.k12.fsrs.FsrsState
import com.openminis.k12.graph.GraphFormats
import com.openminis.k12.graph.GraphStore
import com.openminis.k12.mistakes.ErrorCause
import com.openminis.k12.mistakes.Mastery
import com.openminis.k12.mistakes.MasteryLevel
import com.openminis.k12.mistakes.MistakeBook
import com.openminis.k12.mistakes.MistakeEvent
import com.openminis.k12.tutoring.Disposition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.Instant
import kotlin.math.abs

/**
 * M3 acceptance: WP-M3-1…4 / B1–B4 / S5 mastery slice.
 * Fixtures are synthetic — no real family or NC graph data.
 */
class K12M3AcceptanceTest {

    private val childId = "11111111-2222-4333-8444-555555555555"

    @Test
    fun versionIsM3() {
        assertEquals("1.0.0-m3", Version.NAME)
        assertEquals(1_000_004, Version.CODE)
        assertTrue(Version.matchesCodeFormula())
        assertEquals("v1.0.0-m3", Version.MILESTONE_TAG)
    }

    @Test
    fun wpM3_1_mitHeaderAndPyFsrsVectors() {
        val license = moduleFile("src/main/java/com/openminis/k12/fsrs/LICENSE-java-fsrs.txt").readText()
        assertTrue(license.contains("MIT License"))
        assertTrue(license.contains("Open Spaced Repetition"))
        val schedulerSrc = moduleFile("src/main/java/com/openminis/k12/fsrs/FsrsScheduler.kt").readText()
        assertTrue(schedulerSrc.contains("Copyright (c) 2025 Open Spaced Repetition"))
        assertTrue(schedulerSrc.contains("MIT"))

        // Vectors from py-fsrs with java-fsrs v1.0.0 default parameters, fuzzing off.
        data class V(
            val rating: FsrsRating,
            val state: Int,
            val step: Int?,
            val stability: Double,
            val difficulty: Double,
            val dueDeltaSec: Long,
        )
        val vectors = listOf(
            V(FsrsRating.GOOD, 1, 1, 3.2602, 4.88463163, 600),
            V(FsrsRating.GOOD, 2, null, 3.53624366, 4.8680565, 345600),
            V(FsrsRating.HARD, 2, null, 5.77016397, 6.03886144, 518400),
            V(FsrsRating.AGAIN, 3, 0, 1.56316212, 7.84712637, 600),
            V(FsrsRating.EASY, 2, null, 4.00369607, 7.31204667, 345600),
            V(FsrsRating.GOOD, 2, null, 10.22939078, 7.27872237, 864000),
        )
        val sched = FsrsScheduler.forTests()
        var t = Instant.parse("2025-01-01T12:00:00Z")
        var card = FsrsCard.newCard(cardId = 1, due = t)
        for ((i, v) in vectors.withIndex()) {
            val result = sched.reviewCard(card, v.rating, t)
            card = result.card
            assertEquals("state@$i", v.state, card.state.value)
            assertEquals("step@$i", v.step, card.step)
            assertNear("stability@$i", v.stability, card.stability!!)
            assertNear("difficulty@$i", v.difficulty, card.difficulty!!)
            assertEquals("due@$i", v.dueDeltaSec, card.due.epochSecond - t.epochSecond)
            t = card.due
        }
        // First AGAIN on a fresh card → learning step 0, 1 minute.
        val again = sched.reviewCard(
            FsrsCard.newCard(cardId = 2, due = Instant.parse("2025-01-01T12:00:00Z")),
            FsrsRating.AGAIN,
            Instant.parse("2025-01-01T12:00:00Z"),
        ).card
        assertEquals(FsrsState.LEARNING, again.state)
        assertEquals(0, again.step)
        assertNear("again-s", 0.2172, again.stability!!)
        assertNear("again-d", 7.0114, again.difficulty!!)
    }

    @Test
    fun wpM3_2_b1_b2_recordAndFsrsQueue() {
        val book = MistakeBook()
        try {
            book.create("", "qh", "数学", ErrorCause.CALC)
            fail("empty child_id")
        } catch (_: IllegalArgumentException) {
        }
        val m = book.create(
            childId = childId,
            questionHash = "qh-b1",
            subject = "数学",
            cause = ErrorCause.CONCEPT,
            kpIds = listOf("kp.equation.one"),
        )
        assertEquals(ErrorCause.CONCEPT, m.cause)
        assertTrue(book.events().any { it is MistakeEvent.Created })

        val pending = book.create(
            childId = childId,
            questionHash = "qh-pending",
            subject = "数学",
            cause = ErrorCause.UNKNOWN,
            disposition = Disposition.PENDING_CHECK,
        )
        assertFalse(pending.countsInMistakeStats)
        assertTrue(book.dueForReview(childId).none { it.id == pending.id })

        val now = Instant.parse("2025-02-01T00:00:00Z")
        val reviewed = book.review(childId, m.id, FsrsRating.GOOD, now)
        assertNotNull(reviewed)
        assertTrue(reviewed!!.due.isAfter(now))
        assertTrue(book.events().any { it is MistakeEvent.ReviewDone })
    }

    @Test
    fun wpM3_3_b3_b4_masteryCorrectionVariants() {
        val book = MistakeBook()
        val m = book.create(
            childId = childId,
            questionHash = "qh-b3",
            subject = "数学",
            cause = ErrorCause.METHOD,
            kpIds = listOf("kp.equation.one"),
        )
        // Saw full reveal → not a correction.
        assertNull(book.markCorrected(childId, m.id, sawFullReveal = true, independentRedoCorrect = true))
        val corrected = book.markCorrected(
            childId, m.id,
            sawFullReveal = false,
            independentRedoCorrect = true,
            now = Instant.parse("2025-02-02T00:00:00Z"),
        )
        assertNotNull(corrected?.correctedAt)
        assertTrue(book.events().any { it is MistakeEvent.Corrected })

        val attempts = (1..3).map { i ->
            Mastery.FirstAttempt(
                correct = i != 2,
                at = Instant.parse("2025-01-0${i}T12:00:00Z"),
            )
        }
        val (score, level) = book.applyMastery(
            childId, "kp.equation.one", attempts,
            now = Instant.parse("2025-01-10T12:00:00Z"),
        )
        assertNotNull(score)
        assertTrue(level != MasteryLevel.UNRATED)
        assertTrue(book.list(childId).first { it.id == m.id }.pushReason!!.contains("掌握度"))

        val plan = book.variantPlan(m)
        assertEquals(3, plan.size)
        assertTrue(plan[0].contains("基础"))
        assertTrue(plan[2].contains("稍难"))
        assertFalse(plan.any { it.contains("http") })

        // S5 thresholds
        assertEquals(MasteryLevel.MASTERED, Mastery.levelFor(0.85))
        assertEquals(MasteryLevel.GOOD, Mastery.levelFor(0.65))
        assertEquals(MasteryLevel.PASS, Mastery.levelFor(0.40))
        assertEquals(MasteryLevel.WEAK, Mastery.levelFor(0.39))
        assertEquals(MasteryLevel.UNRATED, Mastery.score(attempts.take(2), emptyList()).second)
    }

    @Test
    fun wpM3_4_graphDownloadPrereqNotInApkAsNc() {
        val graphText = resource("k12/sample-graph.txt").readText()
        val manifestText = resource("k12/manifest.sample.txt").readText()
        assertFalse(graphText.contains("K12-KGraph"))
        assertFalse(graphText.contains("cn-math-taxonomy"))
        val entries = GraphFormats.parseManifest(manifestText)
        assertEquals(1, entries.size)
        assertEquals(GraphFormats.REQUIRED_LICENSE, entries[0].license)
        val bytes = graphText.toByteArray()
        assertEquals(entries[0].sha256, GraphFormats.sha256Hex(bytes))

        val dir = File.createTempFile("k12-graph", null).apply {
            delete()
            mkdirs()
        }
        val store = GraphStore(dir)
        assertTrue(store.installFromBytes(entries[0], bytes).isSuccess)
        assertTrue(store.prereqBacktrack("kp.equation.two").contains("kp.equation.one"))
        assertTrue(store.prereqBacktrack("kp.equation.two").contains("kp.integer.add"))
        assertEquals(listOf("未映射"), store.prereqBacktrack("kp.missing"))

        // Bad digest rejected; existing good copy kept.
        val bad = entries[0].copy(sha256 = "0".repeat(64))
        assertTrue(store.installFromBytes(bad, bytes).isFailure)
        assertNotNull(store.graph)

        // Wrong license rejected.
        val badLic = entries[0].copy(license = "MIT")
        assertTrue(store.installFromBytes(badLic, bytes).isFailure)
    }

    @Test
    fun changelogAndBlockerMentionSymja() {
        val changelog = repoFile("CHANGELOG.md").readText()
        assertTrue(changelog.contains("## [1.0.0-m3]"))
        assertTrue(changelog.contains("Symja") || changelog.contains("CAS"))
        val roadmap = repoFile("docs/k12/roadmap.md").readText()
        assertTrue(roadmap.contains("Symja") || roadmap.contains("CAS"))
        assertTrue(roadmap.contains("v1 发布阻塞") || roadmap.contains("发布阻塞"))
    }

    private fun assertNear(label: String, expected: Double, actual: Double) {
        assertTrue("$label expected=$expected actual=$actual", abs(expected - actual) < 1e-5)
    }

    private fun moduleFile(name: String): File = listOf(
        File(name),
        File("app/$name"),
        File("src/android/app/$name"),
    ).first { it.exists() }

    private fun repoFile(name: String): File = listOf(
        File(name),
        File("../../$name"),
        File("../../../$name"),
    ).firstOrNull { it.exists() } ?: File("../../$name")

    private fun resource(path: String): File {
        val url = javaClass.classLoader.getResource(path)
            ?: error("missing resource $path")
        return File(url.toURI())
    }
}
