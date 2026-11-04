package com.openminis.k12.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.k12.PinResult
import com.openminis.k12.ScreenTime
import com.openminis.k12.android.K12Runtime
import com.openminis.k12.android.K12SafetyRuntime
import com.openminis.k12.model.K12ModelRuntime
import com.openminis.k12.safety.ContentSafetyGuide

@Composable
fun K12EmergencyHost() {
    val screen = K12SafetyRuntime.screen.value ?: return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = {},
        title = { Text(ContentSafetyGuide.TEXT_TELL_ADULT) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(screen.messageText)
                if (screen.hotlineNumber != null) Text(screen.hotlineNumber)
            }
        },
        confirmButton = {
            if (screen.showDeclineButton) {
                Button(onClick = { K12SafetyRuntime.decline() }) {
                    Text(ContentSafetyGuide.TEXT_DECLINE_BUTTON)
                }
            } else {
                Button(onClick = { K12SafetyRuntime.dismiss() }) {
                    Text(stringResource(R.string.k12_emergency_ok))
                }
            }
        },
    )
}

@Composable
fun K12ScreenTimeChip() {
    if (!K12Runtime.installed) return
    val snap = K12Runtime.ui.value
    if (!snap.profile.onboardingComplete) return
    val remainingMin = (ScreenTime.remainingMs(snap, System.currentTimeMillis()) / 60_000L).toInt()
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            stringResource(R.string.k12_time_chip, remainingMin),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
fun K12BeforeChatScreen(paired: Boolean, onPair: () -> Unit, onManual: () -> Unit) {
    K12Page {
        if (paired) {
            Text(
                stringResource(R.string.k12_wait_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.k12_wait_body))
        } else {
            Text(
                stringResource(R.string.k12_need_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.k12_need_body))
        }
        Button(onClick = onPair, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.k12_need_pair))
        }
        OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.k12_need_manual))
        }
    }
}

@Composable
fun K12ScreenLockScreen(onExtended: () -> Unit, onParent: () -> Unit) {
    val snap = K12Runtime.ui.value
    val remainingMin = (ScreenTime.remainingMs(snap, System.currentTimeMillis()) / 60_000L).toInt()
    var pinOpen by remember { mutableStateOf(false) }
    var pinError by remember { mutableStateOf<String?>(null) }
    val wrong = stringResource(R.string.k12_pin_wrong)
    val locked = stringResource(R.string.k12_pin_locked)
    K12Page {
        Text(
            stringResource(R.string.k12_lock_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.k12_lock_body))
        Text(stringResource(R.string.k12_time_chip, remainingMin))
        if (ScreenTime.breakDue(snap)) Text(stringResource(R.string.k12_home_break))
        Button(onClick = { pinOpen = true; pinError = null }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.k12_home_extend))
        }
        OutlinedButton(onClick = onParent, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.k12_home_parent))
        }
    }
    if (pinOpen) {
        PinDialog(
            error = pinError,
            onDismiss = { pinOpen = false },
            onSubmit = { pin ->
                when (val result = K12Runtime.grantWithPin(pin)) {
                    is PinResult.Accepted -> {
                        pinOpen = false
                        onExtended()
                    }
                    is PinResult.Rejected -> pinError = if (result.locked) locked else wrong
                }
            },
        )
    }
}

@Composable
fun K12ParentGateScreen(onUnlocked: () -> Unit) {
    var pinError by remember { mutableStateOf<String?>(null) }
    val wrong = stringResource(R.string.k12_pin_wrong)
    val locked = stringResource(R.string.k12_pin_locked)
    if (K12Runtime.parentUnlocked()) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onUnlocked() }
    }
    K12Page {
        Text(
            stringResource(R.string.k12_pin_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        PinFields(
            error = pinError,
            onSubmit = { pin ->
                when (val result = K12Runtime.unlock(pin)) {
                    is PinResult.Accepted -> onUnlocked()
                    is PinResult.Rejected -> pinError = if (result.locked) locked else wrong
                }
            },
        )
    }
}

@Composable
fun K12Page(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun PinFields(error: String?, onSubmit: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    androidx.compose.material3.OutlinedTextField(
        value = pin,
        onValueChange = { pin = it.filter(Char::isDigit).take(8) },
        label = { Text(stringResource(R.string.k12_onboarding_pin)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    if (error != null) Text(error)
    Button(onClick = { onSubmit(pin) }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.k12_pin_ok))
    }
}

@Composable
fun k12ModelManagedNow(): Boolean {
    val tick = K12ModelRuntime.revision.value
    return tick >= 0 && K12ModelRuntime.managed()
}
