package com.openminis.k12.device

import com.openminis.k12.model.ModelFetch
import com.openminis.k12.model.ModelLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * Schedules [SyncClient] on a paired tablet.
 *
 * HeartbeatResponse has no interval field. The sync-client doc asks for an
 * event upload every 5 minutes, which WorkManager cannot express: a periodic
 * job cannot be shorter than 15 minutes. That 15-minute floor is also the
 * doc's forced window before the daily brief, so the periodic job uses it.
 */
object SyncSchedule {
    const val PERIODIC_MINUTES = 15L
    const val PERIODIC_WORK = "k12-sync-periodic"
    const val SOON_WORK = "k12-sync-soon"
    const val QUEUE_FLUSH_COUNT = 100
    const val BACKOFF_SECONDS = 30L
    const val MAX_EVENT_BATCH = 500
}

/**
 * How often a paired tablet may pull `GET /api/v1/device/tablet-model`.
 * The 15-minute job still sends the heartbeat and uploads usage. It pulls the
 * full model document on the first success after pairing, again when the
 * heartbeat's `tablet_model_version` changes, and otherwise at most once per
 * [INTERVAL_MS]. A failed pull does not count, so the next sync retries.
 */
object ModelConfigSchedule {
    const val INTERVAL_MS = 24L * 60L * 60L * 1000L

    fun shouldFetch(announced: Long, applied: Long, lastSuccessAtMs: Long, nowMs: Long): Boolean {
        if (announced != applied) return true
        if (lastSuccessAtMs <= 0L) return true
        return nowMs - lastSuccessAtMs >= INTERVAL_MS
    }
}

enum class SyncTrigger { FOREGROUND, PAIRED, QUEUE }

interface SyncScheduler {
    fun ensurePeriodic(intervalMinutes: Long)
    fun enqueueSoon(trigger: SyncTrigger)
    fun cancel()
}

/** In-memory scheduler for unit tests. A second request with work already queued is kept. */
class RecordingSyncScheduler : SyncScheduler {
    var periodicMinutes: Long? = null
    var periodicEnsures: Int = 0
    var periodicKept: Int = 0
    val soon = mutableListOf<SyncTrigger>()
    var soonKept: Int = 0
    var cancelCount: Int = 0
    val networkConnected: Boolean = true
    val backoff: String = "EXPONENTIAL"
    val periodicPolicy: String = "KEEP"
    val soonPolicy: String = "KEEP"

    override fun ensurePeriodic(intervalMinutes: Long) {
        periodicEnsures++
        if (periodicMinutes != null) {
            periodicKept++
            return
        }
        periodicMinutes = intervalMinutes
    }

    override fun enqueueSoon(trigger: SyncTrigger) {
        if (soon.isNotEmpty()) {
            soonKept++
            return
        }
        soon += trigger
    }

    override fun cancel() {
        cancelCount++
        periodicMinutes = null
        soon.clear()
    }
}

class K12SyncController(private val scheduler: SyncScheduler) {
    var paired: Boolean = false
        private set

    fun onForeground() {
        if (!paired) return
        schedule(SyncTrigger.FOREGROUND)
    }

    fun onPaired() {
        paired = true
        schedule(SyncTrigger.PAIRED)
    }

    fun onUnpair() {
        paired = false
        scheduler.cancel()
    }

    fun onQueueDepth(pending: Int) {
        if (paired && pending >= SyncSchedule.QUEUE_FLUSH_COUNT) {
            scheduler.enqueueSoon(SyncTrigger.QUEUE)
        }
    }

    private fun schedule(trigger: SyncTrigger) {
        scheduler.ensurePeriodic(SyncSchedule.PERIODIC_MINUTES)
        scheduler.enqueueSoon(trigger)
    }
}

data class PairedEndpoint(
    val baseUrl: String,
    val deviceId: String,
    val childId: String,
    val certFingerprint: String? = null,
)

sealed class SyncRunResult {
    data object Ok : SyncRunResult()
    data object Skipped : SyncRunResult()
    data object NotPaired : SyncRunResult()
    data class Stop(val code: Int) : SyncRunResult()
    data class Retry(val code: Int) : SyncRunResult()
}

/** Thrown when the TLS certificate does not match the fingerprint from pairing. */
class CertificatePinException : Exception()

/**
 * Overlapping runs collapse into the one already executing. The in-flight run
 * repeats once if another trigger arrived, and does not start a second client.
 */
class SyncGate {
    private val lock = Any()
    private var running = false
    private var again = false

