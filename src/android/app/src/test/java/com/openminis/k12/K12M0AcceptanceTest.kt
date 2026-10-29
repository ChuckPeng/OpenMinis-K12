package com.openminis.k12

import com.openminis.k12.android.K12Gates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZonedDateTime

/**
 * M0 acceptance, exercised without a device and without any family data.
 * WP-M0-1 through WP-M0-8.
 */
class K12M0AcceptanceTest {

    @Test
    fun wpM0_1_identityConstants() {
        assertEquals("com.openminis.k12", Identity.APPLICATION_ID)
        assertEquals("伴学 Minis", Identity.DISPLAY_NAME)
        assertEquals("ChuckPeng/OpenMinis-K12", Identity.RELEASE_REPO)
    }

    @Test
    fun wpM0_1_k12SemVerIndependentOfUpstream() {
        assertTrue(Version.NAME.startsWith("1.0.0"))
        assertTrue(Version.CODE >= 1_000_001)
        assertTrue(Version.matchesCodeFormula())
        assertEquals("1.15", Version.UPSTREAM_VERSION_NAME)
        assertEquals(37, Version.UPSTREAM_VERSION_CODE)
        assertEquals(1, Version.DEVICE_API)
        assertEquals(1, Version.DEVICE_API_MIN)
        assertEquals("v${Version.NAME}", Version.MILESTONE_TAG)
        assertTrue(Version.CODE > Version.UPSTREAM_VERSION_CODE)
    }

    @Test
    fun wpM0_1_gradleAndLauncherName() {
        val gradle = moduleFile("build.gradle.kts").readText()
        assertTrue(gradle.contains("applicationId = \"com.openminis.k12\""))
        assertTrue(gradle.contains("namespace = \"com.openminis.app\""))
        assertTrue(gradle.contains("ChuckPeng/OpenMinis-K12"))
        assertTrue(gradle.contains("versionName = \"${Version.NAME}\""))
        assertTrue(
            gradle.contains("versionCode = ${Version.CODE}") ||
                gradle.contains("versionCode = 1_000_00${Version.CODE % 10}"),
        )
        assertFalse(gradle.contains("applicationId = \"com.openminis.app\""))
        val appName = moduleFile("src/main/res/values/strings.xml").readText()
        assertTrue(appName.contains("<string name=\"app_name\">伴学 Minis</string>"))
        val changelog = repoFile("CHANGELOG.md")
        assertTrue(changelog.isFile)
        assertTrue(changelog.readText().contains("## [1.0.0-m0]"))
    }

    @Test
    fun wpM0_1_buildConfigApplicationId() {
        assertEquals(Identity.APPLICATION_ID, com.openminis.app.BuildConfig.APPLICATION_ID)
        assertEquals(Version.NAME, com.openminis.app.BuildConfig.VERSION_NAME)
        assertEquals(Version.CODE, com.openminis.app.BuildConfig.VERSION_CODE)
        assertEquals(Version.UPSTREAM_VERSION_NAME, com.openminis.app.BuildConfig.K12_UPSTREAM_VERSION_NAME)
        assertEquals(Version.UPSTREAM_VERSION_CODE, com.openminis.app.BuildConfig.K12_UPSTREAM_VERSION_CODE)
        assertEquals(Version.DEVICE_API, com.openminis.app.BuildConfig.K12_DEVICE_API)
    }

    @Test
    fun wpM0_2_coldStartIsChildHomeAfterOnboarding() {
        assertEquals(ColdStart.ONBOARDING, ColdStart.route(onboardingComplete = false))
        assertEquals(ColdStart.HOME, ColdStart.route(onboardingComplete = true))
        assertNotEquals("sessions", ColdStart.route(true))
    }

    @Test
    fun wpM0_3_e1_lockedParentCannotMutateSensitiveAreas() {
        for (area in SensitiveArea.entries) {
            assertFalse(canMutate(area, parentUnlocked = false))
            assertTrue(canMutate(area, parentUnlocked = true))
        }
        assertTrue(RoutePolicy.blocked("providers", true, parentUnlocked = false, studyLocked = false))
        assertTrue(RoutePolicy.blocked("background", true, parentUnlocked = false, studyLocked = false))
        assertFalse(RoutePolicy.blocked("providers", true, parentUnlocked = true, studyLocked = false))
    }

