package com.openminis.k12.tutoring

import com.openminis.k12.PinResult
import com.openminis.k12.Profile
import com.openminis.k12.Secrets

/**
 * Local reveal unlock for M2 (G3/G4). PIN required; TOTP optional.
 * Works fully offline — no server round-trip (G4).
 */
object RevealAuth {
    private val usedTotp = mutableSetOf<String>()

    fun clearTotpCache() = usedTotp.clear()

    fun unlockWithPin(
        state: TutoringState,
        profile: Profile,
        pin: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<TutoringState, ReduceResult> {
        if (!TutoringMachine.canApplyForReveal(state) && !state.layer.chainComplete()) {
            val rejected = ReduceResult(state, rejected = true, message = "not ready")
            return state to rejected
        }
        if (nowMs < profile.pinLockedUntilMs) {
            return state to ReduceResult(state, rejected = true, message = "pin locked")
        }
        if (!Secrets.matches(pin, profile.pinSalt, profile.pinHash)) {
            return state to ReduceResult(state, rejected = true, message = "bad pin")
        }
        val granted = TutoringMachine.reduce(state, TutoringAction.GrantReveal(RevealMethod.PIN), nowMs)
        return granted.state to granted
    }

    fun unlockWithTotp(
        state: TutoringState,
        totpSecret: ByteArray?,
        code: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<TutoringState, ReduceResult> {
        if (totpSecret == null || totpSecret.isEmpty()) {
            return state to ReduceResult(state, rejected = true, message = "totp not configured")
        }
        if (!state.layer.chainComplete() || !state.stemConfirmed) {
            return state to ReduceResult(state, rejected = true, message = "not ready")
        }
        if (!Totp.verify(totpSecret, code, nowMs, usedCodes = usedTotp)) {
            return state to ReduceResult(state, rejected = true, message = "bad totp")
        }
        val granted = TutoringMachine.reduce(state, TutoringAction.GrantReveal(RevealMethod.TOTP), nowMs)
        return granted.state to granted
    }

    fun mapPinGate(result: PinResult): Boolean = result is PinResult.Accepted

    fun unlockWithRemoteGrant(
        state: TutoringState,
        grant: Grant,
        serverPubkey: String,
        localDeviceId: String,
        consumedGrantIds: MutableSet<String>,
        verifier: GrantSignatureVerifier = DefaultGrantVerifier,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<TutoringState, ReduceResult> {
        if (!TutoringMachine.canApplyForReveal(state) && !state.layer.chainComplete()) {
            return state to ReduceResult(state, rejected = true, message = "not ready")
        }
        if (!RemoteUnlock.verifyGrant(grant, serverPubkey, localDeviceId, state.questionHash, consumedGrantIds, verifier)) {
            return state to ReduceResult(state, rejected = true, message = "invalid grant")
        }
        consumedGrantIds.add(grant.grantId)
        val granted = TutoringMachine.reduce(state, TutoringAction.GrantReveal(RevealMethod.REMOTE), nowMs)
        return granted.state to granted
    }

    fun unlockWithBackupCode(
        state: TutoringState,
        backupVerifier: OfflineBackupCode,
        code: String,
        requestId: String,
        requestCreatedAtMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<TutoringState, ReduceResult> {
        if (!TutoringMachine.canApplyForReveal(state) && !state.layer.chainComplete()) {
            return state to ReduceResult(state, rejected = true, message = "not ready")
        }
        val result = backupVerifier.verify(code, requestId, state.questionHash, requestCreatedAtMs, nowMs)
        if (result !is OfflineBackupCode.VerifyResult.Accepted) {
            return state to ReduceResult(state, rejected = true, message = "backup code rejected: $result")
        }
        val granted = TutoringMachine.reduce(state, TutoringAction.GrantReveal(RevealMethod.UNLOCK_CODE), nowMs)
        return granted.state to granted
    }
}
