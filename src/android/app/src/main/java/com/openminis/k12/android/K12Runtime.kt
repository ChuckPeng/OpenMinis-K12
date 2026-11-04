package com.openminis.k12.android

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.openminis.k12.K12State
import com.openminis.k12.ParentGate
import com.openminis.k12.StudyEntry
import com.openminis.k12.PermissionReport
import com.openminis.k12.PinResult
import com.openminis.k12.ScreenTime
import com.openminis.k12.SecretSubmit
import com.openminis.k12.StudyStage
import com.openminis.k12.Wizard

/**
 * Process-wide K12 state. Upstream unit tests never call [install], and the
 * gates stay open until it does so they keep their existing behavior.
 */
object K12Runtime {
    @Volatile
    var installed: Boolean = false
        private set

    private var state: K12State = K12State()

    /** Read during composition so screens refresh after each change. */
    val ui = mutableStateOf(K12State())

    private var prefs: K12Prefs? = null
    private var probe: K12PermissionProbe? = null

    @Volatile
    var localProviderReady: Boolean = false
        private set

    fun install(context: Context) {
        val app = context.applicationContext
        val store = K12Prefs(app)
        prefs = store
        probe = K12PermissionProbe(app)
        state = ScreenTime.discardStaleAnchor(store.load(), System.currentTimeMillis())
        installed = true
        persist()
    }

    fun snapshot(): K12State = state

    fun onboardingComplete(): Boolean = !installed || state.profile.onboardingComplete

    fun parentUnlocked(): Boolean = installed && state.parent.unlocked

    fun enforcing(): Boolean = installed && !state.parent.unlocked

    fun studyLocked(now: Long = System.currentTimeMillis()): Boolean =
        installed && state.profile.onboardingComplete && ScreenTime.limitReached(state, now)

    fun setLocalProviderReady(ready: Boolean) {
        localProviderReady = ready
    }

    fun chatReady(): Boolean = StudyEntry.chatReady(
        managed = com.openminis.k12.model.K12ModelRuntime.managed(),
        localReady = localProviderReady,
    )

    fun coldStartRoute(): String = StudyEntry.destination(
        onboardingComplete = state.profile.onboardingComplete,
        paired = state.profile.pairing == "active",
        chatReady = chatReady(),
        studyLocked = studyLocked(),
    )

    fun chooseStage(stage: StudyStage) = update { Wizard.chooseStage(it, stage) }

    fun openIdentity() = update { Wizard.openIdentity(it) }

    fun setNickname(raw: String) = update { Wizard.setNickname(it, raw) }

    fun openSecrets() = update { Wizard.openSecrets(it) }

    fun openPairing() = update { Wizard.openPairing(it) }

    fun submitSecrets(pin: String, pinAgain: String, backup: String, backupAgain: String): SecretSubmit {
        val result = Wizard.submitSecrets(state, pin, pinAgain, backup, backupAgain)
        if (result is SecretSubmit.Accepted) {
            state = result.state
            persist()
        }
        return result
    }

    fun completeOnboarding() = update { Wizard.complete(it) }

    fun permissionReport(): PermissionReport =
        probe?.read() ?: PermissionReport(false, false, false, false)

    fun unlock(pin: String, now: Long = System.currentTimeMillis()): PinResult =
        applyPin(ParentGate.unlock(state, pin, now))

    fun lock() = update { ParentGate.lock(it) }

    fun touch(now: Long = System.currentTimeMillis()) = update { ParentGate.touch(it, now) }

    fun grantWithPin(pin: String, now: Long = System.currentTimeMillis()): PinResult =
        applyPin(ParentGate.grantWithPin(state, pin, now))

    fun grantWhileUnlocked(now: Long = System.currentTimeMillis()) =
        update { ParentGate.grantWhileUnlocked(it, now) }

    fun updateLimits(dailyMin: Int, weekendExtraMin: Int, breakMin: Int): Boolean {
        val next = ParentGate.updateLimits(state, dailyMin, weekendExtraMin, breakMin) ?: return false
        state = next
        persist()
        return true
    }

    fun onForeground(now: Long = System.currentTimeMillis()) {
        advance { ScreenTime.onForeground(it, now) }
        K12SyncRuntime.onForeground()
    }

    fun permissionReportOrNull(): PermissionReport? = if (installed) probe?.read() else null

    /** Pairing reached `active`. Schedules the periodic sync and runs one pass now. */
    fun markPaired(endpoint: com.openminis.k12.device.PairedEndpoint) {
        if (installed) {
            state = state.copy(
                profile = state.profile.copy(
                    pairing = "active",
                    deviceId = endpoint.deviceId,
                    childId = endpoint.childId.ifBlank { state.profile.childId },
                ),
            )
            persist()
        }
        K12SyncRuntime.onPairingSucceeded(endpoint)
    }

    /** Applies time limits from a heartbeat without requiring the parent session to be open. */
    fun applyServerPolicy(patch: com.openminis.k12.device.ServerPolicyPatch) {
        val stage = patch.stageStored?.let { StudyStage.fromStored(it) }
        if (!installed) return
        val current = ScreenTime.limits(state.profile.stage, state.profile)
        val merged = com.openminis.k12.device.mergeServerLimits(
            com.openminis.k12.device.LimitTriple(current.dailyMin, current.weekendExtraMin, current.breakMin),
            patch,
        )
        var profile = state.profile
        if (merged != null) {
            profile = profile.copy(
                dailyLimitOverrideMin = merged.daily,
                weekendExtraOverrideMin = merged.weekend,
                breakOverrideMin = merged.breakMin,
            )
        }
        if (stage != null) profile = profile.copy(stage = stage)
        if (profile != state.profile) {
            state = state.copy(profile = profile)
            persist()
        }
    }

    fun onBackground(now: Long = System.currentTimeMillis()) = advance { ScreenTime.onBackground(it, now) }

    /** Returns true when the parent session was locked by the idle timer. */
    fun tick(now: Long = System.currentTimeMillis()): Boolean {
        if (!installed) return false
        val wasUnlocked = state.parent.unlocked
        advance { ScreenTime.onTick(it, now) }
        val expired = ParentGate.expireIdle(state, now)
        if (expired.parent.unlocked != state.parent.unlocked || expired != state) {
            state = expired
            persist()
        }
        return wasUnlocked && !state.parent.unlocked
    }

    fun onEnteredChildFacing() {
        if (state.parent.unlocked) lock()
    }

    /** Local unpair or reset. Drops the server-delivered model key. */
    fun unpairOrReset() {
        if (installed) {
            state = state.copy(profile = state.profile.copy(pairing = "unpaired"))
            persist()
        }
        K12SyncRuntime.onUnpairOrReset()
        com.openminis.k12.model.K12ModelRuntime.onUnpairOrReset()
    }

    private fun advance(block: (K12State) -> K12State) {
        if (!installed) return
        val before = state.clockAnomaly
        state = block(state)
        if (state.clockAnomaly && !before) {
            Log.i(TAG, "clock anomaly")
            state = state.copy(clockAnomaly = false)
        }
        persist()
    }

    private fun applyPin(result: PinResult): PinResult {
        state = when (result) {
            is PinResult.Accepted -> result.state
            is PinResult.Rejected -> result.state
        }
        persist()
        return result
    }

    private fun update(block: (K12State) -> K12State) {
        if (!installed) return
        state = block(state)
        persist()
    }

    private fun persist() {
        ui.value = state
        prefs?.save(state)
    }

    private const val TAG = "K12"
}
