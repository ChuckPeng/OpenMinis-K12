package com.openminis.k12

import com.openminis.k12.backup.DeviceBackupManager
import com.openminis.k12.device.DevicePairing
import com.openminis.k12.device.DeviceSigner
import com.openminis.k12.device.DeviceStatus
import com.openminis.k12.device.HttpTransport
import com.openminis.k12.device.HttpResponse
import com.openminis.k12.device.PairingPayload
import com.openminis.k12.device.SyncClient
import com.openminis.k12.device.SyncConfig
import com.openminis.k12.safety.AlertLevel
import com.openminis.k12.safety.ContentSafetyGuide
import com.openminis.k12.safety.EmergencyStep
import com.openminis.k12.safety.SafetyAlert
import com.openminis.k12.tutoring.Grant
import com.openminis.k12.tutoring.OfflineBackupCode
import com.openminis.k12.tutoring.RemoteUnlock
import com.openminis.k12.tutoring.RevealAuth
import com.openminis.k12.tutoring.RevealLayer
import com.openminis.k12.tutoring.RevealMethod
import com.openminis.k12.tutoring.TutoringAction
import com.openminis.k12.tutoring.TutoringMachine
import com.openminis.k12.tutoring.TutoringState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M5b Acceptance Test Suite for Android client components (WP-B-1 to WP-B-8).
 * Pure synthetic test fixtures only — strictly no family PII.
 */
class K12M5bAcceptanceTest {

    @Test
    fun versionAndChangelogAreM5b() {
        assertEquals("1.0.0-m5b", Version.NAME)
        assertEquals(1_000_006, Version.CODE)
        assertTrue(Version.matchesCodeFormula())
        assertEquals("v1.0.0-m5b", Version.MILESTONE_TAG)

        val changelog = repoFile("CHANGELOG.md").readText()
        assertTrue(changelog.contains("## [1.0.0-m5b]"))
    }

    // --- WP-B-1: Pairing & Device Signer (J1, J9, J11) ---

    @Test
    fun wpB1_qrCodeParsingJsonAndUri() {
        val original = PairingPayload(
            baseUrl = "https://k12.example.com",
            certFingerprint = "AA:BB:CC:DD:EE:FF",
            serverPubkey = "server-pubkey-base64",
            pairCode = "123456",
        )

        val jsonStr = DevicePairing.formatQrJson(original)
        val parsedFromJson = DevicePairing.parseQr(jsonStr)
        assertNotNull(parsedFromJson)
        assertEquals(original.baseUrl, parsedFromJson?.baseUrl)
        assertEquals(original.certFingerprint, parsedFromJson?.certFingerprint)
        assertEquals(original.serverPubkey, parsedFromJson?.serverPubkey)
        assertEquals(original.pairCode, parsedFromJson?.pairCode)

        val uriStr = DevicePairing.formatQrUri(original)
        val parsedFromUri = DevicePairing.parseQr(uriStr)
        assertNotNull(parsedFromUri)
        assertEquals(original.baseUrl, parsedFromUri?.baseUrl)
        assertEquals(original.certFingerprint, parsedFromUri?.certFingerprint)
        assertEquals(original.serverPubkey, parsedFromUri?.serverPubkey)
        assertEquals(original.pairCode, parsedFromUri?.pairCode)
    }

