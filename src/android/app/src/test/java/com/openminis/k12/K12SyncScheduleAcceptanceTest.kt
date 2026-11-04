package com.openminis.k12

import com.openminis.k12.device.CertificatePinException
import com.openminis.k12.device.DevicePairing
import com.openminis.k12.device.DeviceSigner
import com.openminis.k12.device.DeviceStatus
import com.openminis.k12.device.HttpResponse
import com.openminis.k12.device.HttpTransport
import com.openminis.k12.device.K12SyncController
import com.openminis.k12.device.K12SyncCycle
import com.openminis.k12.device.MemoryConfigSink
import com.openminis.k12.device.MemoryDiagnosticOutbox
import com.openminis.k12.device.MemoryStudyEventOutbox
import com.openminis.k12.device.PairedEndpoint
import com.openminis.k12.device.PendingDiagnostic
import com.openminis.k12.device.PendingStudyEvent
import com.openminis.k12.device.RecordingSyncScheduler
import com.openminis.k12.device.SyncClient
import com.openminis.k12.device.SyncConfig
import com.openminis.k12.device.SyncGate
import com.openminis.k12.device.SyncRunResult
import com.openminis.k12.device.SyncSchedule
import com.openminis.k12.device.SyncTrigger
import com.openminis.k12.device.mergeServerLimits
import com.openminis.k12.device.serverPolicyPatch
import com.openminis.k12.device.LimitTriple
import com.openminis.k12.device.toPending
import com.openminis.k12.model.K12ModelSync
import com.openminis.k12.model.LocalModelSnapshot
import com.openminis.k12.model.MemoryModelSecretStore
import com.openminis.k12.model.ModelFetch
import com.openminis.k12.model.ModelLog
import com.openminis.k12.model.TabletModelSpec
import com.openminis.k12.model.UpstreamModelPort
import com.openminis.k12.tutoring.RevealLayer
import com.openminis.k12.tutoring.RevealMethod
import com.openminis.k12.tutoring.StudyEvent
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Periodic sync scheduling. Synthetic hosts and keys only.
 */
class K12SyncScheduleAcceptanceTest {
    private val secret = "synthetic-tablet-key-9f3a"

    @After
    fun resetPairingHook() {
        DevicePairing.onActivated = null
    }

    @Test
    fun periodicIntervalIsFifteenMinutesBecauseTheContractHasNoInterval() {
        assertEquals(15L, SyncSchedule.PERIODIC_MINUTES)
        val api = repoFile("docs/k12/api/device-api.yaml").readText()
        val start = api.indexOf("HeartbeatResponse:")
        assertTrue(start > 0)
        val section = api.substring(start, api.indexOf("PermissionCheck:", start))
        assertFalse(section.contains("sync_interval"))
        assertFalse(section.contains("next_heartbeat"))
        assertTrue(section.contains("tablet_model_version"))
        assertFalse(section.contains("api_key"))
    }

    @Test
    fun pairedDeviceSchedulesPeriodicAndOneOffAndUnpairCancels() {
        val sched = RecordingSyncScheduler()
        val controller = K12SyncController(sched)
        DevicePairing.onActivated = { controller.onPaired() }

        controller.onForeground()
        assertFalse(controller.paired)
        assertNull(sched.periodicMinutes)

        DevicePairing.noteActivated(
            PairedEndpoint("https://example.invalid", "dev-1", "child-model-a"),
            DeviceStatus.PENDING_CONFIRM,
        )
        assertFalse(controller.paired)

        DevicePairing.noteActivated(
            PairedEndpoint("https://example.invalid", "dev-1", "child-model-a", "ab"),
            DeviceStatus.ACTIVE,
        )
        assertTrue(controller.paired)
        assertEquals(15L, sched.periodicMinutes)
        assertEquals(SyncTrigger.PAIRED, sched.soon.single())
        assertTrue(sched.networkConnected)
        assertEquals("EXPONENTIAL", sched.backoff)
        assertEquals("KEEP", sched.periodicPolicy)
        assertEquals("KEEP", sched.soonPolicy)

        controller.onForeground()
        assertTrue(sched.periodicKept >= 1)
        assertTrue(sched.soonKept >= 1)
        assertEquals(1, sched.soon.size)

        controller.onQueueDepth(99)
        val keptBefore = sched.soonKept
        controller.onQueueDepth(100)
        assertTrue(sched.soonKept > keptBefore)

        controller.onUnpair()
        assertFalse(controller.paired)
        assertNull(sched.periodicMinutes)
        assertTrue(sched.soon.isEmpty())
        assertTrue(sched.cancelCount >= 1)

        controller.onForeground()
        assertTrue(sched.soon.isEmpty())
    }