    @Test
    fun wpM0_4_f1_childCannotOpenGuardedEntries() {
        val guarded = listOf(
            "settings", "providers", "terminal", "file_browser", "mcp",
            "agent_tools", "env_vars", "network", "logs", "shizuku",
            "system_permissions", "scheduled_tasks", "background",
        )
        for (route in guarded) {
            assertTrue(route, RoutePolicy.blocked(route, true, false, false))
            assertFalse(route, RoutePolicy.blocked(route, true, true, false))
        }
        assertTrue(RoutePolicy.blocked("terminal?initCommand=x", true, false, false))
        assertTrue(DeepLinkPolicy.blocked(DeepLinkKind.TERMINAL, enforcing = true))
        assertTrue(DeepLinkPolicy.blocked(DeepLinkKind.SETTINGS, enforcing = true))
        assertTrue(DeepLinkPolicy.blocked(DeepLinkKind.NEW_CHAT, enforcing = true))
        assertFalse(DeepLinkPolicy.blocked(DeepLinkKind.TERMINAL, enforcing = false))
        assertTrue(BypassActivityPolicy.blocked("com.openminis.app.share.ShareReceiverActivity", true))
        assertTrue(BypassActivityPolicy.blocked("com.openminis.app.webapp.WebAppActivity", true))
        assertTrue(BypassActivityPolicy.blocked("com.openminis.app.auth.OAuthRedirectActivity", true))
        assertFalse(BypassActivityPolicy.blocked("com.openminis.app.MainActivity", true))
        assertFalse(RoutePolicy.blocked(ColdStart.HOME, true, false, true))
    }

    @Test
    fun wpM0_4_f2_denyListBlocksCapabilitiesAndKeepsSpeech() {
        val denied = listOf(
            "android-contacts", "contacts", "android-location", "calendar",
            "clipboard", "android-notification", "photos", "android-device",
            "android-shizuku-cli", "shizuku_cli", "android-a11y-cli", "a11y_cli",
            "minis-debug", "minis-browser-use", "browser_use", "minis-scheduled",
        )
        for (name in denied) {
            assertTrue(name, OffloadPolicy.blocked(name, enforcing = true))
            assertFalse(name, OffloadPolicy.blocked(name, enforcing = false))
        }
        assertFalse(OffloadPolicy.blocked("android-speech", true))
        assertFalse(OffloadPolicy.blocked("android-speak", true))
        assertTrue(OffloadPolicy.agentToolBlocked("browser_use", true))
        assertFalse(OffloadPolicy.agentToolBlocked("browser_use", false))
        assertTrue(OffloadPolicy.scheduledTaskBlocked(true))
        assertFalse(OffloadPolicy.scheduledTaskBlocked(false))
        assertTrue(OffloadPolicy.developerToolsClosed(true))
        assertFalse(K12Gates.offloadBlocked("android-contacts"))
    }

    @Test
    fun wpM0_4_releaseBuildClosesDevTools() {
        val gradle = moduleFile("build.gradle.kts").readText()
        assertTrue(gradle.contains("buildConfigField(\"boolean\", \"DEV_TOOLS\", \"false\")"))
        val release = gradle.substringAfter("release {").substringBefore("productFlavors")
        assertFalse(release.contains("DEV_TOOLS\", \"true\""))
    }

    @Test
    fun wpM0_5_dailyLimitsBreakReminderAndExtension() {
        val friday = at(2026, 10, 30, 12)
        val primary = reach(StudyStage.PRIMARY_LOWER, friday, 39)
        assertFalse(ScreenTime.limitReached(primary, friday + minutes(39)))
        assertTrue(ScreenTime.breakDue(primary))
        val primaryLimit = reach(StudyStage.PRIMARY_LOWER, friday, 40)
        assertTrue(ScreenTime.limitReached(primaryLimit, friday + minutes(40)))
        assertEquals(StudyClock.dayKey(friday), primaryLimit.profile.limitReachedDay)
        assertTrue(ScreenTime.breakDue(reach(StudyStage.PRIMARY_LOWER, friday, 25)))

        val junior = reach(StudyStage.JUNIOR, friday, 89)
        assertFalse(ScreenTime.limitReached(junior, friday + minutes(89)))
        assertFalse(ScreenTime.breakDue(reach(StudyStage.JUNIOR, friday, 39)))
        assertTrue(ScreenTime.limitReached(reach(StudyStage.JUNIOR, friday, 90), friday + minutes(90)))
        assertTrue(ScreenTime.breakDue(reach(StudyStage.JUNIOR, friday, 40)))

        val saturday = at(2026, 10, 31, 12)
        assertTrue(StudyClock.isWeekend(saturday))
        assertFalse(StudyClock.isWeekend(friday))
        val weekend = reach(StudyStage.PRIMARY_LOWER, saturday, 70)
        assertTrue(ScreenTime.limitReached(weekend, saturday + minutes(70)))
        assertFalse(ScreenTime.limitReached(reach(StudyStage.PRIMARY_LOWER, saturday, 69), saturday + minutes(69)))

        val locked = reach(StudyStage.PRIMARY_LOWER, friday, 40)
        val extended = ScreenTime.grant(locked, friday + minutes(40), ScreenTime.EXTENSION_QUANTUM_MIN)
        assertFalse(ScreenTime.limitReached(extended, friday + minutes(40)))
        assertEquals(15, ScreenTime.EXTENSION_QUANTUM_MIN)
    }