    @Test
    fun wpB1_deviceSignerBuildsCanonicalFiveLineStringAndHeaders() {
        val signStr = DeviceSigner.buildSignString(
            method = "post",
            path = "/api/v1/device/heartbeat",
            tsMs = 1761800000000L,
            nonce = "0123456789abcdef0123456789abcdef",
            body = """{"hello":"world"}""".toByteArray(Charsets.UTF_8),
        )
        val lines = signStr.split("\n")
        assertEquals(5, lines.size)
        assertEquals("POST", lines[0])
        assertEquals("/api/v1/device/heartbeat", lines[1])
        assertEquals("1761800000000", lines[2])
        assertEquals("0123456789abcdef0123456789abcdef", lines[3])
        assertEquals(DeviceSigner.sha256Hex("""{"hello":"world"}""".toByteArray(Charsets.UTF_8)), lines[4])

        val signer = DeviceSigner.MockSigner("test-key")
        val headers = DeviceSigner.signHeaders(
            deviceId = "dev-1",
            method = "GET",
            path = "/api/v1/device/heartbeat",
            body = null,
            signer = signer,
            nowMs = 1761800000000L,
            nonce = "0123456789abcdef0123456789abcdef",
        )
        assertEquals("dev-1", headers[DeviceSigner.HEADER_DEVICE])
        assertEquals("1761800000000", headers[DeviceSigner.HEADER_TS])
        assertEquals("0123456789abcdef0123456789abcdef", headers[DeviceSigner.HEADER_NONCE])
        assertNotNull(headers[DeviceSigner.HEADER_SIG])
    }

    @Test
    fun wpB1_j11_tlsFingerprintNormalization() {
        val fp1 = "aa:bb:cc:dd:ee:ff"
        val fp2 = "AA-BB-CC-DD-EE-FF"
        val fp3 = "aabbccddeeff"
        assertEquals(SyncClient.normalizeFingerprint(fp1), SyncClient.normalizeFingerprint(fp2))
        assertEquals(SyncClient.normalizeFingerprint(fp1), SyncClient.normalizeFingerprint(fp3))
    }

    // --- WP-B-2: Remote Unlock & Offline Backup Code (J2-J8, J10, J13, G5) ---

    @Test
    fun wpB2_canonicalGrantPayloadAlphabeticalOrder() {
        val grant = Grant(
            v = 1,
            grantId = "g-1",
            requestId = "r-1",
            deviceId = "d-1",
            questionHash = "q-1",
            scope = "question",
            expiresAt = "2026-10-30T12:00:00Z",
            decidedByLabel = "家长",
            signature = "sig-dummy",
        )
        val canonical = String(RemoteUnlock.buildCanonicalGrantPayload(grant), Charsets.UTF_8)
        val keys = listOf(
            "decided_by_label", "device_id", "expires_at", "grant_id",
            "question_hash", "request_id", "scope", "v",
        )
        var lastIdx = -1
        for (k in keys) {
            val idx = canonical.indexOf("\"$k\":")
            assertTrue("Key $k should be found", idx >= 0)
            assertTrue("Key $k should come after previous key", idx > lastIdx)
            lastIdx = idx
        }
    }

    @Test
    fun wpB2_j5_grantVerificationRejectsTransfersOrReplay() {
        val grant = Grant(
            v = 1,
            grantId = "grant-100",
            requestId = "req-100",
            deviceId = "dev-target",
            questionHash = "qh-target",
            scope = "question",
            expiresAt = "2026-10-30T12:00:00Z",
            decidedByLabel = "家长",
            signature = "valid-sig",
        )
        val mockVerifier = { _: String, _: ByteArray, sig: String -> sig == "valid-sig" }
        val consumed = mutableSetOf<String>()

        // 1. Success matching
        assertTrue(
            RemoteUnlock.verifyGrant(
                grant = grant,
                serverPubkey = "pubkey",
                localDeviceId = "dev-target",
                currentQuestionHash = "qh-target",
                consumedGrantIds = consumed,
                verifier = mockVerifier,
            ),
        )

        // 2. Reject transfer to another device (J5)
        assertFalse(
            RemoteUnlock.verifyGrant(
                grant = grant,
                serverPubkey = "pubkey",
                localDeviceId = "dev-OTHER",
                currentQuestionHash = "qh-target",
                consumedGrantIds = consumed,
                verifier = mockVerifier,
            ),
        )

        // 3. Reject transfer to another question (J5)
        assertFalse(
            RemoteUnlock.verifyGrant(
                grant = grant,
                serverPubkey = "pubkey",
                localDeviceId = "dev-target",
                currentQuestionHash = "qh-OTHER",
                consumedGrantIds = consumed,
                verifier = mockVerifier,
            ),
        )

        // 4. Reject replay of already consumed grant (J5)
        consumed.add("grant-100")
        assertFalse(
            RemoteUnlock.verifyGrant(
                grant = grant,
                serverPubkey = "pubkey",
                localDeviceId = "dev-target",
                currentQuestionHash = "qh-target",
                consumedGrantIds = consumed,
                verifier = mockVerifier,
            ),
        )
    }

