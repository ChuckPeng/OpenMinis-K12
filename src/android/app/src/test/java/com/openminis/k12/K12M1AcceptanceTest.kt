package com.openminis.k12

import com.openminis.app.data.repository.SkillRepository
import com.openminis.k12.skills.K12SkillBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M1 acceptance (WP-M1-1…WP-M1-4 / K1, K3, K4).
 * Fixtures are synthetic — no real family data.
 */
class K12M1AcceptanceTest {

    @Test
    fun versionAtLeastM1() {
        assertTrue(Version.NAME.startsWith("1.0.0"))
        assertTrue(Version.CODE >= 1_000_002)
        assertTrue(Version.matchesCodeFormula())
        assertEquals("v${Version.NAME}", Version.MILESTONE_TAG)
    }

    @Test
    fun wpM1_1_k1_studyCompanionCorePresentAndParseable() {
        val skill = assetFile("skills/study-companion-core/SKILL.md")
        assertTrue(skill.isFile)
        val text = skill.readText()
        val parsed = SkillRepository.parseSkillMd(text)
        assertNotNull(parsed)
        assertEquals(K12SkillBundle.CORE_SKILL_ID, parsed!!.name)
        assertTrue(parsed.version.isNotBlank())
        assertTrue(K12SkillBundle.coreSkillPassesK1(text))
        assertTrue(text.contains("请直接问爸爸妈妈"))
        assertTrue(text.contains("ghostwriting") || text.contains("不代写"))
        assertTrue(text.contains("Beyond syllabus") || text.contains("超纲"))
        assertTrue(text.contains("Layered hints") || text.contains("分层"))
        assertTrue(text.contains("Breaks") || text.contains("休息"))
    }

    @Test
    fun wpM1_2_k3_studentTemplateHasNoPii() {
        val template = assetFile("templates/student.md")
        assertTrue(template.isFile)
        val text = template.readText()
        assertTrue(K12SkillBundle.studentTemplatePassesK3(text))
        assertTrue(text.contains("schema: k12-student/v1"))
        assertTrue(text.contains("Forbidden in this file"))
        assertFalse(text.contains("张三"))
        assertFalse(text.contains("某市第一中学"))
        assertFalse(Regex("""\b1[3-9]\d{9}\b""").containsMatchIn(text))
    }

    @Test
    fun wpM1_3_sampleCatalogsCoverFirstSubjects() {
        val expected = listOf(
            "junior-math" to "数学",
            "junior-english-vocab" to "英语",
            "primary-chinese" to "语文",
            "primary-math" to "数学",
            "primary-english" to "英语",
        )
        assertEquals(expected.map { "k12/catalogs/${it.first}/catalog.md" }, K12SkillBundle.SAMPLE_CATALOG_ASSETS)
        for ((dir, subject) in expected) {
            val file = assetFile("catalogs/$dir/catalog.md")
            assertTrue(dir, file.isFile)
            val text = file.readText()
            assertTrue(dir, text.contains("sample: true"))
            assertTrue(dir, text.contains("subject: $subject") || text.contains(subject))
            assertTrue(dir, text.contains("知识点") || text.contains("| word |"))
            assertFalse(dir, text.contains("张三"))
            assertFalse(dir, text.contains("某市第一中学"))
        }
    }

    @Test
    fun wpM1_4_k4_officialMinisInstallDoc() {
        val doc = repoFile("docs/k12/skills/install-into-official-minis.md")
        assertTrue(doc.isFile)
        val text = doc.readText()
        assertTrue(text.contains("K4"))
        assertTrue(text.contains("study-companion-core"))
        assertTrue(text.contains("/var/minis/skills/"))
        assertTrue(text.contains("官方 Minis") || text.contains("com.openminis.app"))
        assertTrue(text.contains("SKILL.md"))
    }

    @Test
    fun changelogListsM1() {
        val changelog = repoFile("CHANGELOG.md")
        assertTrue(changelog.readText().contains("## [1.0.0-m1]"))
    }

    private fun assetFile(relative: String): File = listOf(
        File("src/main/assets/k12/$relative"),
        File("app/src/main/assets/k12/$relative"),
        File("src/android/app/src/main/assets/k12/$relative"),
    ).first { it.exists() }

    private fun repoFile(name: String): File = listOf(
        File(name),
        File("../../$name"),
        File("../../../$name"),
    ).firstOrNull { it.exists() } ?: File("../../$name")
}