    fun run(block: () -> SyncRunResult): SyncRunResult {
        synchronized(lock) {
            if (running) {
                again = true
                return SyncRunResult.Skipped
            }
            running = true
        }
        try {
            var last: SyncRunResult = SyncRunResult.Ok
            while (true) {
                synchronized(lock) { again = false }
                last = block()
                val repeat = synchronized(lock) { again }
                if (!repeat) return last
                if (last !is SyncRunResult.Ok) return last
            }
        } finally {
            synchronized(lock) { running = false }
        }
    }
}

interface DeviceConfigSink {
    fun appliedVersion(): Int
    fun apply(version: Int, fields: Map<String, Any>)
}

class MemoryConfigSink : DeviceConfigSink {
    var version: Int = 0
    var fields: Map<String, Any> = emptyMap()
    override fun appliedVersion(): Int = version
    override fun apply(version: Int, fields: Map<String, Any>) {
        this.version = version
        this.fields = fields
    }
}

data class PendingStudyEvent(
    val eventId: String,
    val seq: Int,
    val type: String,
    val occurredAtMs: Long,
    val payload: Map<String, Any?>,
) {
    fun toJson(deviceId: String, childId: String): JSONObject {
        val payloadJson = JSONObject()
        payload.forEach { (key, value) ->
            if (value != null) payloadJson.put(key, value)
        }
        return JSONObject()
            .put("event_id", eventId)
            .put("schema_version", 1)
            .put("device_id", deviceId)
            .put("child_id", childId)
            .put("type", type)
            .put("occurred_at", occurredAtMs)
            .put("tz", com.openminis.k12.Identity.ZONE_ID)
            .put("seq", seq)
            .put("payload", payloadJson)
    }
}

interface StudyEventOutbox {
    fun add(event: PendingStudyEvent)
    fun pending(): List<PendingStudyEvent>
    fun ack(ids: Collection<String>)
    fun clear()
}

class MemoryStudyEventOutbox : StudyEventOutbox {
    private val items = mutableListOf<PendingStudyEvent>()
    private var nextSeq = 1

    override fun add(event: PendingStudyEvent) {
        if (items.any { it.eventId == event.eventId }) return
        val seq = if (event.seq > 0) event.seq else nextSeq
        nextSeq = maxOf(nextSeq, seq + 1)
        items += event.copy(seq = seq)
    }

    override fun pending(): List<PendingStudyEvent> = items.toList()

    override fun ack(ids: Collection<String>) {
        val drop = ids.toSet()
        items.removeAll { it.eventId in drop }
    }

    override fun clear() {
        items.clear()
        nextSeq = 1
    }
}

data class PendingDiagnostic(val id: String, val json: String)

interface DiagnosticOutbox {
    fun add(item: PendingDiagnostic): Boolean
    fun pending(): List<PendingDiagnostic>
    fun ack(id: String)
    fun drop(id: String)
    fun clear()
}

class MemoryDiagnosticOutbox : DiagnosticOutbox {
    private val items = mutableListOf<PendingDiagnostic>()

    override fun add(item: PendingDiagnostic): Boolean {
        if (!diagnosticAllowed(item.json)) return false
        if (items.any { it.id == item.id }) return true
        items += item
        return true
    }

    override fun pending(): List<PendingDiagnostic> = items.toList()

    override fun ack(id: String) {
        items.removeAll { it.id == id }
    }

    override fun drop(id: String) = ack(id)

    override fun clear() {
        items.clear()
    }
}

fun diagnosticAllowed(json: String): Boolean {
    return !json.contains("problem_text") &&
        !json.contains("chat_transcript") &&
        !json.contains("image_data")
}

/**
 * One sync pass: heartbeat, apply config, tablet-model fetch when
 * [ModelConfigSchedule] allows it, then queued events and diagnostics.
 * The API key is never logged.
 */