    @Test
    fun wpB2_j13_offlineBackupCodeDerivationAndLockout() {
        val secret = "test-secret-32-bytes-long-key!!".toByteArray(Charsets.UTF_8)
        val manager = OfflineBackupCode(secret)
        val reqId = "req-123"
        val qHash = "qh-456"
        val now = 1761800000000L

        val expectedCode = OfflineBackupCode.deriveCode(secret, reqId, qHash)
        assertEquals(6, expectedCode.length)
        assertTrue(expectedCode.all { it.isDigit() })

        // Bad code fails and decrements attempts remaining
        val r1 = manager.verify("000000", reqId, qHash, now, now)
        assertTrue(r1 is OfflineBackupCode.VerifyResult.BadCode)
        assertEquals(4, (r1 as OfflineBackupCode.VerifyResult.BadCode).attemptsRemaining)

        // Repeat bad attempts to hit lockout (5 consecutive)
        manager.verify("000001", reqId, qHash, now, now)
        manager.verify("000002", reqId, qHash, now, now)
        manager.verify("000003", reqId, qHash, now, now)
        val r5 = manager.verify("000004", reqId, qHash, now, now)
        assertTrue(r5 is OfflineBackupCode.VerifyResult.Locked)
        assertTrue(manager.isLocked(now))

        // While locked, even correct code is rejected
        val lockedResult = manager.verify(expectedCode, reqId, qHash, now, now)
        assertTrue(lockedResult is OfflineBackupCode.VerifyResult.Locked)

        // After lock window passes, correct code is accepted
        val later = now + OfflineBackupCode.LOCK_DURATION_MS + 1000
        val acceptedResult = manager.verify(expectedCode, reqId, qHash, later, later)
        assertTrue(acceptedResult is OfflineBackupCode.VerifyResult.Accepted)

        // Code cannot be reused (single-use)
        val reuseResult = manager.verify(expectedCode, reqId, qHash, later, later)
        assertTrue(reuseResult is OfflineBackupCode.VerifyResult.AlreadyUsed)
    }

    @Test
    fun wpB2_revealAuthIntegratesRemoteGrantAndBackupCode() {
        var state = TutoringMachine.reduce(
            TutoringState(),
            TutoringAction.Start("qh-1", 10),
        ).state
        state = TutoringMachine.reduce(state, TutoringAction.ConfirmStem(10)).state
        state = TutoringMachine.reduce(state, TutoringAction.AskHelp).state
        state = TutoringMachine.reduce(state, TutoringAction.AskHelp).state
        assertEquals(RevealLayer.STEP, state.layer)
        assertTrue(state.layer.chainComplete())

        // Unlock with Remote Grant
        val grant = Grant(
            v = 1,
            grantId = "g-m5b",
            requestId = "r-m5b",
            deviceId = "dev-1",
            questionHash = "qh-1",
            scope = "question",
            expiresAt = "2026-10-30T12:00:00Z",
            decidedByLabel = "家长",
            signature = "sig-ok",
        )
        val consumed = mutableSetOf<String>()
        val (grantedState, reduceResult) = RevealAuth.unlockWithRemoteGrant(
            state = state,
            grant = grant,
            serverPubkey = "pub",
            localDeviceId = "dev-1",
            consumedGrantIds = consumed,
            verifier = { _, _, sig -> sig == "sig-ok" },
        )
        assertFalse(reduceResult.rejected)
        assertTrue(grantedState.revealGranted)
        assertEquals(RevealMethod.REMOTE, grantedState.revealMethod)
        assertEquals(RevealLayer.REVEAL, grantedState.layer)
    }