    @Test
    fun overlappingRunsCollapse() {
        val gate = SyncGate()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runs = java.util.concurrent.atomic.AtomicInteger(0)
        val joinedInside = CountDownLatch(1)
        gate.onJoined = { joinedInside.countDown() }
        val workerResult = arrayOfNulls<SyncRunResult>(1)
        val worker = Thread {
            workerResult[0] = gate.run {
                runs.incrementAndGet()
                started.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                SyncRunResult.Ok
            }
        }
        val joinedResult = arrayOfNulls<SyncRunResult>(1)
        val joined = Thread {
            joinedResult[0] = gate.run {
                runs.incrementAndGet()
                SyncRunResult.Stop(9)
            }
        }
        worker.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        joined.start()
        assertTrue(joinedInside.await(5, TimeUnit.SECONDS))
        release.countDown()
        worker.join(5_000)
        joined.join(5_000)
        gate.onJoined = null
        assertTrue(workerResult[0] is SyncRunResult.Ok)
        assertTrue("a sync that joined the in-flight run must observe its result", joinedResult[0] is SyncRunResult.Ok)
        assertEquals(2, runs.get())
    }

    @Test
    fun runAppliesConfigFetchesModelAndUploadsEventsWithoutLoggingTheKey() {
        val store = MemoryModelSecretStore()
        val upstream = FakeUpstream()
        val log = ModelLog()
        val config = MemoryConfigSink()
        val events = MemoryStudyEventOutbox()
        val diagnostics = MemoryDiagnosticOutbox()
        repeat(600) { index ->
            events.add(
                PendingStudyEvent("evt-$index", 0, "stem_confirmed", 1_700_000_000_000L, mapOf("question_hash" to "abc", "char_count" to 4)),
            )
        }
        assertFalse(diagnostics.add(PendingDiagnostic("bad", """{"problem_text":"1+1"}""")))
        assertTrue(diagnostics.add(PendingDiagnostic("ok", """{"kind":"log","body":"synthetic"}""")))
        var modelFetches = 0
        val batches = mutableListOf<Int>()
        val client = client { url, body ->
            when {
                url.endsWith("/api/v1/device/heartbeat") -> {
                    val text = String(body ?: ByteArray(0), Charsets.UTF_8)
                    assertFalse(text.contains(secret))
                    if (config.version == 0) {
                        assertTrue(text.contains("\"config_version_applied\":0"))
                    } else {
                        assertTrue(text.contains("\"config_version_applied\":${config.version}"))
                    }
                    json(
                        """{"server_time":"2026-11-03T00:00:00Z","config_version":3,"tablet_model_version":2,"config_json":{"daily_limit_minutes":50,"weekend_extra_minutes":20,"break_reminder_minutes":25,"reveal_policy":{"mode":"after_steps"},"turn_limit":40,"image_limit":10,"companion_limit_minutes":12,"hotline_text":"placeholder","hotline_number":"000","api_key":"$secret","forbidden_alert_threshold":9}}""",
                    )
                }
                url.endsWith("/api/v1/device/tablet-model") -> {
                    modelFetches++
                    json(
                        """{"version":2,"cleared":false,"provider_type":"openAI","base_url":"https://example.invalid/v1","model_name":"chat-model","vision_model_name":"","api_key":"$secret"}""",
                    )
                }
                url.endsWith("/api/v1/device/events") -> {
                    val payload = JSONObject(ungzip(body ?: ByteArray(0)))
                    batches += payload.getJSONArray("events").length()
                    json("""{"max_seq":1}""")
                }
                url.endsWith("/api/v1/device/diagnostics") -> json("""{"id":"d1"}""", code = 202)
                else -> HttpResponse(404, emptyMap(), ByteArray(0))
            }
        }
        val cycle = cycle(client, config, events, diagnostics, store, upstream, log)
        assertTrue(cycle.run() is SyncRunResult.Ok)
        assertEquals(3, config.version)
        assertEquals(50, config.fields["daily_limit_minutes"])
        assertFalse(config.fields.containsKey("api_key"))
        assertFalse(config.fields.containsKey("forbidden_alert_threshold"))
        val patch = serverPolicyPatch(config.fields)
        assertEquals(50, patch.dailyLimitMinutes)
        assertEquals(40, patch.turnLimit)
        assertTrue(patch.revealPolicy!!.contains("after_steps"))
        assertEquals(1, modelFetches)
        assertEquals(secret, store.load()?.apiKey)
        assertEquals(listOf(500, 100), batches)
        assertTrue(events.pending().isEmpty())
        assertTrue(diagnostics.pending().isEmpty())
        assertTrue(log.lines.none { it.contains(secret) })

        val again = cycle.run()
        assertTrue(again is SyncRunResult.Ok)
        assertEquals(1, modelFetches)
    }