class K12SyncCycle(
    private val client: SyncClient,
    private val config: DeviceConfigSink,
    private val events: StudyEventOutbox,
    private val diagnostics: DiagnosticOutbox,
    private val onModelVersion: (Long, Boolean, () -> ModelFetch) -> com.openminis.k12.model.SyncOutcome,
    private val appVersion: String,
    private val deviceId: String,
    private val childId: String,
    private val dailyUsedMinutes: Int = 0,
    private val permission: JSONObject? = null,
    private val log: ModelLog? = null,
    private val modelAppliedVersion: () -> Long = { 0L },
    private val clock: () -> Long = { System.currentTimeMillis() },
    initialModelFetchAtMs: Long = 0L,
    private val onModelFetched: (Long) -> Unit = {},
) {
    var lastModelFetchAtMs: Long = initialModelFetchAtMs
        private set
    fun run(): SyncRunResult {
        val posted = try {
            client.postHeartbeat(config.appliedVersion(), appVersion, dailyUsedMinutes, permission)
        } catch (_: CertificatePinException) {
            return SyncRunResult.Stop(0)
        } catch (_: Exception) {
            return SyncRunResult.Retry(0)
        }
        when (posted.code) {
            200 -> Unit
            401, 403 -> return SyncRunResult.Stop(posted.code)
            429 -> return SyncRunResult.Retry(posted.code)
            else -> return SyncRunResult.Retry(posted.code)
        }
        val beat = try {
            client.parseHeartbeat(posted.bodyText, config.appliedVersion())
        } catch (_: Exception) {
            return SyncRunResult.Retry(posted.code)
        }
        if (beat.revoked) return SyncRunResult.Stop(401)
        if (beat.configVersion > config.appliedVersion() && beat.config.isNotEmpty()) {
            config.apply(beat.configVersion, beat.config)
        }
        log?.info("heartbeat version=${beat.configVersion} model=${beat.tabletModelVersion}")
        val modelResult = pullModelIfDue(beat.tabletModelVersion)
        if (modelResult != null) return modelResult
        val uploaded = uploadEvents()
        if (uploaded !is SyncRunResult.Ok) return uploaded
        return uploadDiagnostics()
    }

    /**
     * Null means the pass should continue with uploads. A non-null result stops it.
     * Same-version pulls inside [ModelConfigSchedule.INTERVAL_MS] do not call the network.
     */
    private fun pullModelIfDue(announced: Long): SyncRunResult? {
        val now = clock()
        if (!ModelConfigSchedule.shouldFetch(announced, modelAppliedVersion(), lastModelFetchAtMs, now)) {
            return null
        }
        var modelDenied = 0
        val outcome = try {
            onModelVersion(announced, announced == modelAppliedVersion()) {
                val resp = client.fetchTabletModel()
                when (resp.code) {
                    200 -> ModelFetch.Body(resp.bodyText)
                    401, 403 -> {
                        modelDenied = resp.code
                        ModelFetch.Denied(resp.code)
                    }
                    else -> ModelFetch.Failed(resp.code)
                }
            }
        } catch (_: CertificatePinException) {
            return SyncRunResult.Stop(0)
        } catch (_: Exception) {
            return SyncRunResult.Retry(0)
        }
        if (modelDenied == 401 || modelDenied == 403) return SyncRunResult.Stop(modelDenied)
        if (outcome is com.openminis.k12.model.SyncOutcome.Applied ||
            outcome is com.openminis.k12.model.SyncOutcome.Cleared
        ) {
            lastModelFetchAtMs = now
            onModelFetched(now)
        }
        return null
    }

    private fun uploadEvents(): SyncRunResult {
        val pending = events.pending()
        var index = 0
        while (index < pending.size) {
            val end = minOf(index + SyncSchedule.MAX_EVENT_BATCH, pending.size)
            val batch = pending.subList(index, end).toList()
            val body = JSONArray()
            batch.forEach { body.put(it.toJson(deviceId, childId)) }
            val resp = try {
                client.uploadEvents(body)
            } catch (_: Exception) {
                return SyncRunResult.Retry(0)
            }
            when (resp.code) {
                200 -> events.ack(batch.map { it.eventId })
                401, 403 -> return SyncRunResult.Stop(resp.code)
                429 -> return SyncRunResult.Retry(resp.code)
                else -> return SyncRunResult.Retry(resp.code)
            }
            index = end
        }
        return SyncRunResult.Ok
    }

    private fun uploadDiagnostics(): SyncRunResult {
        for (item in diagnostics.pending()) {
            if (!diagnosticAllowed(item.json)) {
                diagnostics.drop(item.id)
                continue
            }
            val resp = try {
                client.uploadDiagnostics(JSONObject(item.json))
            } catch (_: IllegalArgumentException) {
                diagnostics.drop(item.id)
                continue
            } catch (_: Exception) {
                return SyncRunResult.Retry(0)
            }
            when (resp.code) {
                200, 202 -> diagnostics.ack(item.id)
                401, 403 -> return SyncRunResult.Stop(resp.code)
                429 -> return SyncRunResult.Retry(resp.code)
                else -> return SyncRunResult.Retry(resp.code)
            }
        }
        return SyncRunResult.Ok
    }
}
