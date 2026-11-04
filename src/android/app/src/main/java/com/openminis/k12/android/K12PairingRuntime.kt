package com.openminis.k12.android

import android.content.Context
import com.openminis.k12.device.DevicePairing
import com.openminis.k12.device.DeviceStatus
import com.openminis.k12.device.PairedEndpoint
import com.openminis.k12.device.PairingClient
import com.openminis.k12.device.PairingOutcome
import com.openminis.k12.device.PairingText

/** Android pairing: keystore key, HTTPS transport, then [DevicePairing.noteActivated]. */
object K12PairingRuntime {
    fun currentEndpoint(): PairedEndpoint? = K12SyncRuntime.currentEndpoint()

    fun submit(
        context: Context,
        baseUrl: String,
        pairCode: String,
        childId: String,
        certFingerprint: String? = null,
    ): PairingOutcome {
        val client = client(context, certFingerprint) ?: return PairingOutcome.Failed(PairingText.NO_KEY)
        val outcome = client.submit(baseUrl, pairCode, childId, certFingerprint)
        if (outcome is PairingOutcome.Active) {
            DevicePairing.noteActivated(outcome.endpoint, DeviceStatus.ACTIVE)
        }
        return outcome
    }

    fun refresh(context: Context, pending: PairingOutcome.Pending): PairingOutcome {
        val client = client(context, pending.certFingerprint) ?: return PairingOutcome.Failed(PairingText.NO_KEY)
        val outcome = client.poll(pending)
        if (outcome is PairingOutcome.Active) {
            DevicePairing.noteActivated(outcome.endpoint, DeviceStatus.ACTIVE)
        }
        return outcome
    }

    fun unpair() {
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
