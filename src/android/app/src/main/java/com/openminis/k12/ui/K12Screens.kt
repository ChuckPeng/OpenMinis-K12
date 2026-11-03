package com.openminis.k12.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.openminis.k12.OnboardingStep
import com.openminis.k12.PermissionItem
import com.openminis.k12.PermissionReport
import com.openminis.k12.PinResult
import com.openminis.app.R
import com.openminis.k12.ScreenTime
import com.openminis.k12.SecretSubmit
import com.openminis.k12.StudyStage
import com.openminis.k12.android.K12Runtime

@Composable
fun K12BreakBannerHost() {
    if (!K12Runtime.installed) return
    val snap = K12Runtime.ui.value
    if (!snap.profile.onboardingComplete || !ScreenTime.breakDue(snap)) return
    Box(Modifier.fillMaxWidth().wrapContentHeight().statusBarsPadding().padding(16.dp)) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
            Text(
                stringResource(R.string.k12_home_break),
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

@Composable
fun K12ChildHomeScreen(onStudy: () -> Unit, onParent: () -> Unit) {
    val snap = K12Runtime.ui.value
    val now = System.currentTimeMillis()
    val remainingMin = (ScreenTime.remainingMs(snap, now) / 60_000L).toInt()
    val limited = ScreenTime.limitReached(snap, now)
    val breakDue = ScreenTime.breakDue(snap)
    var pinOpen by remember { mutableStateOf(false) }
    var pinForExtend by remember { mutableStateOf(false) }
    var pinError by remember { mutableStateOf<String?>(null) }
    val wrong = stringResource(R.string.k12_pin_wrong)
    val locked = stringResource(R.string.k12_pin_locked)
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.k12_home_title),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() },
        )
        snap.profile.stage?.let { Text(it.label, style = MaterialTheme.typography.titleMedium) }
        snap.profile.nickname?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        Text(stringResource(R.string.k12_home_remaining, remainingMin))
        if (snap.profile.stage?.curriculumInV1() == false) {
            Text(stringResource(R.string.k12_home_senior))
        }
        if (breakDue) Text(stringResource(R.string.k12_home_break))
        if (limited) Text(stringResource(R.string.k12_home_limit))
        Button(
            onClick = onStudy,
            enabled = !limited,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.k12_home_study)) }
        if (limited) {
            OutlinedButton(
                onClick = { pinForExtend = true; pinOpen = true; pinError = null },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.k12_home_extend))
            }
        }
        OutlinedButton(
            onClick = { pinForExtend = false; pinOpen = true; pinError = null },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.k12_home_parent))
        }
    }
    if (pinOpen) {
        PinDialog(
            error = pinError,
            onDismiss = { pinOpen = false },
            onSubmit = { pin ->
                if (pinForExtend) {
                    when (val result = K12Runtime.grantWithPin(pin)) {
                        is PinResult.Accepted -> pinOpen = false
                        is PinResult.Rejected -> pinError = if (result.locked) locked else wrong
                    }
                } else {
                    when (val result = K12Runtime.unlock(pin)) {
                        is PinResult.Accepted -> {
                            pinOpen = false
                            onParent()
                        }
                        is PinResult.Rejected -> pinError = if (result.locked) locked else wrong
                    }
                }
            },
        )
    }
}