    // --- WP-B-3: Content Safety & Emergency Flow (F5, F6, F7, H1-H6) ---

    @Test
    fun wpB3_f5_f6_f7_emergencyScreensAndNoHardcodedHotline() {
        // Step 1: F5 - only "请马上告诉身边的大人。" and "不想说", NO phone number
        val step1 = ContentSafetyGuide.createStep1Screen()
        assertEquals(EmergencyStep.STEP1_TELL_ADULT, step1.step)
        assertEquals("请马上告诉身边的大人。", step1.messageText)
        assertTrue(step1.showDeclineButton)
        assertNull(step1.hotlineNumber)
        assertNull(step1.hotlineText)

        // Step 2 without parent configured hotline: F6 - still retains only first sentence, no number
        val step2Unconfigured = ContentSafetyGuide.createStep2Screen(null, null)
        assertEquals(EmergencyStep.STEP2_DECLINED_OR_HOTLINE, step2Unconfigured.step)
        assertEquals("请马上告诉身边的大人。", step2Unconfigured.messageText)
        assertFalse(step2Unconfigured.showDeclineButton)
        assertNull(step2Unconfigured.hotlineNumber)

        // Step 2 with parent configured hotline: F6 - shows parent's custom text and number
        val step2Configured = ContentSafetyGuide.createStep2Screen("家长预留热线", "400-000-0000")
        assertEquals(EmergencyStep.STEP2_DECLINED_OR_HOTLINE, step2Configured.step)
        assertTrue(step2Configured.messageText.contains("400-000-0000") || step2Configured.hotlineNumber == "400-000-0000")

        // F7: Verify no hardcoded phone numbers in repo source files
        val safetySrc = repoFile("src/android/app/src/main/java/com/openminis/k12/safety/ContentSafetyGuide.kt").readText()
        assertFalse(safetySrc.contains("400-"))
        assertFalse(safetySrc.contains("138"))
        assertFalse(safetySrc.contains("12355"))
    }

    @Test
    fun wpB3_h4_contextSnippetMaxTwoSentencesBeforeAndAfter() {
        val full = "这是第一句。这是第二句。这是第三句。想自残。这是第五句。这是第六句。这是第七句。"
        val triggerIdx = full.indexOf("想自残")
        val snippet = ContentSafetyGuide.extractContextSnippet(full, triggerIdx)
        assertTrue(snippet.contains("想自残"))
        assertTrue(snippet.contains("这是第二句"))
        assertTrue(snippet.contains("这是第三句"))
        assertTrue(snippet.contains("这是第五句"))
        assertTrue(snippet.contains("这是第六句"))
        // Max 2 sentences before and after: should NOT contain first or seventh
        assertFalse(snippet.contains("这是第一句"))
        assertFalse(snippet.contains("这是第七句"))
    }

    // --- WP-B-5: Sync Client Heartbeat & Config Whitelisting (§19.4) ---

    @Test
    fun wpB5_heartbeatAppliesOnlyWhitelistedKeysAndCalibratesClock() {
        val mockTransport = object : HttpTransport {
            override fun execute(
                method: String,
                url: String,
                headers: Map<String, String>,
                body: ByteArray?,
            ): HttpResponse {
                val resp = JSONObject().apply {
                    put("server_time", "2026-10-30T12:00:00Z")
                    put("config_version", 5)
                    put("revoked", false)
                    put("config", JSONObject().apply {
                        put("daily_time_limit_minutes", 45)
                        put("hotline_text", "校心理援助")
                        put("hotline_number", "800-8888")
                        put("forbidden_alert_threshold", 99) // Strictly forbidden (§19.4)
                        put("unknown_arbitrary_key", "val") // Unknown key
                    })
                }
                return HttpResponse(200, emptyMap(), resp.toString().toByteArray(Charsets.UTF_8))
            }
        }

        val client = SyncClient(
            config = SyncConfig("https://example.com", "dev-1"),
            signer = DeviceSigner.MockSigner("k"),
            transport = mockTransport,
        )

        val result = client.heartbeat(1, "1.0.0-m5b", 20)
        assertEquals(5, result.configVersion)
        assertEquals(45, result.config["daily_time_limit_minutes"])
        assertEquals("校心理援助", result.config["hotline_text"])
        assertEquals("800-8888", result.config["hotline_number"])
        // Whitelist enforcement (§19.4):
        assertFalse(result.config.containsKey("forbidden_alert_threshold"))
        assertFalse(result.config.containsKey("unknown_arbitrary_key"))
    }

