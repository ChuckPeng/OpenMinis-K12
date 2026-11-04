package com.openminis.k12

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.openminis.k12.device.DevicePairing
import com.openminis.k12.device.DeviceSigner
import com.openminis.k12.device.DeviceStatus
import com.openminis.k12.device.HttpResponse
import com.openminis.k12.device.HttpTransport
import com.openminis.k12.device.PairingClient
import com.openminis.k12.device.PairingOutcome
import com.openminis.k12.device.PairingText
import com.openminis.k12.device.QrCodes
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

/**
 * Pairing client and the post-onboarding route. Placeholder host only.
 */
class K12PairingAcceptanceTest {
    private val childId = "11111111-1111-4111-8111-111111111111"
    private val pubkey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    @Test
    fun manualHttpsPairThenStatusActiveNotifiesTheHook() {
        var activated = false
        DevicePairing.onActivated = { endpoint ->
            activated = endpoint.baseUrl == "https://k12.example.com" && endpoint.deviceId == "dev-1"
        }
        var statusCalls = 0
        val client = client { method, url, body ->
            when {
                method == "POST" && url.endsWith("/api/v1/device/pair") -> {
                    val json = JSONObject(String(body ?: ByteArray(0), Charsets.UTF_8))
                    assertEquals("123456", json.getString("pair_code"))
                    assertEquals(pubkey, json.getString("device_pubkey"))
                    assertEquals(childId, json.getString("child_id"))
                    assertEquals(PairingText.DEVICE_LABEL, json.getString("label"))
                    assertFalse(json.toString().contains("example.org"))
                    jsonBody("""{"device_id":"dev-1","status":"pending_confirm"}""")
                }
                method == "GET" && url.contains("/api/v1/device/pair/status") -> {
                    statusCalls++
                    assertTrue(url.contains("wait=0"))
                    jsonBody("""{"status":"active","changed":true}""")
                }
                else -> HttpResponse(404, emptyMap(), ByteArray(0))
            }
        }
        val outcome = client.submit("https://k12.example.com/", "123456", childId)
        assertTrue(outcome is PairingOutcome.Active)
        val active = outcome as PairingOutcome.Active
        assertEquals("k12.example.com", PairingText.hostOf(active.endpoint.baseUrl))
        DevicePairing.noteActivated(active.endpoint, DeviceStatus.ACTIVE)
        assertTrue(activated)
        assertEquals(1, statusCalls)
        DevicePairing.onActivated = null
    }

    @Test
    fun invalidExpiredCodeNetworkAndTlsUseChineseMessages() {
        val invalid = client { _, _, _ ->
            HttpResponse(401, emptyMap(), """{"error":"invalid_pair_code","message":"nope"}""".toByteArray())
        }
        val badCode = invalid.submit("https://k12.example.com", "000000", childId)
        assertEquals(PairingText.INVALID_CODE, (badCode as PairingOutcome.Failed).message)

        val down = client { _, _, _ -> throw SocketTimeoutException("timed out") }
        val network = down.submit("https://k12.example.com", "123456", childId)
        assertEquals(PairingText.NETWORK, (network as PairingOutcome.Failed).message)

        val tls = client { _, _, _ -> throw SSLHandshakeException("certificate") }
        val pinned = tls.submit("https://k12.example.com", "123456", childId)
        assertEquals(PairingText.TLS, (pinned as PairingOutcome.Failed).message)

        val http = client { _, _, _ -> jsonBody("{}") }
        assertEquals(PairingText.BAD_URL, (http.submit("http://k12.example.com", "123456", childId) as PairingOutcome.Failed).message)
        assertEquals(PairingText.BAD_CODE, (http.submit("https://k12.example.com", "12", childId) as PairingOutcome.Failed).message)
    }

    @Test
    fun cleartextHttpIsOnlyAcceptedForADebugTestFlag() {
        PairingClient.allowCleartextForTests = false
        assertEquals(null, PairingClient.normalizeBaseUrl("http://10.0.2.2:18080"))
        PairingClient.allowCleartextForTests = true
        try {
            val normalized = PairingClient.normalizeBaseUrl("http://10.0.2.2:18080")
            if (com.openminis.app.BuildConfig.DEBUG) {
                assertEquals("http://10.0.2.2:18080", normalized)
            } else {
                assertEquals(null, normalized)
            }
            assertEquals(null, PairingClient.normalizeBaseUrl("http://user:pass@10.0.2.2:18080"))
            assertEquals("https://k12.example.com", PairingClient.normalizeBaseUrl("https://k12.example.com"))
        } finally {
            PairingClient.allowCleartextForTests = false
        }
    }