@Composable
fun K12OnboardingScreen(onFinished: () -> Unit) {
    val snap = K12Runtime.ui.value
    var pin by remember { mutableStateOf("") }
    var pinAgain by remember { mutableStateOf("") }
    var backup by remember { mutableStateOf("") }
    var backupAgain by remember { mutableStateOf("") }
    var secretError by remember { mutableStateOf(false) }
    var nick by remember(snap.profile.nickname) { mutableStateOf(snap.profile.nickname.orEmpty()) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.k12_onboarding_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        when (snap.profile.onboardingStep) {
            OnboardingStep.STAGE, OnboardingStep.DONE -> {
                Text(stringResource(R.string.k12_onboarding_stage), style = MaterialTheme.typography.titleMedium)
                StudyStage.entries.forEach { stage ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = snap.profile.stage == stage,
                            onClick = { K12Runtime.chooseStage(stage) },
                        )
                        Text(stage.label)
                    }
                }
                if (snap.profile.stage == StudyStage.SENIOR) {
                    Text(stringResource(R.string.k12_stage_senior_note))
                }
                Button(
                    onClick = { K12Runtime.openIdentity() },
                    enabled = snap.profile.stage != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.k12_onboarding_next)) }
            }
            OnboardingStep.IDENTITY -> {
                Text(stringResource(R.string.k12_onboarding_id_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.k12_onboarding_id_body))
                Text(snap.profile.childId.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                OutlinedTextField(
                    value = nick,
                    onValueChange = { nick = it },
                    label = { Text(stringResource(R.string.k12_onboarding_nick)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { K12Runtime.chooseStage(snap.profile.stage ?: StudyStage.PRIMARY_LOWER) }) {
                        Text(stringResource(R.string.k12_onboarding_back))
                    }
                    Button(
                        onClick = {
                            K12Runtime.setNickname(nick)
                            K12Runtime.openSecrets()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.k12_onboarding_next)) }
                }
            }
            OnboardingStep.SECRETS -> SecretFields(
                pin, { pin = it }, pinAgain, { pinAgain = it },
                backup, { backup = it }, backupAgain, { backupAgain = it },
                secretError,
                onBack = { K12Runtime.openIdentity() },
                onNext = {
                    when (K12Runtime.submitSecrets(pin, pinAgain, backup, backupAgain)) {
                        is SecretSubmit.Accepted -> {
                            secretError = false
                            pin = ""
                            pinAgain = ""
                            backup = ""
                            backupAgain = ""
                        }
                        is SecretSubmit.Rejected -> secretError = true
                    }
                },
            )
            OnboardingStep.PERMISSIONS -> {
                Text(stringResource(R.string.k12_onboarding_later))
                PermissionList(K12Runtime.permissionReport())
                Button(
                    onClick = {
                        K12Runtime.completeOnboarding()
                        onFinished()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.k12_onboarding_finish)) }
            }
        }
    }
}

