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
}