    @Test
    fun revokedHeartbeatDoesNotFetchOrUploadAndRateLimitRetries() {
        val events = MemoryStudyEventOutbox()
        events.add(PendingStudyEvent("evt-1", 1, "stem_confirmed", 1L, mapOf("question_hash" to "abc")))
        val seen = mutableListOf<String>()
        val denied = client { url, _ ->
            seen += url
            HttpResponse(401, emptyMap(), """{"api_key":"$secret"}""".toByteArray())
        }
        val store = MemoryModelSecretStore()
        val result = cycle(denied, MemoryConfigSink(), events, MemoryDiagnosticOutbox(), store, FakeUpstream(), ModelLog()).run()
        assertTrue(result is SyncRunResult.Stop)
        assertEquals(401, (result as SyncRunResult.Stop).code)
        assertEquals(1, events.pending().size)
        assertNull(store.load())
        assertTrue(seen.none { it.contains("tablet-model") })
        assertTrue(seen.none { it.contains(secret) })

        val limited = client { _, _ -> HttpResponse(429, emptyMap(), ByteArray(0)) }
        val retry = cycle(limited, MemoryConfigSink(), events, MemoryDiagnosticOutbox(), store, FakeUpstream(), ModelLog()).run()
        assertTrue(retry is SyncRunResult.Retry)
        assertEquals(1, events.pending().size)
    }

    @Test
    fun modelDenialDoesNotStoreTheKey() {
        val store = MemoryModelSecretStore()
        val client = client { url, _ ->
            when {
                url.endsWith("/api/v1/device/heartbeat") -> json(
                    """{"server_time":"2026-11-03T00:00:00Z","tablet_model_version":4}""",
                )
                else -> HttpResponse(403, emptyMap(), """{"api_key":"$secret"}""".toByteArray())
            }
        }
        val log = ModelLog()
        val result = cycle(client, MemoryConfigSink(), MemoryStudyEventOutbox(), MemoryDiagnosticOutbox(), store, FakeUpstream(), log).run()
        assertTrue(result is SyncRunResult.Stop)
        assertNull(store.load())
        assertTrue(log.lines.none { it.contains(secret) })
    }

    @Test
    fun limitsAndEventWireStayInsideTheContract() {
        val merged = mergeServerLimits(LimitTriple(40, 30, 25), serverPolicyPatch(mapOf("daily_limit_minutes" to 55)))
        assertEquals(55, merged?.daily)
        assertNull(mergeServerLimits(LimitTriple(40, 30, 25), serverPolicyPatch(mapOf("daily_limit_minutes" to 1))))
        val wire = StudyEvent.AnswerReveal(
            eventId = "evt-a",
            occurredAtMs = 10L,
            sessionId = "sess",
            questionHash = "hash-only",
            method = RevealMethod.PIN,
            hintsUsed = 2,
        ).toPending().toJson("dev-1", "child-model-a")
        assertEquals("answer_reveal", wire.getString("type"))
        assertEquals("hash-only", wire.getJSONObject("payload").getString("question_hash"))
        assertFalse(wire.toString().contains("problem_text"))
        val hint = StudyEvent.HintUsed("evt-b", 11L, "sess", "hash-only", RevealLayer.HINT, 1).toPending()
        assertEquals("hint", hint.payload["level"])
    }

