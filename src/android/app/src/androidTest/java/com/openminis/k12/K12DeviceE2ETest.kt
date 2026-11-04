package com.openminis.k12

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.openminis.k12.android.DeviceSigningKey
import com.openminis.k12.android.K12PairingRuntime
import com.openminis.k12.android.K12Runtime
import com.openminis.k12.android.K12SyncRuntime
import com.openminis.k12.device.Ed25519DeviceKey
import com.openminis.k12.model.K12ModelRuntime
import com.openminis.k12.device.PairingClient
import com.openminis.k12.device.PairingOutcome
import com.openminis.k12.device.SyncRunResult
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Device key, pairing, model delivery, and dark-mode onboarding on a real
 * Android runtime. The workflow passes the runner's admin API and a one-time
 * pair code. Nothing here logs key bytes or the admin password.
 */
@RunWith(AndroidJUnit4::class)
class K12DeviceE2ETest {
    private val args = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun allowEmulatorHttp() {
        PairingClient.allowCleartextForTests = true
    }

    @After
    fun restoreHttpsOnly() {
        PairingClient.allowCleartextForTests = false
    }

    @Test
    fun keystorePairModelAndDarkOnboarding() {
        val baseUrl = required("baseUrl")
        val adminUrl = required("adminUrl")
        val adminUser = required("adminUser")
        val adminPass = required("adminPass")
        val pairCode = required("pairCode")
        assertTrue("K12 runtime did not install", K12Runtime.installed)

        DeviceSigningKey.delete(context)
        val opened = DeviceSigningKey.open(context)
        if (opened == null) {
            fail("DeviceSigningKey.open returned null on sdk=${android.os.Build.VERSION.SDK_INT}")
        }
        val publicKey = android.util.Base64.decode(opened!!.publicKeyBase64, android.util.Base64.DEFAULT)
        assertEquals(Ed25519DeviceKey.PUBLIC_LENGTH, publicKey.size)
        Log.i(TAG, "device key ready sdk=${android.os.Build.VERSION.SDK_INT}")

        val childId = UUID.randomUUID().toString()
        val submitted = K12PairingRuntime.submit(context, baseUrl, pairCode, childId, null)
        val pending = when (submitted) {
            is PairingOutcome.Pending -> submitted
            is PairingOutcome.Active -> {
                deliverModel(adminUrl, adminUser, adminPass, submitted.endpoint.deviceId)
                assertModelApplied()
                captureDarkOnboarding()
                return
            }
            is PairingOutcome.Failed -> fail("pair failed: ${submitted.message}")
        }
        assertTrue(pending is PairingOutcome.Pending)
        val waiting = pending as PairingOutcome.Pending
        deliverModel(adminUrl, adminUser, adminPass, waiting.deviceId)
        val refreshed = K12PairingRuntime.refresh(context, waiting)
        if (refreshed !is PairingOutcome.Active) {
            fail("pair status did not become active: $refreshed")
        }
        val sync = K12SyncRuntime.runOnce()
        Log.i(TAG, "sync result ${sync::class.java.simpleName}")
        if (sync !is SyncRunResult.Ok) fail("sync did not apply: $sync")
        assertModelApplied()
        captureDarkOnboarding()
    }

    private fun deliverModel(adminUrl: String, user: String, pass: String, deviceId: String) {
        val admin = AdminClient(adminUrl)
        admin.login(user, pass)
        admin.approve(deviceId)
        admin.putTabletModel()
    }

    private fun assertModelApplied() {
        val status = K12ModelRuntime.pageStatus()
        Log.i(TAG, "model page ${status}")
        assertTrue(status.managed)
        assertEquals("openAI", status.providerType)
        assertEquals(MODEL_NAME, status.modelName)
        assertEquals(MODEL_URL, status.baseUrl)
        assertTrue(status.apiKeySet)
        assertTrue(status.version > 0L)
        File(context.filesDir, "k12-e2e.txt").writeText(
            "sdk=${android.os.Build.VERSION.SDK_INT} model=${status.modelName} version=${status.version}\n",
        )
    }

    private fun captureDarkOnboarding() {
        context.getSharedPreferences("appearance_prefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .putInt("theme_mode", 2)
            .commit()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand("cmd uimode night yes")
        val intent = Intent().setClassName(context.packageName, "com.openminis.app.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        val title = device.wait(Until.findObject(By.text(ONBOARDING)), 30_000)
        val shot = File(context.filesDir, "k12-onboarding-dark.png")
        device.takeScreenshot(shot)
        assertNotNull("onboarding title was not visible", title)
        assertFalse(title.visibleBounds.isEmpty)
        val bitmap = BitmapFactory.decodeFile(shot.absolutePath)
        assertNotNull(bitmap)
        val bounds = title.visibleBounds
        var min = 255
        var max = 0
        var y = bounds.top
        while (y < bounds.bottom) {
            var x = bounds.left
            while (x < bounds.right) {
                if (x in 0 until bitmap.width && y in 0 until bitmap.height) {
                    val pixel = bitmap.getPixel(x, y)
                    val lum = (Color.red(pixel) * 3 + Color.green(pixel) * 6 + Color.blue(pixel)) / 10
                    if (lum < min) min = lum
                    if (lum > max) max = lum
                }
                x += 2
            }
            y += 2
        }
        Log.i(TAG, "onboarding contrast min=$min max=$max")
        assertTrue("dark-mode onboarding text has no contrast min=$min max=$max", max - min >= 40)
    }

    private fun required(name: String): String {
        val value = args.getString(name)?.trim().orEmpty()
        if (value.isEmpty()) fail("instrumentation argument $name is required")
        return value
    }

    private class AdminClient(baseUrl: String) {
        private val baseUrl = baseUrl.trimEnd('/')
        private var cookie: String? = null
        private var csrf: String? = null

        fun login(username: String, password: String) {
            val body = JSONObject().put("username", username).put("password", password).toString()
            val response = call("POST", "/api/v1/admin/login", body, withCsrf = false)
            if (response.code != 204 && response.code != 200) {
                fail("admin login failed: ${response.code}")
            }
            if (cookie.isNullOrBlank() || csrf.isNullOrBlank()) fail("admin login did not return a session")
        }

        fun approve(deviceId: String) {
            val response = call("POST", "/api/v1/admin/devices/$deviceId/approve", "", withCsrf = true)
            if (response.code != 204 && response.code != 200) {
                fail("approve failed: ${response.code}")
            }
        }

        fun putTabletModel() {
            val body = JSONObject()
                .put("provider_type", "openAI")
                .put("base_url", MODEL_URL)
                .put("model_name", MODEL_NAME)
                .put("vision_model_name", "")
                .put("api_key", "e2e-placeholder-key")
                .toString()
            val response = call("PUT", "/api/v1/admin/tablet-model", body, withCsrf = true)
            if (response.code != 200 && response.code != 204) {
                fail("tablet-model push failed: ${response.code}")
            }
        }

        private fun call(method: String, path: String, body: String, withCsrf: Boolean): HttpResult {
            val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
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
        private const val TAG = "K12DeviceKey"
        private const val ONBOARDING = "第一次设置"
        private const val MODEL_NAME = "k12-e2e-model"
        private const val MODEL_URL = "https://k12.example.com/v1"
    }
}
