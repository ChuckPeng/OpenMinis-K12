package com.openminis.k12

sealed class SecretSubmit {
    data class Accepted(val state: K12State) : SecretSubmit()
    data class Rejected(val reason: String) : SecretSubmit()
}

sealed class PinResult {
    data class Accepted(val state: K12State) : PinResult()
    data class Rejected(val state: K12State, val locked: Boolean) : PinResult()
}

object Wizard {
    fun chooseStage(state: K12State, stage: StudyStage): K12State =
        state.copy(profile = state.profile.copy(stage = stage, onboardingStep = OnboardingStep.STAGE))

    fun openIdentity(state: K12State): K12State {
        if (state.profile.stage == null) return state
        val withIds = ensureIds(state)
        return withIds.copy(profile = withIds.profile.copy(onboardingStep = OnboardingStep.IDENTITY))
    }

    /** Keeps an existing v4 id. A second call does not mint another one. */
    fun ensureIds(state: K12State): K12State {
        var profile = state.profile
        if (!Ids.isUuidV4(profile.childId)) profile = profile.copy(childId = Ids.newId())
        if (!Ids.isUuidV4(profile.deviceId)) profile = profile.copy(deviceId = Ids.newId())
        return state.copy(profile = profile)
    }

    fun setNickname(state: K12State, raw: String): K12State =
        state.copy(profile = state.profile.copy(nickname = normalizeNickname(raw)))

    fun openSecrets(state: K12State): K12State {
        if (!Ids.isUuidV4(state.profile.childId)) return state
        return state.copy(profile = state.profile.copy(onboardingStep = OnboardingStep.SECRETS))
    }

    fun submitSecrets(state: K12State, pin: String, pinAgain: String, backup: String, backupAgain: String): SecretSubmit {
        if (!Secrets.acceptablePin(pin)) return SecretSubmit.Rejected("pin")
        if (pin != pinAgain) return SecretSubmit.Rejected("pin_mismatch")
        if (!Secrets.acceptableBackup(backup)) return SecretSubmit.Rejected("backup")
        if (backup != backupAgain) return SecretSubmit.Rejected("backup_mismatch")
        val pinSalt = Secrets.newSalt()
        val backupSalt = Secrets.newSalt()
        val profile = state.profile.copy(
            pinSalt = pinSalt.toHex(),
            pinHash = Secrets.hash(pin, pinSalt),
            backupSalt = backupSalt.toHex(),
            backupHash = Secrets.hash(backup, backupSalt),
            pinFailures = 0,
            pinLockedUntilMs = 0,
            onboardingStep = OnboardingStep.PERMISSIONS,
        )
        return SecretSubmit.Accepted(state.copy(profile = profile))
    }

    fun canLeaveSecrets(profile: Profile): Boolean = profile.hasPin() && !profile.backupHash.isNullOrBlank()

    fun complete(state: K12State): K12State {
        if (!canLeaveSecrets(state.profile)) return state
        if (state.profile.stage == null || !Ids.isUuidV4(state.profile.childId)) return state
        return state.copy(
            profile = state.profile.copy(
                onboardingComplete = true,
                onboardingStep = OnboardingStep.DONE,
            ),
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

object ParentGate {
    fun checkPin(state: K12State, pin: String, now: Long): PinResult {
        val profile = state.profile
        if (now < profile.pinLockedUntilMs) {
            return PinResult.Rejected(state, locked = true)
        }
        if (Secrets.matches(pin, profile.pinSalt, profile.pinHash)) {
            val cleared = profile.copy(pinFailures = 0, pinLockedUntilMs = 0)
            return PinResult.Accepted(state.copy(profile = cleared))
        }
        val failures = profile.pinFailures + 1
        val lockedUntil = if (failures >= Secrets.FAILURES_BEFORE_LOCK) now + Secrets.LOCK_MS else 0L
        val next = state.copy(
            profile = profile.copy(pinFailures = failures, pinLockedUntilMs = lockedUntil),
        )
        return PinResult.Rejected(next, locked = lockedUntil > 0)
    }

    fun unlock(state: K12State, pin: String, now: Long): PinResult {
        return when (val checked = checkPin(state, pin, now)) {
            is PinResult.Rejected -> checked
            is PinResult.Accepted -> PinResult.Accepted(
                checked.state.copy(parent = ParentSession(unlocked = true, lastTouchMs = now)),
            )
        }
    }

    fun lock(state: K12State): K12State = state.copy(parent = ParentSession())

    fun touch(state: K12State, now: Long): K12State {
        if (!state.parent.unlocked) return state
        return state.copy(parent = state.parent.copy(lastTouchMs = now))
    }

    fun expireIdle(state: K12State, now: Long): K12State {
        if (!state.parent.unlocked) return state
        if (now - state.parent.lastTouchMs < ScreenTime.PARENT_IDLE_MS) return state
        return lock(state)
    }

    /** E3: a correct PIN grants time and does not leave the parent session open. */
    fun grantWithPin(state: K12State, pin: String, now: Long, minutes: Int = ScreenTime.EXTENSION_QUANTUM_MIN): PinResult {
        return when (val checked = checkPin(state, pin, now)) {
            is PinResult.Rejected -> checked
            is PinResult.Accepted -> PinResult.Accepted(
                ScreenTime.grant(checked.state.copy(parent = state.parent), now, minutes),
            )
        }
    }

    fun grantWhileUnlocked(state: K12State, now: Long, minutes: Int = ScreenTime.EXTENSION_QUANTUM_MIN): K12State {
        if (!state.parent.unlocked) return state
        return ScreenTime.grant(touch(state, now), now, minutes)
    }

    fun updateLimits(state: K12State, dailyMin: Int, weekendExtraMin: Int, breakMin: Int): K12State? {
        if (!state.parent.unlocked) return null
        if (!LimitEdits.acceptable(dailyMin, weekendExtraMin, breakMin)) return null
        return state.copy(
            profile = state.profile.copy(
                dailyLimitOverrideMin = dailyMin,
                weekendExtraOverrideMin = weekendExtraMin,
                breakOverrideMin = breakMin,
            ),
        )
    }
}
