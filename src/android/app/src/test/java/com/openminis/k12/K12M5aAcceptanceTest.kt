package com.openminis.k12

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M5a is primarily the Go server + Vue admin under server/.
 * Android keeps the shared product SemVer in lockstep.
 */
class K12M5aAcceptanceTest {

    @Test
    fun versionIsM5a() {
        assertTrue(Version.CODE >= 1_000_005)
        assertTrue(Version.matchesCodeFormula())
        assertTrue(repoFile("CHANGELOG.md").readText().contains("## [1.0.0-m5a]"))
    }

    @Test
    fun serverModuleAndOpsDocsPresent() {
        assertTrue(repoFile("server/go.mod").exists())
        assertTrue(repoFile("server/go.mod").readText().contains("github.com/ChuckPeng/OpenMinis-K12/server"))
        assertTrue(repoFile("server/README.md").readText().contains("GPL-3.0"))
        assertTrue(repoFile("server/cmd/openminis-k12-server").isDirectory ||
            repoFile("server/cmd/openminis-k12-server/main.go").exists())
        assertTrue(repoFile("docs/k12/ops/j12-live-test.md").exists())
        assertTrue(repoFile("docs/k12/ops/install-systemd.md").exists())
        val changelog = repoFile("CHANGELOG.md").readText()
        assertTrue(changelog.contains("## [1.0.0-m5a]"))
    }

    private fun repoFile(name: String): File = listOf(
        File(name),
        File("../../$name"),
        File("../../../$name"),
    ).firstOrNull { it.exists() } ?: File("../../$name")
}