    @Test
    fun pendingConfirmDoesNotActivate() {
        var activated = false
        DevicePairing.onActivated = { activated = true }
        val client = client { method, url, _ ->
            if (method == "POST") jsonBody("""{"device_id":"dev-9","status":"pending_confirm"}""")
            else jsonBody("""{"status":"pending_confirm","changed":false}""")
        }
        val outcome = client.submit("https://k12.example.com", "654321", childId)
        assertTrue(outcome is PairingOutcome.Pending)
        assertFalse(activated)
        val refreshed = client.poll(outcome as PairingOutcome.Pending)
        assertTrue(refreshed is PairingOutcome.Pending)
        assertFalse(activated)
        var waited = false
        val longPoll = client { _, url, _ ->
            waited = url.contains("wait=10")
            jsonBody("""{"status":"active","changed":true}""")
        }
        val active = longPoll.poll(outcome as PairingOutcome.Pending, waitSeconds = 10)
        assertTrue(waited)
        assertTrue(active is PairingOutcome.Active)
        DevicePairing.onActivated = null
    }

    @Test
    fun qrPayloadFromTheAdminPageParsesUrlAndCode() {
        val json = """{"public_base_url":"https://k12.example.com","base_url":"https://k12.example.com","pair_code":"123456"}"""
        val parsed = DevicePairing.parseQr(json)
        assertEquals("https://k12.example.com", parsed?.baseUrl)
        assertEquals("123456", parsed?.pairCode)
        val link = "k12://pair?url=https%3A%2F%2Fk12.example.com&code=654321"
        val fromLink = DevicePairing.parseQr(link)
        assertEquals("https://k12.example.com", fromLink?.baseUrl)
        assertEquals("654321", fromLink?.pairCode)
        val matrix = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 240, 240)
        val text = QrCodes.decodeMatrix(matrix)
        assertEquals(link, text)
        val admin = repoFile("server/web/src/App.vue").readText()
        assertTrue(admin.contains("配对二维码"))
        assertTrue(admin.contains("qrcode"))
        val handler = repoFile("server/internal/admin/m5b_handlers.go").readText()
        assertTrue(handler.contains("public_base_url"))
        assertTrue(handler.contains("base_url"))
    }

    @Test
    fun unpairedTabletDoesNotOpenChatUntilAModelExists() {
        assertEquals(
            StudyEntry.NEED_PAIRING,
            StudyEntry.destination(onboardingComplete = true, paired = false, chatReady = false, studyLocked = false),
        )
        assertEquals(
            StudyEntry.WAITING,
            StudyEntry.destination(onboardingComplete = true, paired = true, chatReady = false, studyLocked = false),
        )
        assertEquals(
            StudyEntry.SESSIONS,
            StudyEntry.destination(onboardingComplete = true, paired = true, chatReady = true, studyLocked = false),
        )
        assertTrue(RoutePolicy.blocked("sessions", true, false, false, chatReady = false, paired = false))
        assertTrue(RoutePolicy.blocked("chat/abc", true, false, false, chatReady = false, paired = true))
        assertFalse(RoutePolicy.blocked(StudyEntry.WAITING, true, false, false, chatReady = false, paired = true))
        assertFalse(RoutePolicy.blocked("sessions", true, false, false, chatReady = true, paired = true))
        assertTrue(RoutePolicy.blocked("sessions", true, false, studyLocked = true, chatReady = true, paired = true))
        assertFalse(RoutePolicy.blocked(StudyEntry.PARENT_GATE_PATH, true, false, false, chatReady = false, paired = false))
        val screens = repoFile("src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListScreen.kt").readText()
        assertTrue(screens.contains("K12BeforeChatScreen"))
        assertTrue(screens.contains("k12_chat_empty"))
        val nav = repoFile("src/android/app/src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt").readText()
        assertFalse(nav.contains("K12ChildHomeScreen"))
        assertTrue(nav.contains("StudyEntry.SESSIONS"))
        val theme = repoFile("src/android/app/src/main/java/com/openminis/app/ui/theme/Theme.kt").readText()
        assertTrue(theme.contains("LocalContentColor provides colorScheme.onBackground"))
        assertTrue(theme.contains("LocalContentColor provides if (darkTheme)"))
        val workflow = repoFile(".github/workflows/android.yml").readText()
        assertTrue(workflow.contains("K12PairingAcceptanceTest"))
    }

    private fun client(handler: (String, String, ByteArray?) -> HttpResponse): PairingClient {
        val transport = object : HttpTransport {
            override fun execute(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
                assertFalse(url.startsWith("http://"))
                return handler(method, url, body)
            }
        }
        return PairingClient(transport, DeviceSigner.MockSigner("k"), pubkey)
    }

    private fun jsonBody(text: String) = HttpResponse(200, emptyMap(), text.toByteArray())

    private fun repoFile(name: String): File = listOf(
        File(name),
        File("..", name),
        File("../..", name),
        File("../../..", name),
    ).first { it.isFile }
}