    // --- WP-B-6: Encrypted Backup & Restore (§19.6) ---

    @Test
    fun wpB6_encryptedBackupAndRestoreWithMagicAndPbkdf2() {
        val originalData = "test-k12-state-backup-data-123".toByteArray(Charsets.UTF_8)
        val password = "parent-secure-password"

        val pkg = DeviceBackupManager.createBackup(originalData, password)
        assertTrue(pkg.payload.size > 36)
        // Check magic bytes "K12B"
        assertEquals(0x4B.toByte(), pkg.payload[0])
        assertEquals(0x31.toByte(), pkg.payload[1])
        assertEquals(0x32.toByte(), pkg.payload[2])
        assertEquals(0x42.toByte(), pkg.payload[3])

        val restored = DeviceBackupManager.restoreBackup(pkg.payload, password)
        assertEquals("test-k12-state-backup-data-123", String(restored, Charsets.UTF_8))

        // Corrupted password fails
        var threw = false
        try {
            DeviceBackupManager.restoreBackup(pkg.payload, "wrong-password")
        } catch (_: Exception) {
            threw = true
        }
        assertTrue("Wrong password must fail", threw)
    }

    // --- WP-B-7: Parental Controls Quota ---

    @Test
    fun wpB7_dailyQuotaDefaults() {
        val quota = ScreenTime.DailyQuota()
        assertEquals(60, quota.maxRounds)
        assertEquals(20, quota.maxImages)
        assertEquals(15, quota.maxCompanionMinutes)
        assertFalse(quota.isRoundsExceeded(59))
        assertTrue(quota.isRoundsExceeded(60))
        assertFalse(quota.isImagesExceeded(19))
        assertTrue(quota.isImagesExceeded(20))
        assertFalse(quota.isCompanionExceeded(14))
        assertTrue(quota.isCompanionExceeded(15))
    }

    // --- WP-B-8: Diagnostics Sanitization (§19.7) ---

    @Test
    fun wpB8_diagnosticsSanitizationRejectsForbiddenFields() {
        val mockTransport = object : HttpTransport {
            override fun execute(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse =
                HttpResponse(202, emptyMap(), ByteArray(0))
        }
        val client = SyncClient(
            config = SyncConfig("https://example.com", "dev-1"),
            signer = DeviceSigner.MockSigner("k"),
            transport = mockTransport,
        )

        // Safe diagnostics accepts
        val safeDiag = JSONObject().apply {
            put("battery_level", 85)
            put("storage_free_mb", 1024)
        }
        val resp = client.uploadDiagnostics(safeDiag)
        assertEquals(202, resp.code)

        // Leak check: rejects problem_text
        var rejected = false
        try {
            val leaking = JSONObject().apply {
                put("problem_text", "1 + 1 = ?")
            }
            client.uploadDiagnostics(leaking)
        } catch (_: Exception) {
            rejected = true
        }
        assertTrue("Diagnostics containing problem_text must be rejected", rejected)
    }

    private fun repoFile(name: String): File = listOf(
        File(name),
        File("../../$name"),
        File("../../../$name"),
    ).firstOrNull { it.exists() } ?: File("../../$name")
}
