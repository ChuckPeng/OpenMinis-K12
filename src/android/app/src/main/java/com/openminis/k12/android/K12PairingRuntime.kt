package com.openminis.k12.android

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.openminis.k12.device.DevicePairing
import com.openminis.k12.device.DeviceStatus
import com.openminis.k12.device.PairedEndpoint
import com.openminis.k12.device.PairingClient
import com.openminis.k12.device.PairingOutcome
import com.openminis.k12.device.PairingText

/**
 * Android pairing: keystore key, HTTPS transport, persisted pending record,
 * then [DevicePairing.noteActivated] when the parent confirms.
 *
 * [pending], [endpoint] and [notice] are Compose state. Screens read them
 * during composition so a confirmation that arrives after navigation or
 * process start updates the waiting view.
 */
object K12PairingRuntime {
    val pending = mutableStateOf<PairingOutcome.Pending?>(null)
    val endpoint = mutableStateOf<PairedEndpoint?>(null)
    val notice = mutableStateOf<String?>(null)

    private val lock = Any()
    private var generation = 0

    fun currentEndpoint(): PairedEndpoint? = endpoint.value ?: K12SyncRuntime.currentEndpoint()

    fun restore(storedPending: PairingOutcome.Pending?, storedEndpoint: PairedEndpoint?) {
        pending.value = storedPending
        endpoint.value = storedEndpoint
        notice.value = if (storedPending != null) PairingText.PENDING else null
    }

    fun submit(
        context: Context,
        baseUrl: String,
        pairCode: String,
        childId: String,
        certFingerprint: String? = null,
    ): PairingOutcome {
        val client = client(context, certFingerprint) ?: return PairingOutcome.Failed(PairingText.NO_KEY)
        val outcome = client.submit(baseUrl, pairCode, childId, certFingerprint)
        return adopt(context, outcome)
    }

    fun refresh(context: Context, pending: PairingOutcome.Pending): PairingOutcome =
        poll(context, pending, waitSeconds = 0)

    /**
     * One status check. Safe to call when nothing is pending: returns null.
     * Holds [lock] so a screen loop and a background job do not double-apply.
     */
    fun pollNow(context: Context, waitSeconds: Int): PairingOutcome? {
        val current = pending.value ?: K12SyncRuntime.loadPending() ?: return null
        return poll(context, current, waitSeconds)
    }

    fun cancel(context: Context) {
        synchronized(lock) { generation++ }
        K12SyncRuntime.clearPending()
        pending.value = null
        K12Runtime.clearPendingPairing()
        notice.value = null
    }

    private fun poll(context: Context, current: PairingOutcome.Pending, waitSeconds: Int): PairingOutcome {
        val gen = synchronized(lock) { generation }
        val client = client(context, current.certFingerprint) ?: return PairingOutcome.Failed(PairingText.NO_KEY)
        val outcome = client.poll(current, waitSeconds)
        if (synchronized(lock) { gen != generation }) return outcome
        return adopt(context, outcome)
    }

    private fun adopt(context: Context, outcome: PairingOutcome): PairingOutcome {
        when (outcome) {
            is PairingOutcome.Active -> {
                pending.value = null
                endpoint.value = outcome.endpoint
                notice.value = null
                K12SyncRuntime.clearPending()
                DevicePairing.noteActivated(outcome.endpoint, DeviceStatus.ACTIVE)
            }
            is PairingOutcome.Pending -> {
                K12SyncRuntime.savePending(outcome)
                pending.value = outcome
                notice.value = PairingText.PENDING
                K12Runtime.markPending(outcome)
                K12SyncRuntime.ensurePairPoll()
            }
            is PairingOutcome.Failed -> {
                notice.value = outcome.message
                if (outcome.message == PairingText.DENIED) {
                    pending.value = null
                    K12SyncRuntime.clearPending()
                    K12Runtime.clearPendingPairing()
                }
            }
        }
        return outcome
    }

    fun unpair() {
        synchronized(lock) { generation++ }
        pending.value = null
        endpoint.value = null
        notice.value = null
        K12Runtime.unpairOrReset()
    }

    private fun client(context: Context, certFingerprint: String?): PairingClient? {
        DeviceSigningKey.ensure(context)
        val signer = DeviceSigningKey.signer(context) ?: return null
        val pubkey = DeviceSigningKey.publicKeyBase64(context) ?: return null
        return PairingClient(
            transport = OkHttpSyncTransport(certFingerprint),
            signer = signer,
            devicePubkey = pubkey,
        )
    }
}
