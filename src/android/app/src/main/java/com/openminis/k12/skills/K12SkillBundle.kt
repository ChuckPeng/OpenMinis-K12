package com.openminis.k12.skills

import android.content.Context
import android.util.Log
import com.openminis.app.data.repository.SkillRepository
import java.io.File

/**
 * Ships the M1 three-tier skill files from APK assets.
 * Core skill → SkillRepository (H-18). Templates/catalogs → app-private files.
 * Never writes real family data; samples are synthetic.
 */
object K12SkillBundle {
    const val ASSET_ROOT = "k12"
    const val CORE_SKILL_ID = "study-companion-core"
    const val CORE_ASSET = "k12/skills/study-companion-core/SKILL.md"
    const val STUDENT_TEMPLATE_ASSET = "k12/templates/student.md"

    val SAMPLE_CATALOG_ASSETS: List<String> = listOf(
        "k12/catalogs/junior-math/catalog.md",
        "k12/catalogs/junior-english-vocab/catalog.md",
        "k12/catalogs/primary-chinese/catalog.md",
        "k12/catalogs/primary-math/catalog.md",
        "k12/catalogs/primary-english/catalog.md",
    )

    private const val TAG = "K12SkillBundle"

    fun install(context: Context, skills: SkillRepository) {
        installCoreSkill(context, skills)
        materializePrivateFiles(context)
    }

    fun installCoreSkill(context: Context, skills: SkillRepository) {
        val content = readAsset(context, CORE_ASSET) ?: run {
            Log.e(TAG, "missing asset $CORE_ASSET")
            return
        }
        val parsed = SkillRepository.parseSkillMd(content) ?: run {
            Log.e(TAG, "core SKILL.md failed to parse")
            return
        }
        val existing = skills.skills.value.find { it.id == CORE_SKILL_ID }
        if (existing != null && existing.version >= parsed.version) {
            Log.i(TAG, "keep local $CORE_SKILL_ID v${existing.version}")
            return
        }
        skills.importFromContent(content, SkillRepository.ImportSource.BUNDLED)
        Log.i(TAG, "installed $CORE_SKILL_ID v${parsed.version}")
    }

    fun materializePrivateFiles(context: Context) {
        val root = File(context.filesDir, "k12")
        copyAssetIfAbsent(context, STUDENT_TEMPLATE_ASSET, File(root, "templates/student.md"))
        for (asset in SAMPLE_CATALOG_ASSETS) {
            val name = asset.removePrefix("k12/catalogs/").removeSuffix("/catalog.md")
            copyAssetIfAbsent(context, asset, File(root, "catalogs/$name/catalog.md"))
        }
    }

    fun readAsset(context: Context, path: String): String? =
        runCatching {
            context.assets.open(path).bufferedReader().use { it.readText() }
        }.getOrNull()

    private fun copyAssetIfAbsent(context: Context, asset: String, dest: File) {
        if (dest.exists()) return
        val text = readAsset(context, asset) ?: return
        dest.parentFile?.mkdirs()
        dest.writeText(text)
    }

    fun coreSkillPassesK1(content: String): Boolean {
        val parsed = SkillRepository.parseSkillMd(content) ?: return false
        if (parsed.name != CORE_SKILL_ID) return false
        val layered = content.contains("Layered hints") || content.contains("分层提示")
        val noGhostwrite = content.contains("ghostwriting") || content.contains("不代写")
        val beyond = content.contains("Beyond syllabus") || content.contains("超纲")
        val breaks = content.contains("Breaks and study load") || content.contains("休息")
        val pinLine = content.contains("请直接问爸爸妈妈")
        return layered && noGhostwrite && beyond && breaks && pinLine
    }

    fun studentTemplatePassesK3(content: String): Boolean {
        if (!content.contains("schema: k12-student/v1")) return false
        if (!content.contains("nickname")) return false
        if (!content.contains("stage")) return false
        if (!content.contains("Forbidden in this file")) return false
        // Template placeholders only — no filled personal identity.
        return !content.contains("张三") &&
            !content.contains("某市第一中学") &&
            !Regex("""\b1[3-9]\d{9}\b""").containsMatchIn(content)
    }
}
