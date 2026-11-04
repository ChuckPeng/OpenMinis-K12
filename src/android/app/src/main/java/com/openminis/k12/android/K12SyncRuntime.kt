package com.openminis.k12.android

import android.content.Context
import com.openminis.k12.K12ServerPolicy
import com.openminis.k12.device.DevicePairing
import com.openminis.k12.device.DeviceSigner
import com.openminis.k12.device.K12SyncController
import com.openminis.k12.device.K12SyncCycle
import com.openminis.k12.device.PairedEndpoint
import com.openminis.k12.device.PairingOutcome
import com.openminis.k12.device.SyncClient
import com.openminis.k12.device.SyncConfig
import com.openminis.k12.device.SyncGate
import com.openminis.k12.device.SyncRunResult
import com.openminis.k12.device.SyncScheduler
import com.openminis.k12.device.serverPolicyPatch
import com.openminis.k12.device.toPending
import com.openminis.k12.model.K12ModelRuntime
import com.openminis.k12.tutoring.TutoringRuntime
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Process-wide sync schedule. Upstream unit tests never call [install].
 */
object K12SyncRuntime {
    private val gate = SyncGate()
    private var scheduler: SyncScheduler = NoopScheduler
    private var controller = K12SyncController(scheduler)
    private var prefs: K12SyncPrefs? = null
    private var appContext: Context? = null
    private var workScheduler: WorkManagerSyncScheduler? = null
    private var cycleOverride: (() -> SyncRunResult)? = null
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "k12-sync").apply { isDaemon = true }
    }

    fun install(context: Context) {
        val app = context.applicationContext
        appContext = app
        val store = K12SyncPrefs(app)
        prefs = store
        val work = WorkManagerSyncScheduler(app)
        workScheduler = work
        scheduler = work
        controller = K12SyncController(work)
        DevicePairing.onActivated = { endpoint -> K12Runtime.markPaired(endpoint) }
        TutoringRuntime.events.onAppend = { event ->
            store.events.add(event.toPending())
            controller.onQueueDepth(store.events.pending().size)
        }
        val endpoint = store.loadEndpoint()
        val pending = store.loadPending()
        K12PairingRuntime.restore(pending, endpoint)
        if (endpoint != null && K12Runtime.snapshot().profile.pairing == "active") {
            controller.onPaired()
        }
        if (pending != null) {
            if (K12Runtime.snapshot().profile.pairing != "pending") {
                K12Runtime.markPending(pending)
            }
            work.ensurePairPoll()
            pollPendingAsync(0)
        }
    }

    fun currentEndpoint(): com.openminis.k12.device.PairedEndpoint? = prefs?.loadEndpoint()

    fun loadPending(): PairingOutcome.Pending? = prefs?.loadPending()

    fun savePending(pending: PairingOutcome.Pending) {
        prefs?.savePending(pending)
        ensurePairPoll()
    }

    fun clearPending() {
        prefs?.clearPending()
        workScheduler?.cancelPairPoll()
    }

    fun ensurePairPoll() {
        workScheduler?.ensurePairPoll()
    }

    fun pollPendingAsync(waitSeconds: Int) {
        io.execute { pollPending(waitSeconds) }
    }

    fun pollPending(waitSeconds: Int) {
        val context = appContext ?: return
        if (loadPending() == null && K12PairingRuntime.pending.value == null) return
        K12PairingRuntime.pollNow(context, waitSeconds)
    }

    fun enqueueAlert(json: String) {
        val store = prefs ?: return
        val items = store.loadAlerts().toMutableList()
        if (json !in items) items += json
        store.saveAlerts(items)
        requestSync()
    }

    fun requestSync() {
        io.execute { runOnce() }
    }

    fun onForeground() {
        controller.onForeground()
        if (loadPending() != null) pollPendingAsync(0)
    }

    fun onPairingSucceeded(endpoint: PairedEndpoint) {
        appContext?.let { DeviceSigningKey.ensure(it) }
        prefs?.saveEndpoint(endpoint)
        K12PairingRuntime.endpoint.value = endpoint
        K12PairingRuntime.pending.value = null
        controller.onPaired()
        requestSync()
    }

    fun onUnpairOrReset() {
        controller.onUnpair()
        prefs?.clearAll()
        appContext?.let { DeviceSigningKey.delete(it) }
        TutoringRuntime.events.onAppend = null
    }

    fun runOnce(): SyncRunResult {
        val override = cycleOverride
        if (override != null) return gate.run { override() }
        val endpoint = prefs?.loadEndpoint()
        if (endpoint == null || !controller.paired) return SyncRunResult.NotPaired
        val context = appContext ?: return SyncRunResult.NotPaired
        val signer = DeviceSigningKey.signer(context) ?: return SyncRunResult.NotPaired
        return gate.run { execute(endpoint, signer) }
    }

    internal fun installForTest(scheduler: SyncScheduler, run: () -> SyncRunResult) {
        this.scheduler = scheduler
        controller = K12SyncController(scheduler)
        cycleOverride = run
        prefs = null
        appContext = null
    }

    internal fun resetForTest() {
        cycleOverride = null
        scheduler = NoopScheduler
        controller = K12SyncController(scheduler)
        prefs = null
        appContext = null
        TutoringRuntime.events.onAppend = null
        DevicePairing.onActivated = null
    }

    internal fun controllerForTest(): K12SyncController = controller

    private fun execute(endpoint: PairedEndpoint, signer: DeviceSigner.Signer): SyncRunResult {
        val store = prefs ?: return SyncRunResult.NotPaired
        val client = SyncClient(
            config = SyncConfig(
                baseUrl = endpoint.baseUrl,
                deviceId = endpoint.deviceId,
                pinnedCertFingerprint = endpoint.certFingerprint,
            ),
            signer = signer,
            transport = OkHttpSyncTransport(endpoint.certFingerprint),
        )
        val sink = object : com.openminis.k12.device.DeviceConfigSink {
            override fun appliedVersion(): Int = store.appliedVersion()
            override fun apply(version: Int, fields: Map<String, Any>) {
                val patch = serverPolicyPatch(fields)
                K12ServerPolicy.apply(patch)
                K12SafetyRuntime.hotlineText = K12ServerPolicy.hotlineText
                K12SafetyRuntime.hotlineNumber = K12ServerPolicy.hotlineNumber
                K12Runtime.applyServerPolicy(patch)
                store.setAppliedVersion(version)
            }
        }
        val usedMinutes = if (K12Runtime.installed) {
            (K12Runtime.snapshot().ledger.usedMs / 60_000L).toInt()
        } else {
            0
        }
        val permission = K12Runtime.permissionReportOrNull()?.let { report ->
            JSONObject()
                .put("notifications_enabled", report.notificationsEnabled)
                .put("battery_unrestricted", report.batteryUnrestricted)
                .put("background_allowed", report.backgroundAllowed)
                .put("exact_alarm_allowed", report.exactAlarmAllowed)
        }
        val cycle = K12SyncCycle(
            client = client,
            config = sink,
            events = store.events,
            diagnostics = store.diagnostics,
            onModelVersion = { version, force, fetch ->
                K12ModelRuntime.noteHeartbeatVersion(version, fetch, force = force)
            },
            appVersion = com.openminis.k12.Version.NAME,
            deviceId = endpoint.deviceId,
            childId = endpoint.childId,
            dailyUsedMinutes = usedMinutes,
            permission = permission,
            modelAppliedVersion = { com.openminis.k12.model.K12ModelRuntime.pageStatus().version },
            initialModelFetchAtMs = store.lastModelFetchAt(),
            onModelFetched = { at -> store.setLastModelFetchAt(at) },
        )
        val result = cycle.run()
        if (result is SyncRunResult.Ok) flushAlerts(client)
        return result
    }

    private fun flushAlerts(client: SyncClient) {
        val store = prefs ?: return
        val left = mutableListOf<String>()
        for (raw in store.loadAlerts()) {
            val posted = try {
                client.postAlert(JSONObject(raw))
            } catch (_: Exception) {
                left += raw
                continue
            }
            if (posted.code != 200 && posted.code != 201 && posted.code != 202) left += raw
        }
        store.saveAlerts(left)
    }

    private object NoopScheduler : SyncScheduler {
        override fun ensurePeriodic(intervalMinutes: Long) = Unit
        override fun enqueueSoon(trigger: com.openminis.k12.device.SyncTrigger) = Unit
        override fun cancel() = Unit
    }
}
