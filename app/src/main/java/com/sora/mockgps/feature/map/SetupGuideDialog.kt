package com.sora.mockgps.feature.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sora.mockgps.R

@Composable
internal fun SetupGuideDialog(
    onOpenDeveloperOptions: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setup_guide_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(R.string.setup_guide_mock_app),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.setup_guide_is_mock),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.setup_guide_battery),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenDeveloperOptions) {
                Text(stringResource(R.string.action_open_developer_options))
            }
        },
        dismissButton = {
            Column {
                TextButton(onClick = onOpenBatterySettings) {
                    Text(stringResource(R.string.action_open_battery_settings))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_got_it))
                }
            }
        },
    )
}
