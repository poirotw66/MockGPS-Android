package com.sora.mockgps.feature.map

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.sora.mockgps.R
import com.sora.mockgps.service.MockServiceErrorKind

@Composable
internal fun ServiceErrorDialog(
    kind: MockServiceErrorKind,
    onDismiss: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
) {
    val message = stringResource(
        when (kind) {
            MockServiceErrorKind.MockAppSetup -> R.string.mock_error_setup_required
            MockServiceErrorKind.GooglePlayServices -> R.string.mock_error_google_play_services
            MockServiceErrorKind.Generic -> R.string.mock_error_generic
        },
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mock_error_title)) },
        text = { Text(message) },
        confirmButton = {
            if (kind == MockServiceErrorKind.MockAppSetup) {
                TextButton(onClick = onOpenDeveloperOptions) {
                    Text(stringResource(R.string.action_open_developer_options))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

/** A full-screen map picker with controls kept clear of the map's centre. */