@Composable
private fun SecretFields(
    pin: String,
    onPin: (String) -> Unit,
    pinAgain: String,
    onPinAgain: (String) -> Unit,
    backup: String,
    onBackup: (String) -> Unit,
    backupAgain: String,
    onBackupAgain: (String) -> Unit,
    error: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    Text(stringResource(R.string.k12_onboarding_secrets), style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(pin, onPin, label = { Text(stringResource(R.string.k12_onboarding_pin)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedTextField(pinAgain, onPinAgain, label = { Text(stringResource(R.string.k12_onboarding_pin_again)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedTextField(backup, onBackup, label = { Text(stringResource(R.string.k12_onboarding_backup)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedTextField(backupAgain, onBackupAgain, label = { Text(stringResource(R.string.k12_onboarding_backup_again)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    if (error) Text(stringResource(R.string.k12_onboarding_secret_error))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.k12_onboarding_back)) }
        Button(onClick = onNext, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.k12_onboarding_next)) }
    }
}

@Composable
fun K12PermissionScreen(onBack: () -> Unit) {
    var report by remember { mutableStateOf(K12Runtime.permissionReport()) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.k12_perm_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        PermissionList(report)
        Button(onClick = { report = K12Runtime.permissionReport() }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.k12_perm_refresh))
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.k12_onboarding_back))
        }
    }
}

@Composable
private fun PermissionList(report: PermissionReport) {
    val context = LocalContext.current
    val rows = listOf(
        PermissionItem.NOTIFICATIONS to report.notificationsEnabled,
        PermissionItem.BATTERY to report.batteryUnrestricted,
        PermissionItem.BACKGROUND to report.backgroundAllowed,
        PermissionItem.EXACT_ALARM to report.exactAlarmAllowed,
    )
    rows.forEach { (item, ok) ->
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(permissionLabel(item), style = MaterialTheme.typography.titleMedium)
            Text(if (ok) stringResource(R.string.k12_perm_ok) else stringResource(R.string.k12_perm_missing))
            if (!ok) {
                TextButton(onClick = { openPermissionSettings(context, item) }) {
                    Text(stringResource(R.string.k12_perm_open))
                }
            }
        }
    }
}

@Composable
private fun permissionLabel(item: PermissionItem): String = when (item) {
    PermissionItem.NOTIFICATIONS -> stringResource(R.string.k12_perm_notifications)
    PermissionItem.BATTERY -> stringResource(R.string.k12_perm_battery)
    PermissionItem.BACKGROUND -> stringResource(R.string.k12_perm_background)
    PermissionItem.EXACT_ALARM -> stringResource(R.string.k12_perm_alarm)
}

private fun openPermissionSettings(context: android.content.Context, item: PermissionItem) {
    val pkg = context.packageName
    val intent = when (item) {
        PermissionItem.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
        PermissionItem.BATTERY -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        PermissionItem.EXACT_ALARM -> if (Build.VERSION.SDK_INT >= 31) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$pkg"))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        }
        PermissionItem.BACKGROUND -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

@Composable
fun K12ParentScreen(
    onExit: () -> Unit,
    onPermissions: () -> Unit,
    onModelKey: () -> Unit,
    onNotificationChannel: () -> Unit,
) {
    val snap = K12Runtime.ui.value
    val limits = ScreenTime.limits(snap.profile.stage, snap.profile)
    var daily by remember(limits.dailyMin) { mutableStateOf(limits.dailyMin.toString()) }
    var weekend by remember(limits.weekendExtraMin) { mutableStateOf(limits.weekendExtraMin.toString()) }
    var breakMin by remember(limits.breakMin) { mutableStateOf(limits.breakMin.toString()) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.k12_parent_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.k12_parent_backup_set))
        Text(stringResource(R.string.k12_parent_limits), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(daily, { daily = it.filter(Char::isDigit).take(3) }, label = { Text(stringResource(R.string.k12_parent_daily)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(weekend, { weekend = it.filter(Char::isDigit).take(3) }, label = { Text(stringResource(R.string.k12_parent_weekend)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(breakMin, { breakMin = it.filter(Char::isDigit).take(3) }, label = { Text(stringResource(R.string.k12_parent_break)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(
            onClick = {
                K12Runtime.touch()
                K12Runtime.updateLimits(daily.toIntOrNull() ?: -1, weekend.toIntOrNull() ?: -1, breakMin.toIntOrNull() ?: -1)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.k12_parent_save)) }
        OutlinedButton(onClick = { K12Runtime.touch(); K12Runtime.grantWhileUnlocked() }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.k12_parent_extend))
        }
        Button(onClick = { K12Runtime.touch(); onPermissions() }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.k12_perm_title))
        }
        Button(onClick = { K12Runtime.touch(); onModelKey() }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.k12_parent_model_key))
        }
        Button(onClick = { K12Runtime.touch(); onNotificationChannel() }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.k12_parent_notify))
        }
        LaterRow(stringResource(R.string.k12_parent_reveal))
        LaterRow(stringResource(R.string.k12_parent_totp))
        LaterRow(stringResource(R.string.k12_parent_pairing))
        LaterRow(stringResource(R.string.k12_parent_catalog))
        LaterRow(stringResource(R.string.k12_parent_unlock))
        OutlinedButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.k12_parent_lock))
        }
    }
}

@Composable
private fun LaterRow(title: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.k12_parent_later))
    }
}

@Composable
private fun PinDialog(error: String?, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.k12_pin_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                    label = { Text(stringResource(R.string.k12_onboarding_pin)) },
                    singleLine = true,
                )
                if (error != null) Text(error)
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(pin) }) { Text(stringResource(R.string.k12_pin_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.k12_pin_cancel)) } },
    )
}