    @Test
    fun wpM0_5_onlyCountsForegroundAndIgnoresClockJumps() {
        val start = at(2026, 10, 30, 12)
        var state = armed(StudyStage.PRIMARY_LOWER)
        state = ScreenTime.onForeground(state, start)
        state = ScreenTime.onBackground(state, start + minutes(10))
        state = ScreenTime.onTick(state, start + minutes(40))
        assertEquals(minutes(10), state.ledger.usedMs)

        var backward = ScreenTime.onForeground(armed(StudyStage.PRIMARY_LOWER), start)
        backward = ScreenTime.onTick(backward, start - 5_000)
        assertEquals(0L, backward.ledger.usedMs)
        assertTrue(backward.clockAnomaly)

        var jump = ScreenTime.onForeground(armed(StudyStage.JUNIOR), start)
        jump = ScreenTime.onTick(jump, start + ScreenTime.MAX_SPAN_MS + 1)
        assertEquals(0L, jump.ledger.usedMs)
        assertTrue(jump.clockAnomaly)
    }

    @Test
    fun wpM0_5_restClearsContinuousUse() {
        val start = at(2026, 10, 30, 12)
        var state = ScreenTime.onForeground(armed(StudyStage.PRIMARY_LOWER), start)
        state = ScreenTime.onTick(state, start + minutes(20))
        state = ScreenTime.onBackground(state, start + minutes(20))
        state = ScreenTime.onForeground(state, start + minutes(20) + 61_000)
        assertEquals(0L, state.ledger.continuousMs)
        assertFalse(ScreenTime.breakDue(state))
    }

    @Test
    fun wpM0_5_studyDayBoundaryAndSeniorDefaults() {
        val fridayEarly = at(2026, 10, 30, 3)
        assertEquals("2026-10-29", StudyClock.dayKey(fridayEarly))
        assertFalse(StudyClock.isWeekend(fridayEarly))
        val saturdayMorning = at(2026, 10, 31, 5)
        assertTrue(StudyClock.isWeekend(saturdayMorning))
        assertEquals(StudyStage.JUNIOR.dailyLimitMinutes(), StudyStage.SENIOR.dailyLimitMinutes())
        assertEquals(StudyStage.JUNIOR.breakReminderMinutes(), StudyStage.SENIOR.breakReminderMinutes())
        assertFalse(StudyStage.SENIOR.curriculumInV1())
        assertTrue(StudyStage.PRIMARY_LOWER.curriculumInV1())
    }

    @Test
    fun wpM0_5_parentCanEditNumbersOnlyWhileUnlocked() {
        val state = armed(StudyStage.PRIMARY_LOWER).copy(parent = ParentSession(unlocked = true, lastTouchMs = 1))
        assertNull(ParentGate.updateLimits(state.copy(parent = ParentSession()), 50, 10, 20))
        val edited = ParentGate.updateLimits(state, 50, 10, 20)
        assertEquals(50, edited!!.profile.dailyLimitOverrideMin)
        assertNull(ParentGate.updateLimits(state, 1, 10, 20))
    }

    @Test
    fun wpM0_6_childIdIsStableAndSecretsAreRequired() {
        var state = Wizard.chooseStage(K12State(), StudyStage.PRIMARY_UPPER)
        state = Wizard.openIdentity(state)
        val child = state.profile.childId
        val device = state.profile.deviceId
        assertTrue(Ids.isUuidV4(child))
        assertTrue(Ids.isUuidV4(device))
        state = Wizard.openIdentity(state)
        assertEquals(child, state.profile.childId)
        assertEquals(device, state.profile.deviceId)
        assertFalse(Wizard.canLeaveSecrets(state.profile))
        assertFalse(state.profile.onboardingComplete)

        val rejected = Wizard.submitSecrets(state, "12", "12", "password1", "password1")
        assertTrue(rejected is SecretSubmit.Rejected)
        val mismatch = Wizard.submitSecrets(state, "1234", "1235", "password1", "password1")
        assertTrue(mismatch is SecretSubmit.Rejected)

        val accepted = Wizard.submitSecrets(state, "1234", "1234", "password1", "password1") as SecretSubmit.Accepted
        assertTrue(accepted.state.profile.hasPin())
        assertNotEquals("1234", accepted.state.profile.pinHash)
        assertEquals(64, accepted.state.profile.pinHash!!.length)
        assertNotEquals(accepted.state.profile.pinSalt, accepted.state.profile.backupSalt)
        assertTrue(Wizard.canLeaveSecrets(accepted.state.profile))
        val done = Wizard.complete(accepted.state)
        assertTrue(done.profile.onboardingComplete)
        assertEquals(child, done.profile.childId)
        assertEquals("unpaired", done.profile.pairing)
        assertNull(normalizeNickname("  \n "))
        assertEquals("甲", normalizeNickname(" 甲 "))
    }

