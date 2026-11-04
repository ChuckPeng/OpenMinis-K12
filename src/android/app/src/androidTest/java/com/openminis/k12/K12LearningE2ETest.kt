package com.openminis.k12

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
import androidx.test.espresso.Espresso
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.openminis.k12.android.K12PairingRuntime
import com.openminis.k12.android.K12Runtime
import com.openminis.k12.android.K12SafetyRuntime
import com.openminis.k12.android.K12StudyDesk
import com.openminis.k12.android.K12SyncRuntime
import com.openminis.k12.device.PairingClient
import com.openminis.k12.model.K12ModelRuntime
import com.openminis.k12.tutoring.TutoringAction
import com.openminis.k12.tutoring.TutoringRuntime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Learning features on a real emulator UI. The workflow passes the admin API
 * and the fake model's base URL. Pair codes are minted here. Bodies that can
 * contain a chat sentence are not written to logcat.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class K12LearningE2ETest {
    private val args = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    fun allowEmulatorHttp() {
        PairingClient.allowCleartextForTests = true
    }

    @After
    fun restoreHttpsOnly() {
        PairingClient.allowCleartextForTests = false
        runCatching { Intents.release() }
    }

    @Test
    fun pendingPairingActivatesAfterRestart() {
        val baseUrl = required("baseUrl")
        val admin = admin()
        val code = admin.pairCode()
        resetTablet()
        seedPairingStep()
        launch()
        fillPairing(baseUrl, code)
        clickText(BIND)
        waitText(WAIT_TITLE, 30_000)
        shot("a-pending")
        restartProcess()
        launch()
        val waiting = waitText(WAIT_TITLE, 20_000)
        assertNotNull("pending screen did not survive restart", waiting)
        shot("a-pending-restart")
        val childId = K12Runtime.snapshot().profile.childId.orEmpty()
        val deviceId = K12Runtime.snapshot().profile.deviceId.orEmpty()
        assertTrue(deviceId.isNotBlank())
        admin.approve(deviceId)
        admin.putTabletModel(required("modelUrl"))
        awaitAppliedModel(required("modelUrl"))
        assertEquals("active", K12Runtime.snapshot().profile.pairing)
        assertModelApplied(required("modelUrl"))
        val row = admin.deviceRow(deviceId)
        assertTrue("last_seen missing: $row", row.has("last_seen") && row.optString("last_seen").isNotBlank())
        Log.i(TAG, "pending restart reached active child=${childId.take(8)}")
    }

    @Test
    fun learningJourney() {
        val baseUrl = required("baseUrl")
        val modelUrl = required("modelUrl")
        val admin = admin()
        val code = admin.pairCode()
        resetTablet()
        launch()
        waitText(ONBOARDING, 30_000)
        shot("01-onboarding-stage")
        chooseStage(SENIOR)
        waitText(SENIOR_NOTE, 10_000)
        shot("02-stage-senior")
        chooseStage(JUNIOR)
        clickText(NEXT)
        waitText(ID_TITLE, 15_000)
        shot("03-identity")
        clickText(NEXT)
        waitText(SECRETS, 15_000)
        setField(PIN, "1357")
        setField(PIN_AGAIN, "1357")
        setField(BACKUP, "e2e-pass")
        setField(BACKUP_AGAIN, "e2e-pass")
        clickText(NEXT)
        assertNotNull("secrets were rejected", waitText(PERM_LATER, 15_000))
        shot("04-permissions")
        clickText(NEXT)
        assertNotNull("pairing form did not open", waitText(PAIR_TITLE, 15_000))
        fillPairing(baseUrl, code)
        clickText(BIND)
        waitText(WAIT_TITLE, 30_000)
        shot("05-pending")
        restartProcess()
        launch()
        assertNotNull(waitText(WAIT_TITLE, 20_000))
        shot("06-pending-restart")
        val childId = K12Runtime.snapshot().profile.childId.orEmpty()
        val deviceId = K12Runtime.snapshot().profile.deviceId.orEmpty()
        assertTrue("child id was not a v4", childId.length >= 32)
        admin.approve(deviceId)
        admin.putTabletModel(modelUrl)
        assertNotNull("empty chat did not open after the model arrived", waitText(EMPTY_CHAT, 90_000))
        shot("07-sessions")
        assertEquals("active", K12Runtime.snapshot().profile.pairing)
        assertModelApplied(modelUrl)
        val seen = admin.deviceRow(deviceId)
        assertTrue(seen.optString("last_seen").isNotBlank())

        openNewChat()
        sendChat("2+2")
        assertNotNull(waitContains(MATH_ANSWER, 120_000))
        shot("08-math")
        sendChat("again")
        assertNotNull(waitContains(FOLLOW, 90_000))
        shot("09-followup")

        device.pressBack()
        device.waitForIdle(1_000)
        openNewChat()
        sendChat("讲解")
        assertNotNull(waitContains(SOCRATIC, 90_000))
        shot("10-socratic")

        attachFixture()
        sendChat("看图")
        assertNotNull(waitContains(STEM, 90_000))
        shot("11-photo")

        TutoringRuntime.dispatch(TutoringAction.SubmitAttempt(correct = false))
        device.pressBack()
        device.waitForIdle(1_000)
        clickDescAny("Settings", "设置")
        if (waitText(PIN_TITLE, 15_000) == null) {
            die("parent PIN gate did not open; on screen: ${visibleTexts()}")
        }
        setField(PIN, "1357")
        clickText(PIN_OK)
        if (waitText(PARENT, 15_000) == null) {
            die(
                "parent screen did not open unlocked=${K12Runtime.parentUnlocked()} " +
                    "on screen: ${visibleTexts()}",
            )
        }
        swipeUntil(SKILL)
        if (waitText(SKILL, 10_000) == null) die("skill line missing; on screen: ${visibleTexts()}")
        if (waitContains(REVIEW, 10_000) == null) die("review count missing; on screen: ${visibleTexts()}")
        swipeUntil(LINKED)
        if (waitText(LINKED, 10_000) == null) die("knowledge line missing; on screen: ${visibleTexts()}")
        swipeUntil(LATER_UNLOCK)
        shot("13-parent")
        clickText(PERM_TITLE)
        if (waitText(PERM_REFRESH, 10_000) == null) {
            die("permissions screen did not open; on screen: ${visibleTexts()}")
        }
        shot("12-permissions")
        device.pressBack()
        device.waitForIdle(800)
        // Back restores the parent column scrolled to the bottom, so the
        // title 家长 is off screen. 锁定并离开 is the control this step needs.
        swipeUntil(LOCK_LEAVE)
        if (waitText(LOCK_LEAVE, 8_000) == null) {
            die("parent screen did not return; on screen: ${visibleTexts()}")
        }
        clickText(LOCK_LEAVE)
        val lockDeadline = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < lockDeadline && K12Runtime.parentUnlocked()) {
            if (device.findObject(By.text(LOCK_LEAVE)) != null) clickText(LOCK_LEAVE)
            else device.waitForIdle(400)
        }
        if (K12Runtime.parentUnlocked()) {
            die("锁定并离开 did not lock the parent session; on screen: ${visibleTexts()}")
        }

        openChatAgain()
        sendChat(CRISIS_A)
        awaitVisible(TELL_ADULT, 20_000, "emergency step 1 did not open")
        shot("14-safety-step1")
        clickText(DECLINE)
        awaitVisible(TELL_ADULT, 10_000, "emergency step 2 did not stay up")
        if (device.findObject(By.textContains("0000")) != null || textInAnyWindow("0000")) {
            die("step 2 showed a number before a hotline was configured")
        }
        shot("15-safety-step2")
        clickText(KNOWN)
        assertEquals(false, modelStats().optBoolean("crisis_reached_model"))

        admin.putChildConfig(
            childId,
            JSONObject()
                .put("daily_limit_minutes", 10)
                .put("hotline_text", "练习热线")
                .put("hotline_number", "0000"),
        )
        val synced = K12SyncRuntime.runOnce()
        Log.i(TAG, "limit sync ${synced::class.java.simpleName}")
        if (synced !is com.openminis.k12.device.SyncRunResult.Ok) fail("sync after config: $synced")
        assertEquals(10, ScreenTime.limits(K12Runtime.snapshot().profile.stage, K12Runtime.snapshot().profile).dailyMin)
        if (K12SafetyRuntime.hotlineText != "练习热线" || K12SafetyRuntime.hotlineNumber != "0000") {
            die(
                "hotline was not applied from the heartbeat " +
                    "textSet=${!K12SafetyRuntime.hotlineText.isNullOrBlank()} " +
                    "numberSet=${!K12SafetyRuntime.hotlineNumber.isNullOrBlank()}",
            )
        }

        openChatAgain()
        sendChat(CRISIS_B)
        awaitVisible(TELL_ADULT, 20_000, "second emergency step 1 did not open")
        clickText(DECLINE)
        awaitVisible("练习热线", 10_000, "configured hotline text did not show")
        awaitVisible("0000", 10_000, "configured hotline number did not show")
        shot("16-safety-hotline")
        clickText(KNOWN)

        val chip = waitStarts("剩余 ", 20_000)
        if (chip == null) die("time chip missing; on screen: ${visibleTexts()}")
        val minutes = Regex("\\d+").find(chip.text.orEmpty())?.value?.toIntOrNull()
        shot("17-time-chip")
        if (minutes == null) die("time chip had no minutes; text=${chip.text}")
        assertTrue("chip showed $minutes", minutes <= 10)

        val now = System.currentTimeMillis()
        K12Runtime.onForeground(now)
        K12Runtime.tick(now + 11L * 60_000)
        assertTrue(K12Runtime.studyLocked())
        launch()
        if (waitText(LOCK_TITLE, 20_000) == null) {
            die("lock screen missing; on screen: ${visibleTexts()}")
        }
        shot("18-lock")
        clickText(EXTEND)
        setField(PIN, "1357")
        clickText(PIN_OK)
        if (waitAny(listOf(EMPTY_CHAT, "New Chat"), 20_000) == null) {
            die("chat did not return after extend; on screen: ${visibleTexts()}")
        }
        shot("19-extended")

        val usageSync = K12SyncRuntime.runOnce()
        if (usageSync !is com.openminis.k12.device.SyncRunResult.Ok) fail("usage sync: $usageSync")
        val after = admin.deviceRow(deviceId)
        assertTrue(after.optInt("companion_minutes") >= 1)
        val report = admin.generateReport(childId)
        val attempts = report.optJSONObject("metrics")?.optInt("attempts") ?: -1
        assertTrue("report attempts=$attempts", attempts >= 1)
        val alerts = admin.get("/api/v1/admin/alerts")
        assertEquals(200, alerts.code)
        assertTrue(alerts.body.contains("emergency"))
        assertTrue(alerts.body.contains("self_harm"))
        Log.i(TAG, "journey ok attempts=$attempts minutes=${after.optInt("companion_minutes")}")
    }

    private fun resetTablet() {
        runCatching { K12PairingRuntime.unpair() }
        context.getSharedPreferences("k12_profile", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("k12_sync", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        TutoringRuntime.reset()
        K12StudyDesk.reset()
        K12SafetyRuntime.dismiss()
        K12SafetyRuntime.hotlineText = null
        K12SafetyRuntime.hotlineNumber = null
        K12ModelRuntime.onUnpairOrReset()
        K12Runtime.install(context)
        K12SyncRuntime.install(context)
        context.getSharedPreferences("appearance_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putInt("theme_mode", 2).commit()
        device.executeShellCommand("cmd uimode night yes")
    }

    private fun seedPairingStep() {
        K12Runtime.chooseStage(StudyStage.JUNIOR)
        K12Runtime.openIdentity()
        K12Runtime.openSecrets()
        val secrets = K12Runtime.submitSecrets("1357", "1357", "e2e-pass", "e2e-pass")
        if (secrets !is SecretSubmit.Accepted) fail("seed secrets rejected")
        K12Runtime.openPairing()
    }

    private fun restartProcess() {
        device.pressHome()
        device.waitForIdle(1_000)
        K12PairingRuntime.pending.value = null
        K12PairingRuntime.notice.value = null
        K12Runtime.install(context)
        K12SyncRuntime.install(context)
        assertNotNull("pending record was not restored", K12PairingRuntime.pending.value)
        assertEquals("pending", K12Runtime.snapshot().profile.pairing)
    }

    private fun launch() {
        val intent = Intent().setClassName(context.packageName, "com.openminis.app.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        device.wait(Until.hasObject(By.pkg(context.packageName)), 15_000)
        device.waitForIdle(1_500)
    }

    private fun fillPairing(baseUrl: String, code: String) {
        waitText(PAIR_TITLE, 20_000)
        setField(URL_LABEL, baseUrl)
        setField(CODE_LABEL, code)
    }

    private fun sendChat(text: String) {
        var edit = waitEdit(20_000) ?: die("chat field missing; on screen: ${visibleTexts()}")
        replaceComposer(edit, text)
        closeKeyboard()
        // Dismissing the IME can drop an uncommitted composition. Put the
        // text back while the keyboard is down, then send.
        if (!composerHolds(text)) {
            edit = waitEdit(5_000) ?: die("chat field missing after keyboard; on screen: ${visibleTexts()}")
            replaceComposer(edit, text)
        }
        if (!composerHolds(text)) die("composer did not keep the message; on screen: ${visibleTexts()}")
        tapSendUntilCleared()
    }

    private fun replaceComposer(edit: UiObject2, text: String) {
        runCatching { edit.click() }
        runCatching { edit.setText(text) }
        device.waitForIdle(300)
        if (composerHolds(text)) return
        runCatching { edit.click() }
        InstrumentationRegistry.getInstrumentation().sendStringSync(text)
        device.waitForIdle(300)
    }

    private fun composerText(): String =
        waitEdit(1_000)?.let { runCatching { it.text?.toString() }.getOrNull() }.orEmpty()

    /** Null when the composer is not on screen. An empty string is a cleared field. */
    private fun currentComposer(): String? {
        val node = runCatching { findComposer() }.getOrNull() ?: return null
        return runCatching { node.text?.toString() }.getOrNull().orEmpty()
    }

    private fun composerHolds(text: String): Boolean = composerText().contains(text)

    private fun tapSendUntilCleared() {
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            if (K12SafetyRuntime.screen.value != null) return
            val shown = currentComposer()
            if (shown != null && shown.isBlank()) return
            if (!clickDesc("Send", required = false, timeoutMs = 1_500)) {
                val node = device.findObject(By.desc("Send"))
                val bounds = node?.let { runCatching { Rect(it.visibleBounds) }.getOrNull() }
                if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                    device.click(
                        bounds.centerX().coerceIn(1, device.displayWidth - 2),
                        bounds.centerY().coerceIn(1, device.displayHeight - 2),
                    )
                } else {
                    val fieldBounds = waitEdit(2_000)?.let { runCatching { Rect(it.visibleBounds) }.getOrNull() }
                    if (fieldBounds == null) die("missing desc Send; on screen: ${visibleTexts()}")
                    val x = (fieldBounds.right - 20).coerceIn(1, device.displayWidth - 2)
                    val y = fieldBounds.centerY().coerceIn(1, device.displayHeight - 2)
                    device.click(x, y)
                }
            }
            device.waitForIdle(500)
        }
        die(
            "send did not leave the composer; unlocked=${K12Runtime.parentUnlocked()} " +
                "on screen: ${visibleTexts()}",
        )
    }

    private fun openChatAgain() {
        if (K12Runtime.parentUnlocked()) {
            die("parent session still unlocked; on screen: ${visibleTexts()}")
        }
        val deadline = System.currentTimeMillis() + 12_000
        var pressedBack = false
        while (System.currentTimeMillis() < deadline) {
            val send = runCatching { device.findObject(By.desc("Send")) }.getOrNull()
            if (send != null && visible(send)) return
            if (runCatching { device.findObject(By.desc("New Chat")) }.getOrNull()?.let { visible(it) } == true) {
                openNewChat()
                return
            }
            // The parent column is still up. Back would leave it without locking.
            if (device.findObject(By.text(LOCK_LEAVE)) != null) {
                device.waitForIdle(400)
                continue
            }
            if (!pressedBack) {
                device.pressBack()
                pressedBack = true
                device.waitForIdle(800)
                continue
            }
            device.waitForIdle(400)
        }
        die("chat did not open after lock; on screen: ${visibleTexts()}")
    }

    /** New Chat stays on the list until the detail pane is actually showing the composer. */
    private fun openNewChat() {
        val entries = (context.applicationContext as com.openminis.app.MinisApp)
            .providerRepository.allVisibleEntries().size
        Log.i("K12E2E", "visible model entries=$entries")
        val deadline = System.currentTimeMillis() + 25_000
        while (System.currentTimeMillis() < deadline) {
            if (waitEdit(400) != null) return
            if (!clickDesc("New Chat", required = false)) {
                device.waitForIdle(400)
                continue
            }
            if (waitEdit(3_000) != null) return
            Log.w("K12E2E", "composer still closed; on screen: ${visibleTexts()}")
        }
        die("chat field missing; on screen: ${visibleTexts()}; entries=$entries")
    }

    private fun attachFixture() {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "e2e-q.png")
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(30, 90, 160))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val result = Intent().apply {
            data = uri
            clipData = ClipData.newRawUri("e2e-q.png", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val activityResult = Instrumentation.ActivityResult(Activity.RESULT_OK, result)
        runCatching { Intents.release() }
        Intents.init()
        listOf(
            Intent.ACTION_OPEN_DOCUMENT,
            Intent.ACTION_GET_CONTENT,
            "android.provider.action.PICK_IMAGES",
        ).forEach { action ->
            Intents.intending(IntentMatchers.hasAction(action)).respondWith(activityResult)
        }
        clickDesc("Attach")
        val photos = waitAny(listOf("Choose Photos & Videos", "选择照片和视频"), 8_000)
        if (photos != null) photos.click() else clickAnyText("Add File", "添加文件")
        device.waitForIdle(1_500)
    }

    /**
     * Activation navigates as soon as pairing is active. The model document
     * arrives on the sync that activation already started, so wait until that
     * cycle has stored this URL and the waiting form is gone.
     */
    private fun awaitAppliedModel(modelUrl: String) {
        val deadline = System.currentTimeMillis() + 90_000
        var last = "none"
        var status = K12ModelRuntime.pageStatus()
        while (System.currentTimeMillis() < deadline) {
            val sync = K12SyncRuntime.runOnce()
            last = sync::class.java.simpleName
            if (sync is com.openminis.k12.device.SyncRunResult.Stop) fail("sync did not apply: $sync")
            status = K12ModelRuntime.pageStatus()
            val ready = sync is com.openminis.k12.device.SyncRunResult.Ok &&
                status.managed &&
                status.baseUrl == modelUrl &&
                K12Runtime.snapshot().profile.pairing == "active"
            // The session list is composed after the sync result returns.
            if (ready && (chatSurfaceVisible() || waitUntilChatSurface(8_000))) return
            Thread.sleep(500)
        }
        if (status.managed &&
            status.baseUrl == modelUrl &&
            K12Runtime.snapshot().profile.pairing == "active" &&
            waitUntilChatSurface(8_000)
        ) {
            return
        }
        die(
            "tablet did not leave the waiting form on its own ($last) " +
                "managed=${status.managed} url=${status.baseUrl} " +
                "pairing=${K12Runtime.snapshot().profile.pairing} on screen: ${visibleTexts()}",
        )
    }

    /** Empty chat, the paired line, or New Chat. The waiting form has none of these. */
    private fun chatSurfaceVisible(): Boolean =
        runCatching { device.findObject(By.text(EMPTY_CHAT)) }.getOrNull() != null ||
            runCatching { device.findObject(By.text(PAIRED)) }.getOrNull() != null ||
            runCatching { device.findObject(By.desc("New Chat")) }.getOrNull() != null

    private fun waitUntilChatSurface(timeout: Long): Boolean {
        val until = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < until) {
            if (chatSurfaceVisible()) return true
            device.waitForIdle(300)
        }
        return false
    }

    private fun assertModelApplied(modelUrl: String) {
        val status = K12ModelRuntime.pageStatus()
        assertTrue(status.managed)
        assertEquals("openAI", status.providerType)
        assertEquals(MODEL_NAME, status.modelName)
        assertEquals(modelUrl, status.baseUrl)
        assertTrue(status.apiKeySet)
        assertTrue(status.version > 0L)
    }

    private fun modelStats(): JSONObject {
        val port = required("modelUrl").substringAfter("://").substringAfter(":").substringBefore("/")
        val connection = (URL("http://10.0.2.2:$port/e2e/stats").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        val code = connection.responseCode
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()
        if (code != 200) fail("fake model stats $code")
        return JSONObject(text)
    }

    private fun setField(label: String, value: String) {
        dismissAnr()
        closeKeyboard()
        swipeUntil(label)
        val labelNode = device.findObjects(By.text(label)).lastOrNull()
            ?: die("field label missing: $label")
        val target = editBelow(labelNode) ?: die("no edit for $label")
        target.click()
        device.waitForIdle(200)
        target.setText(value)
        device.waitForIdle(300)
        var shown = editBelow(labelNode)?.text.orEmpty()
        if (!shown.contains(value.take(4))) {
            val again = editBelow(labelNode) ?: die("no edit for $label")
            again.click()
            again.setText("")
            InstrumentationRegistry.getInstrumentation().sendStringSync(value)
            device.waitForIdle(300)
            shown = editBelow(labelNode)?.text.orEmpty()
        }
        if (!shown.contains(value.take(4))) die("field $label held '$shown'")
        closeKeyboard()
    }

    /** The field this label belongs to. The lowest edit on the form is a different field. */
    private fun editBelow(labelNode: UiObject2): UiObject2? = runCatching {
        val labelY = labelNode.visibleBounds.centerY()
        device.findObjects(By.clazz("android.widget.EditText"))
            .mapNotNull { edit ->
                val bounds = runCatching { edit.visibleBounds }.getOrNull() ?: return@mapNotNull null
                if (bounds.height() <= 0) null else edit to bounds.centerY()
            }
            .minByOrNull { kotlin.math.abs(it.second - labelY) }
            ?.first
    }.getOrNull()

    private fun chooseStage(label: String) {
        swipeUntil(label)
        val text = device.findObject(By.text(label)) ?: die("stage missing: $label")
        val radio = device.findObjects(By.checkable(true)).minByOrNull { candidate ->
            kotlin.math.abs(candidate.visibleBounds.centerY() - text.visibleBounds.centerY())
        }
        if (radio != null && kotlin.math.abs(radio.visibleBounds.centerY() - text.visibleBounds.centerY()) < 80) {
            radio.click()
        } else {
            text.click()
        }
        device.waitForIdle(400)
    }

    private fun clickText(text: String) {
        // Dialog and overlay buttons are already on screen. Scrolling first
        // moves the page underneath them.
        if (accessibilityActivate(text)) {
            device.waitForIdle(500)
            return
        }
        swipeUntil(text)
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            val node = runCatching { device.findObject(By.text(text)) }.getOrNull()
            val own = node?.let { runCatching { Rect(it.visibleBounds) }.getOrNull() }
            if (node != null && own != null && fullyInside(own)) {
                // Coordinate taps on API 30 land on the scrolling column.
                // ACTION_CLICK hits the button that owns the label. A coordinate
                // tap is only the fallback, and it uses the label or the
                // smallest clickable node around it, never the column center.
                if (!accessibilityActivate(text)) {
                    val bounds = smallestClickableBounds(node, own)
                    val x = bounds.centerX().coerceIn(1, device.displayWidth - 2)
                    val y = bounds.centerY().coerceIn(1, device.displayHeight - 2)
                    device.click(x, y)
                }
                device.waitForIdle(500)
                return
            }
            if (own != null) flick(revealLower = own.centerY() >= device.displayHeight / 2)
            device.waitForIdle(200)
        }
        die("not visible: $text; on screen: ${visibleTexts()}")
    }

    /** Fully inside the display, clear of the status bar and the bottom edge. */
    private fun fullyInside(bounds: Rect): Boolean =
        bounds.width() > 0 && bounds.height() > 0 &&
            bounds.top >= 8 && bounds.bottom <= device.displayHeight - 8 &&
            bounds.left >= 0 && bounds.right <= device.displayWidth

    /**
     * Smallest clickable, non-scrolling node whose bounds contain the label.
     * A full-page scroller is many times the label; the label center is on the button.
     */
    private fun smallestClickableBounds(node: UiObject2, own: Rect): Rect {
        var best: Rect? = null
        var bestArea = Int.MAX_VALUE
        var current: UiObject2? = node
        var guard = 0
        while (current != null && guard++ < 8) {
            val here = current
            val clickable = runCatching { here.isClickable }.getOrDefault(false)
            val scrollable = runCatching { here.isScrollable }.getOrDefault(false)
            val bounds = runCatching { Rect(here.visibleBounds) }.getOrNull()
            if (clickable && !scrollable && bounds != null && bounds.contains(own.centerX(), own.centerY())) {
                val area = bounds.width() * bounds.height()
                if (area in 1 until bestArea) {
                    best = bounds
                    bestArea = area
                }
            }
            current = runCatching { here.parent }.getOrNull()
        }
        val chosen = best ?: return Rect(own)
        val ownArea = (own.width() * own.height()).coerceAtLeast(1)
        if (chosen.width() * chosen.height() > ownArea * 12) return Rect(own)
        return chosen
    }

    /** ACTION_CLICK on the button that contains this label, not on the scroller. */
    private fun accessibilityActivate(text: String): Boolean {
        val roots = windowRoots()
        if (roots.isEmpty()) return false
        return try {
            for (root in roots) {
                val found = ArrayList<AccessibilityNodeInfo>()
                collectExactText(root, text, found)
                val label = found.firstOrNull { it.isVisibleToUser } ?: continue
                val labelBounds = Rect()
                label.getBoundsInScreen(labelBounds)
                if (labelBounds.isEmpty) continue
                var best: AccessibilityNodeInfo? = null
                var bestArea = Int.MAX_VALUE
                var current: AccessibilityNodeInfo? = label
                var guard = 0
                val labelArea = (labelBounds.width() * labelBounds.height()).coerceAtLeast(1)
                while (current != null && guard++ < 8) {
                    if (current.isClickable && !current.isScrollable) {
                        val bounds = Rect()
                        current.getBoundsInScreen(bounds)
                        val area = bounds.width() * bounds.height()
                        if (bounds.contains(labelBounds.centerX(), labelBounds.centerY()) &&
                            area in 1..labelArea * 12 &&
                            area < bestArea
                        ) {
                            best = current
                            bestArea = area
                        }
                    }
                    current = current.parent
                }
                if (best?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
            }
            false
        } catch (_: IllegalStateException) {
            false
        } finally {
            roots.forEach { runCatching { it.recycle() } }
        }
    }

    private fun windowRoots(): List<AccessibilityNodeInfo> {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val windows = runCatching { automation.windows }.getOrNull().orEmpty()
        val roots = windows.mapNotNull { runCatching { it.root }.getOrNull() }
        if (roots.isNotEmpty()) return roots
        return listOfNotNull(runCatching { automation.rootInActiveWindow }.getOrNull())
    }

    private fun textInAnyWindow(text: String): Boolean {
        val roots = windowRoots()
        return try {
            roots.any { root ->
                val found = ArrayList<AccessibilityNodeInfo>()
                collectExactText(root, text, found)
                found.any { it.isVisibleToUser }
            }
        } finally {
            roots.forEach { runCatching { it.recycle() } }
        }
    }

    private fun awaitVisible(text: String, timeout: Long, why: String) {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            if (device.findObject(By.text(text)) != null || textInAnyWindow(text)) return
            device.waitForIdle(400)
        }
        val step = K12SafetyRuntime.screen.value?.step
        val reached = runCatching { modelStats().optBoolean("crisis_reached_model") }.getOrNull()
        die(
            "$why; unlocked=${K12Runtime.parentUnlocked()} step=$step " +
                "hotlineSet=${!K12SafetyRuntime.hotlineText.isNullOrBlank()} " +
                "modelSawCrisis=$reached on screen: ${visibleTexts()}",
        )
    }

    private fun collectExactText(node: AccessibilityNodeInfo, text: String, out: MutableList<AccessibilityNodeInfo>) {
        if (node.text?.toString() == text) out.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectExactText(child, text, out)
        }
    }

    private fun clickDescAny(vararg descs: String) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            for (desc in descs) {
                val node = device.findObject(By.desc(desc))
                if (node != null && node.visibleBounds.height() > 0) {
                    clickableOf(node).click()
                    device.waitForIdle(700)
                    return
                }
            }
            device.waitForIdle(400)
        }
        die("missing desc ${descs.joinToString()}")
    }

    private fun clickAnyText(vararg texts: String) {
        for (text in texts) {
            val node = device.findObject(By.text(text))
            if (node != null) {
                node.click()
                device.waitForIdle(500)
                return
            }
        }
        die("not visible: ${texts.joinToString()}")
    }

    private fun clickDesc(desc: String, required: Boolean = true, timeoutMs: Long = 20_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            // A gesture click on New Chat is claimed by the button's
            // drag-to-swap handler, so the accessibility click action is first.
            if (accessibilityClick(desc)) {
                device.waitForIdle(700)
                return true
            }
            val node = device.findObject(By.desc(desc))
            if (node != null && node.visibleBounds.height() > 0) {
                val bounds = clickableOf(node).visibleBounds
                device.click(bounds.centerX(), bounds.top + bounds.height() / 3)
                device.waitForIdle(700)
                return true
            }
            device.waitForIdle(300)
        }
        if (required) die("missing desc $desc; on screen: ${visibleTexts()}")
        return false
    }

    private fun accessibilityClick(desc: String): Boolean {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return false
        return try {
            val found = ArrayList<AccessibilityNodeInfo>()
            collectDesc(root, desc, found)
            val node = found.firstOrNull { it.isVisibleToUser } ?: return false
            clickNode(node)
        } finally {
            root.recycle()
        }
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var guard = 0
        while (current != null && guard++ < 8 && !current.isClickable) {
            current = current.parent
        }
        val target = current ?: return false
        if (!target.isClickable || (target.isScrollable && target !== node)) return false
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun collectDesc(node: AccessibilityNodeInfo, desc: String, out: MutableList<AccessibilityNodeInfo>) {
        if (node.contentDescription?.toString() == desc) out.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectDesc(child, desc, out)
        }
    }

    /** Texts in this app. Leaves out the status bar, whose clock changes every minute. */
    private fun appTexts(): String = collectTexts(appOnly = true)

    private fun visibleTexts(): String = collectTexts(appOnly = false)

    private fun collectTexts(appOnly: Boolean): String {
        val roots = windowRoots()
        val texts = ArrayList<String>()
        fun walk(node: AccessibilityNodeInfo) {
            if (appOnly && node.packageName?.toString() != context.packageName) return
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            if (!text.isNullOrBlank()) texts.add(text) else if (!desc.isNullOrBlank()) texts.add("[$desc]")
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child)
            }
        }
        return try {
            roots.forEach { walk(it) }
            texts.filterNot { com.openminis.k12.safety.ContentSafetyGuide.isCrisis(it) }
                .distinct()
                .take(if (appOnly) 40 else 16)
                .joinToString(" | ")
        } finally {
            roots.forEach { runCatching { it.recycle() } }
        }
    }

    /** The icon that carries "New Chat" is not itself clickable. The button around it is. */
    private fun clickableOf(node: UiObject2): UiObject2 {
        var current: UiObject2? = node
        var guard = 0
        while (current != null && guard++ < 6) {
            if (current.isClickable) return current
            current = runCatching { current.parent }.getOrNull()
        }
        return node
    }

    private fun waitText(text: String, timeout: Long): UiObject2? {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            device.findObject(By.text(text))?.let { return it }
            device.waitForIdle(400)
        }
        return null
    }

    private fun waitContains(text: String, timeout: Long): UiObject2? {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            device.findObject(By.textContains(text))?.let { return it }
            device.waitForIdle(500)
        }
        return null
    }

    private fun waitStarts(prefix: String, timeout: Long): UiObject2? =
        device.wait(Until.findObject(By.textStartsWith(prefix)), timeout)

    private fun waitAny(texts: List<String>, timeout: Long): UiObject2? {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            for (text in texts) {
                device.findObject(By.text(text))?.let { return it }
                device.findObject(By.desc(text))?.let { return it }
            }
            device.waitForIdle(500)
        }
        return null
    }

    private fun waitEdit(timeout: Long): UiObject2? {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            val found = runCatching { findComposer() }.getOrNull()
            if (found != null) return found
            device.waitForIdle(400)
        }
        return null
    }

    private fun findComposer(): UiObject2? {
        device.findObject(By.res(context.packageName, "k12_chat_composer"))
            ?.takeIf { visible(it) }
            ?.let { return it }
        device.findObjects(By.clazz("android.widget.EditText"))
            .lastOrNull { visible(it) }
            ?.let { return it }
        for (hint in listOf("Message", "发消息")) {
            device.findObject(By.textContains(hint))?.takeIf { visible(it) }?.let { return it }
        }
        return null
    }

    private fun visible(node: UiObject2): Boolean =
        runCatching { node.visibleBounds.height() > 0 }.getOrDefault(false)

    /**
     * The CI emulator is 320×640. A form taller than that puts the action
     * below the fold, and focusing the last field can scroll it off the top.
     * Swipes stay in the screen padding so a text field does not eat them.
     * A center fling is not used: it lands on the fields and moves the
     * target off screen.
     */
    private fun swipeUntil(text: String) {
        dismissAnr()
        closeKeyboard()
        if (onScreen(text)) return
        repeat(10) {
            flick(revealLower = true)
            if (onScreen(text)) return
        }
        repeat(10) {
            flick(revealLower = false)
            if (onScreen(text)) return
        }
    }

    private fun dismissAnr() {
        val close = device.findObject(By.text("Close app")) ?: return
        close.click()
        device.waitForIdle(400)
    }

    private fun onScreen(text: String): Boolean {
        val node = runCatching { device.findObject(By.text(text)) }.getOrNull() ?: return false
        val bounds = runCatching { node.visibleBounds }.getOrNull() ?: return false
        return fullyInside(bounds)
    }

    private fun flick(revealLower: Boolean) {
        // A finger swipe from the left edge is the system back gesture, which
        // leaves the parent screen. An accessibility scroll that reports
        // success without moving the parent column (a single-line field is
        // also scrollable) must not hide the real swipe.
        // App text only. The status-bar clock and other windows change between
        // reads, and treating that as a scroll skips the swipe that reaches
        // 锁定并离开.
        val before = runCatching { appTexts() }.getOrDefault("")
        if (accessibilityScroll(revealLower)) {
            device.waitForIdle(250)
            if (appTexts() != before) return
        }
        val scroller = runCatching {
            device.findObjects(By.scrollable(true)).maxByOrNull { node ->
                runCatching { node.visibleBounds.height() }.getOrDefault(0)
            }
        }.getOrNull()
        val moved = runCatching {
            scroller?.scroll(if (revealLower) Direction.UP else Direction.DOWN, 0.55f) == true
        }.getOrDefault(false)
        device.waitForIdle(250)
        if (moved && appTexts() != before) return
        val bounds = runCatching { scroller?.visibleBounds }.getOrNull()
        val x = (bounds?.centerX() ?: (device.displayWidth / 2)).coerceIn(72, device.displayWidth - 24)
        val top = (bounds?.top ?: 0) + 48
        val bottom = (bounds?.bottom ?: device.displayHeight) - 48
        if (bottom - top < 80) return
        val travel = ((bottom - top) * 0.45f).toInt().coerceAtLeast(80)
        if (revealLower) device.swipe(x, top + travel, x, top, 30) else device.swipe(x, top, x, top + travel, 30)
        device.waitForIdle(300)
    }

    private fun accessibilityScroll(revealLower: Boolean): Boolean {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return false
        return try {
            val node = findScrollable(root) ?: return false
            val action = if (revealLower) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            node.performAction(action)
        } finally {
            root.recycle()
        }
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollable(child)
            if (found != null) return found
        }
        return null
    }

    private fun closeKeyboard() {
        runCatching { Espresso.closeSoftKeyboard() }
        val dump = device.executeShellCommand("dumpsys input_method")
        val shown = dump.contains("mInputShown=true") || Regex("mImeWindowVis=[1-9]").containsMatchIn(dump)
        if (shown) {
            device.pressKeyCode(android.view.KeyEvent.KEYCODE_ESCAPE)
            device.waitForIdle(400)
        }
    }

    private fun shot(name: String) {
        val dir = File(context.filesDir, "e2e-shots").apply { mkdirs() }
        val file = File(dir, "$name.png")
        device.takeScreenshot(file)
        File(dir, "index.txt").appendText("$name.png\n")
    }

    private fun admin(): Admin = Admin(required("adminUrl")).also { it.login(required("adminUser"), required("adminPass")) }

    private fun die(message: String): Nothing {
        shot("fail")
        throw AssertionError(message)
    }

    private fun required(name: String): String {
        val value = args.getString(name)?.trim().orEmpty()
        if (value.isEmpty()) fail("instrumentation argument $name is required")
        return value
    }

    private class Admin(baseUrl: String) {
        private val baseUrl = baseUrl.trimEnd('/')
        private var cookie: String? = null
        private var csrf: String? = null

        fun login(username: String, password: String) {
            val body = JSONObject().put("username", username).put("password", password).toString()
            val response = call("POST", "/api/v1/admin/login", body, withCsrf = false)
            if (response.code != 204 && response.code != 200) fail("admin login ${response.code}")
        }

        fun pairCode(): String {
            val response = call("POST", "/api/v1/admin/pair-codes", "{}", withCsrf = true)
            if (response.code != 200) fail("pair code ${response.code}")
            val code = JSONObject(response.body).optString("pair_code")
            if (code.length != 6) fail("pair code missing")
            return code
        }

        fun approve(deviceId: String) {
            val response = call("POST", "/api/v1/admin/devices/$deviceId/approve", "", withCsrf = true)
            if (response.code != 204 && response.code != 200) fail("approve ${response.code}")
        }

        fun putTabletModel(modelUrl: String) {
            val body = JSONObject()
                .put("provider_type", "openAI")
                .put("base_url", modelUrl)
                .put("model_name", MODEL_NAME)
                .put("vision_model_name", "")
                .put("api_key", "e2e-placeholder-key")
                .toString()
            val response = call("PUT", "/api/v1/admin/tablet-model", body, withCsrf = true)
            if (response.code != 200 && response.code != 204) fail("tablet-model ${response.code}")
        }

        fun putChildConfig(childId: String, fields: JSONObject) {
            val response = call("PUT", "/api/v1/admin/children/$childId/config", fields.toString(), withCsrf = true)
            if (response.code != 200) fail("child config ${response.code}")
        }

        fun deviceRow(deviceId: String): JSONObject {
            val response = call("GET", "/api/v1/admin/devices", "", withCsrf = false)
            if (response.code != 200) fail("devices ${response.code}")
            val rows = JSONArray(response.body)
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                if (row.optString("id") == deviceId) return row
            }
            fail("device $deviceId not listed")
            return JSONObject()
        }

        fun generateReport(childId: String): JSONObject {
            val body = JSONObject().put("child_id", childId).toString()
            val response = call("POST", "/api/v1/admin/reports/generate", body, withCsrf = true)
            if (response.code != 200) fail("report ${response.code}")
            return JSONObject(response.body)
        }

        fun get(path: String): HttpResult = call("GET", path, "", withCsrf = false)

        private fun call(method: String, path: String, body: String, withCsrf: Boolean): HttpResult {
            val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Accept", "application/json")
                if (method != "GET") setRequestProperty("Content-Type", "application/json")
                cookie?.let { setRequestProperty("Cookie", it) }
                if (withCsrf) csrf?.let { setRequestProperty("X-CSRF-Token", it) }
                if (method != "GET") {
                    doOutput = true
                    outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
            }
            val code = connection.responseCode
            connection.getHeaderField("X-CSRF-Token")?.takeIf { it.isNotBlank() }?.let { csrf = it }
            val setCookies = connection.headerFields.entries
                .firstOrNull { it.key.equals("Set-Cookie", ignoreCase = true) }
                ?.value
                .orEmpty()
            for (raw in setCookies) {
                val pair = raw.substringBefore(';').trim()
                if (pair.startsWith("k12_session=")) cookie = pair
            }
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            return HttpResult(code, text)
        }
    }

    private data class HttpResult(val code: Int, val body: String)

    companion object {
        private const val TAG = "K12LearningE2E"
        private const val MODEL_NAME = "k12-e2e-model"
        private const val ONBOARDING = "第一次设置"
        private const val SENIOR = "高中"
        private const val SENIOR_NOTE = "高中只作预留。这一版不提供高中讲法。"
        private const val JUNIOR = "初中"
        private const val NEXT = "下一步"
        private const val ID_TITLE = "这台平板的编号"
        private const val SECRETS = "家长口令"
        private const val PIN = "PIN（4 到 8 位数字）"
        private const val PIN_AGAIN = "再输入一次 PIN"
        private const val BACKUP = "备份口令（至少 8 位）"
        private const val BACKUP_AGAIN = "再输入一次备份口令"
        private const val PERM_LATER = "接下来可以绑定家长服务器。这一步可以跳过，以后在家长区再做。"
        private const val PAIR_TITLE = "绑定家长服务器"
        private const val URL_LABEL = "服务器地址（https）"
        private const val CODE_LABEL = "6 位配对码"
        private const val BIND = "绑定"
        private const val WAIT_TITLE = "已提交，等家长在管理后台/企微确认…"
        private const val EMPTY_CHAT = "还没有对话。点下面的按钮开始。"
        private const val WAIT_MODEL = "正在等待讲题模型"
        private const val PAIRED = "已绑定家长服务器"
        private const val MATH_ANSWER = "算式的结果是 4"
        private const val FOLLOW = "下一步：把两个 2 相加。"
        private const val SOCRATIC = "想一想，这一步可以怎么做？"
        private const val STEM = "题干：2+2。请确认。"
        private const val SKILL = "讲题技能已随应用安装"
        private const val REVIEW = "待复习"
        private const val LINKED = "错题已关联知识点"
        private const val PERM_TITLE = "权限自检"
        private const val PERM_REFRESH = "重新检查"
        private const val LATER_UNLOCK = "解锁兜底"
        private const val PARENT = "家长"
        private const val PIN_TITLE = "家长 PIN"
        private const val PIN_OK = "确认"
        private const val LOCK_LEAVE = "锁定并离开"
        private const val TELL_ADULT = "请马上告诉身边的大人。"
        private const val DECLINE = "不想说"
        private const val KNOWN = "知道了"
        private const val LOCK_TITLE = "今天的时长用完了"
        private const val EXTEND = "请家长加时"
        private const val CRISIS_A = "自杀"
        private const val CRISIS_B = "自残"
    }
}