    @Test
    fun workManagerSchedulerUsesKeepNetworkAndBackoff() {
        val src = repoFile("src/android/app/src/main/java/com/openminis/k12/android/WorkManagerSyncScheduler.kt").readText()
        assertTrue(src.contains("ExistingPeriodicWorkPolicy.KEEP"))
        assertTrue(src.contains("ExistingWorkPolicy.KEEP"))
        assertTrue(src.contains("NetworkType.CONNECTED"))
        assertTrue(src.contains("BackoffPolicy.EXPONENTIAL"))
        assertTrue(src.contains("K12SyncWorker"))
        assertTrue(src.contains("k12-sync-periodic") || src.contains("PERIODIC_WORK"))
        val worker = repoFile("src/android/app/src/main/java/com/openminis/k12/android/K12SyncWorker.kt").readText()
        assertTrue(worker.contains("K12SyncRuntime.runOnce"))
        assertFalse(worker.contains("Log."))
        assertFalse(worker.contains("println"))
        val runtime = repoFile("src/android/app/src/main/java/com/openminis/k12/android/K12SyncRuntime.kt").readText()
        assertTrue(runtime.contains("onForeground"))
        assertTrue(runtime.contains("onPairingSucceeded"))
        assertTrue(runtime.contains("onUnpairOrReset"))
        val app = repoFile("src/android/app/src/main/java/com/openminis/app/MinisApp.kt").readText()
        assertTrue(app.contains("K12SyncRuntime.install"))
        val workflow = repoFile(".github/workflows/android.yml").readText()
        assertTrue(workflow.contains("K12SyncScheduleAcceptanceTest"))
        val transport = repoFile("src/android/app/src/main/java/com/openminis/k12/android/OkHttpSyncTransport.kt").readText()
        assertTrue(transport.contains("CertificatePinException"))
        assertFalse(transport.contains("Log."))
    }

    @Test
    fun modelConfigIsNotRefetchedOnTheFifteenMinuteOrForegroundCadence() {
        assertEquals(24L * 60L * 60L * 1000L, com.openminis.k12.device.ModelConfigSchedule.INTERVAL_MS)
        val store = MemoryModelSecretStore()
        val upstream = FakeUpstream()
        val log = ModelLog()
        var modelFetches = 0
        var now = 1_700_000_000_000L
        val client = client { url, _ ->
            when {
                url.endsWith("/api/v1/device/heartbeat") -> json(
                    """{"server_time":"2026-11-03T00:00:00Z","config_version":1,"tablet_model_version":2,"config_json":{"daily_limit_minutes":40}}""",
                )
                url.endsWith("/api/v1/device/tablet-model") -> {
                    modelFetches++
                    json(
                        """{"version":2,"cleared":false,"provider_type":"openAI","base_url":"https://k12.example.com/v1","model_name":"chat-model","vision_model_name":"","api_key":"$secret"}""",
                    )
                }
                else -> json("""{"ok":true}""")
            }
        }
        val cycle = K12SyncCycle(
            client = client,
            config = MemoryConfigSink(),
            events = MemoryStudyEventOutbox(),
            diagnostics = MemoryDiagnosticOutbox(),
            onModelVersion = { version, force, fetch ->
                K12ModelSync.note(version, store, upstream, fetch, log, force)
            },
            appVersion = com.openminis.k12.Version.NAME,
            deviceId = "dev-1",
            childId = "child-model-a",
            log = log,
            modelAppliedVersion = { store.load()?.version ?: 0L },
            clock = { now },
        )
        assertTrue(cycle.run() is SyncRunResult.Ok)
        assertEquals(1, modelFetches)
        now += 15 * 60 * 1000
        assertTrue(cycle.run() is SyncRunResult.Ok)
        assertEquals(1, modelFetches)
        now += com.openminis.k12.device.ModelConfigSchedule.INTERVAL_MS
        assertTrue(cycle.run() is SyncRunResult.Ok)
        assertEquals(2, modelFetches)
        val adr = repoFile("docs/k12/decisions/0015-server-managed-tablet-model.md").readText()
        assertTrue(adr.contains("ModelConfigSchedule.INTERVAL_MS"))
        assertTrue(adr.contains("24"))
        val changelog = repoFile("CHANGELOG.md").readText()
        assertTrue(changelog.contains("ModelConfigSchedule.INTERVAL_MS"))
    }