    @Test
    fun wpM0_6_pinLocksAfterFiveFailures() {
        val secrets = Wizard.submitSecrets(
            Wizard.openIdentity(Wizard.chooseStage(K12State(), StudyStage.JUNIOR)),
            "1234", "1234", "password1", "password1",
        ) as SecretSubmit.Accepted
        var state = secrets.state
        val now = 1_000_000L
        repeat(4) {
            val result = ParentGate.unlock(state, "0000", now) as PinResult.Rejected
            assertFalse(result.locked)
            state = result.state
        }
        val fifth = ParentGate.unlock(state, "0000", now) as PinResult.Rejected
        assertTrue(fifth.locked)
        state = fifth.state
        val during = ParentGate.unlock(state, "1234", now + 1_000) as PinResult.Rejected
        assertTrue(during.locked)
        val after = ParentGate.unlock(state, "1234", now + Secrets.LOCK_MS + 1)
        assertTrue(after is PinResult.Accepted)
        assertTrue((after as PinResult.Accepted).state.parent.unlocked)

        val idle = ParentGate.expireIdle(after.state, after.state.parent.lastTouchMs + ScreenTime.PARENT_IDLE_MS)
        assertFalse(idle.parent.unlocked)
        assertFalse(ParentGate.lock(after.state).parent.unlocked)
    }

    @Test
    fun wpM0_6_extensionPinDoesNotLeaveParentUnlocked() {
        val secrets = Wizard.submitSecrets(
            Wizard.openIdentity(Wizard.chooseStage(K12State(), StudyStage.PRIMARY_LOWER)),
            "1234", "1234", "password1", "password1",
        ) as SecretSubmit.Accepted
        val now = at(2026, 10, 30, 12)
        var state = reach(StudyStage.PRIMARY_LOWER, now, 40).copy(profile = secrets.state.profile.copy(stage = StudyStage.PRIMARY_LOWER))
        val granted = ParentGate.grantWithPin(state, "1234", now + minutes(40)) as PinResult.Accepted
        assertFalse(granted.state.parent.unlocked)
        assertFalse(ScreenTime.limitReached(granted.state, now + minutes(40)))
    }

    @Test
    fun wpM0_7_permissionReportListsOnlyMissingItems() {
        val none = PermissionReport(false, false, false, false)
        assertEquals(PermissionItem.entries.toList(), none.unauthorized())
        val all = PermissionReport(true, true, true, true)
        assertTrue(all.unauthorized().isEmpty())
        val battery = PermissionReport(true, false, true, true)
        assertEquals(listOf(PermissionItem.BATTERY), battery.unauthorized())
        assertEquals(
            listOf("notifications_enabled", "battery_unrestricted", "background_allowed", "exact_alarm_allowed"),
            PermissionItem.entries.map { it.wire },
        )
    }

    @Test
    fun wpM0_8_releaseKeysStayOutsideTheRepo() {
        val gradle = moduleFile("build.gradle.kts").readText()
        assertTrue(gradle.contains("OPENMINIS_K12_KEYSTORE"))
        assertFalse(Regex("storePassword\\s*=\\s*\"").containsMatchIn(gradle))
        val ignore = repoFile(".gitignore").readText()
        assertTrue(ignore.contains("*.keystore"))
        assertTrue(ignore.contains("keystore.properties"))
        assertFalse(repoFile("keystore.properties").exists())
    }

    private fun armed(stage: StudyStage) = K12State(
        profile = Profile(stage = stage, onboardingComplete = true),
    )

    private fun reach(stage: StudyStage, start: Long, mins: Int): K12State {
        val foreground = ScreenTime.onForeground(armed(stage), start)
        return ScreenTime.onTick(foreground, start + minutes(mins))
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int): Long =
        ZonedDateTime.of(year, month, day, hour, 0, 0, 0, StudyClock.zone).toInstant().toEpochMilli()

    private fun minutes(n: Int): Long = n * 60_000L

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
}
