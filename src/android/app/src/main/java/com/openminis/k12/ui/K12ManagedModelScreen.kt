package com.openminis.k12.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.k12.model.K12ModelRuntime

@Composable
fun k12ProviderSettingsLocked(): Boolean {
    val tick = K12ModelRuntime.revision.value
    return tick >= 0 && K12ModelRuntime.managed()
}

/** Read-only view of the server-managed provider. No key is shown. */
@Composable
fun K12ManagedModelScreen(onBack: () -> Unit) {
    val tick = K12ModelRuntime.revision.value
    val status = if (tick >= 0) K12ModelRuntime.pageStatus() else K12ModelRuntime.pageStatus()
    K12Page {
        Text(
            stringResource(R.string.k12_model_managed_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.k12_model_managed_body))
        Labeled(stringResource(R.string.k12_model_provider), status.providerType.ifBlank { "—" })
        Labeled(stringResource(R.string.k12_model_base), status.baseUrl.ifBlank { "—" })
        Labeled(stringResource(R.string.k12_model_name), status.modelName.ifBlank { "—" })
        Labeled(
            stringResource(R.string.k12_model_vision),
            status.visionModelName.ifBlank { stringResource(R.string.k12_model_vision_same) },
        )
        Labeled(
            stringResource(R.string.k12_model_key),
            if (status.apiKeySet) stringResource(R.string.k12_model_key_set) else stringResource(R.string.k12_model_key_missing),
        )
        Text(stringResource(R.string.k12_model_version, status.version))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.k12_model_back))
        }
    }
}

@Composable
private fun Labeled(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(value)
    }
}