    @Test
    fun newerTabletModelVersionFetchesBeforeTheDailyInterval() {
        val store = MemoryModelSecretStore()
        val upstream = FakeUpstream()
        var announced = 2L
        var modelFetches = 0
        val now = 1_700_000_000_000L
        val client = client { url, _ ->
            when {
                url.endsWith("/api/v1/device/heartbeat") -> json(
                    """{"server_time":"2026-11-03T00:00:00Z","tablet_model_version":$announced}""",
                )
                url.endsWith("/api/v1/device/tablet-model") -> {
                    modelFetches++
                    json(
                        """{"version":$announced,"cleared":false,"provider_type":"openAI","base_url":"https://k12.example.com/v1","model_name":"chat-model","vision_model_name":"","api_key":"$secret"}""",
                    )
                }
                else -> json("""{"ok":true}""")
            }
        }
        val cycle = K12SyncCycle(
            client = client,
            config = MemoryConfigSink(),
            events = MemoryStudyEventOutbox(),
            diagnostics = MemoryDiagnosticOutbox(),
            onModelVersion = { version, force, fetch ->
                K12ModelSync.note(version, store, upstream, fetch, null, force)
            },
            appVersion = com.openminis.k12.Version.NAME,
            deviceId = "dev-1",
            childId = "child-model-a",
            modelAppliedVersion = { store.load()?.version ?: 0L },
            clock = { now },
        )
        assertTrue(cycle.run() is SyncRunResult.Ok)
        assertEquals(1, modelFetches)
        announced = 3L
        assertTrue(cycle.run() is SyncRunResult.Ok)
        assertEquals(2, modelFetches)
        assertEquals(3L, store.load()?.version)
        assertFalse(logContains(store.load()?.toString().orEmpty()))
    }

    private fun logContains(text: String): Boolean = text.contains(secret)

    @Test
    fun pinMismatchStopsWithoutARetry() {
        val client = SyncClient(
            SyncConfig("https://example.invalid", "dev-1"),
            DeviceSigner.MockSigner("k"),
            object : HttpTransport {
                override fun execute(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
                    throw CertificatePinException()
                }
            },
        )
        val result = K12SyncCycle(
            client = client,
            config = MemoryConfigSink(),
            events = MemoryStudyEventOutbox(),
            diagnostics = MemoryDiagnosticOutbox(),
            onModelVersion = { _, _, _ -> com.openminis.k12.model.SyncOutcome.Unchanged },
            appVersion = com.openminis.k12.Version.NAME,
            deviceId = "dev-1",
            childId = "child-model-a",
        ).run()
        assertTrue(result is SyncRunResult.Stop)
    }

    private fun cycle(
        client: SyncClient,
        config: MemoryConfigSink,
        events: MemoryStudyEventOutbox,
        diagnostics: MemoryDiagnosticOutbox,
        store: MemoryModelSecretStore,
        upstream: FakeUpstream,
        log: ModelLog,
    ) = K12SyncCycle(
        client = client,
        config = config,
        events = events,
        diagnostics = diagnostics,
        onModelVersion = { version, force, fetch ->
            K12ModelSync.note(version, store, upstream, fetch, log, force)
        },
        appVersion = com.openminis.k12.Version.NAME,
        deviceId = "dev-1",
        childId = "child-model-a",
        log = log,
        modelAppliedVersion = { store.load()?.version ?: 0L },
        clock = { 1_700_000_000_000L },
    )

    private fun json(text: String, code: Int = 200) = HttpResponse(code, emptyMap(), text.toByteArray())

    private fun client(handler: (String, ByteArray?) -> HttpResponse): SyncClient {
        val transport = object : HttpTransport {
            override fun execute(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
                assertFalse(headers.values.joinToString(" ").contains(secret))
                return handler(url, body)
            }
        }
        return SyncClient(SyncConfig("https://example.invalid", "dev-1"), DeviceSigner.MockSigner("k"), transport)
    }

    private fun ungzip(bytes: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { String(it.readBytes(), Charsets.UTF_8) }

    private fun repoFile(name: String): File = listOf(
        File(name),
        File("../../$name"),
        File("../../../$name"),
    ).first { it.exists() }

    private class FakeUpstream : UpstreamModelPort {
        override fun capture(): LocalModelSnapshot = LocalModelSnapshot(null, null, emptyList())
        override fun apply(spec: TabletModelSpec) = Unit
        override fun restore(snapshot: LocalModelSnapshot) = Unit
        override fun removeManaged() = Unit
    }
}
