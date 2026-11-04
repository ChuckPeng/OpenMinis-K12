package com.openminis.k12.ui

import android.Manifest
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.openminis.app.R
import com.openminis.k12.android.K12PairingRuntime
import com.openminis.k12.android.K12QrBitmap
import com.openminis.k12.android.K12Runtime
import com.openminis.k12.device.DevicePairing
import com.openminis.k12.device.PairingOutcome
import com.openminis.k12.device.PairingText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun K12PairingSection(onPaired: () -> Unit = {}, onSkip: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snap = K12Runtime.ui.value
    val endpoint = K12PairingRuntime.currentEndpoint()
    var url by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var paste by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<PairingOutcome.Pending?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var cameraFile by remember { mutableStateOf<File?>(null) }

    fun applyQr(text: String?) {
        val parsed = text?.let { DevicePairing.parseQr(it) }
        if (parsed == null) {
            message = PairingText.QR_MISS
            return
        }
        url = parsed.baseUrl
        code = parsed.pairCode
        fingerprint = parsed.certFingerprint.ifBlank { null }
        message = null
    }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = cameraFile
        if (!ok || file == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(file.absolutePath)?.let { K12QrBitmap.decode(it) }
            }
            applyQr(text)
            file.delete()
        }
    }
    fun launchCamera() {
        val dir = File(context.filesDir, "camera-photos").apply { mkdirs() }
        val file = File(dir, "pair-qr.jpg")
        file.createNewFile()
        cameraFile = file
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        takePicture.launch(uri)
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else message = context.getString(R.string.k12_pair_camera)
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input)?.let { K12QrBitmap.decode(it) }
                }
            }
            applyQr(text)
        }
    }

    val activeEndpoint = endpoint
    if (snap.profile.pairing == "active" && activeEndpoint != null) {
        Text(stringResource(R.string.k12_pair_active), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.k12_pair_host, PairingText.hostOf(activeEndpoint.baseUrl)))
        OutlinedButton(
            onClick = { K12PairingRuntime.unpair() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.k12_pair_unpair)) }
        if (onSkip != null) {
            Button(onClick = onPaired, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.k12_onboarding_finish))
            }
        }
        return
    }

    Text(stringResource(R.string.k12_onboarding_pair_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.k12_onboarding_later))
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text(stringResource(R.string.k12_pair_url)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    OutlinedTextField(
        value = code,
        onValueChange = { code = it.filter(Char::isDigit).take(6) },
        label = { Text(stringResource(R.string.k12_pair_code)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    OutlinedTextField(
        value = paste,
        onValueChange = {
            paste = it
            if (it.trim().startsWith("{") || it.trim().startsWith("k12://")) applyQr(it)
        },
        label = { Text(stringResource(R.string.k12_pair_paste)) },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { cameraPermission.launch(Manifest.permission.CAMERA) },
            enabled = !busy,
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.k12_pair_scan)) }
        OutlinedButton(
            onClick = { pickImage.launch("image/*") },
            enabled = !busy,
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.k12_pair_image)) }
    }
    if (message != null) Text(message!!)
    if (busy) Text(stringResource(R.string.k12_pair_working))
    Button(
        onClick = {
            val childId = snap.profile.childId.orEmpty()
            busy = true
            message = null
            scope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    K12PairingRuntime.submit(context, url, code, childId, fingerprint)
                }
                busy = false
                when (outcome) {
                    is PairingOutcome.Active -> onPaired()
                    is PairingOutcome.Pending -> {
                        pending = outcome
                        message = PairingText.PENDING
                    }
                    is PairingOutcome.Failed -> message = outcome.message
                }
            }
        },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.k12_pair_submit)) }
    if (pending != null) {
        OutlinedButton(
            onClick = {
                val current = pending ?: return@OutlinedButton
                busy = true
                scope.launch {
                    val outcome = withContext(Dispatchers.IO) {
                        K12PairingRuntime.refresh(context, current)
                    }
                    busy = false
                    when (outcome) {
                        is PairingOutcome.Active -> onPaired()
                        is PairingOutcome.Pending -> message = PairingText.PENDING
                        is PairingOutcome.Failed -> message = outcome.message
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.k12_pair_refresh)) }
    }
    if (onSkip != null) {
        OutlinedButton(onClick = onSkip, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.k12_onboarding_skip))
        }
    }
}
