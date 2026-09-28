package com.sora.mockgps.monetization.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sora.mockgps.R

@Composable
fun AboutAndPrivacyDialog(
    privacyPolicyUrl: String,
    supportUrl: String,
    privacyOptionsRequired: Boolean,
    onPrivacyOptions: () -> Unit,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.about_privacy_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.about_privacy_body), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { uriHandler.openUri(privacyPolicyUrl) }) {
                    Text(stringResource(R.string.about_privacy_policy))
                }
                TextButton(onClick = { uriHandler.openUri(supportUrl) }) {
                    Text(stringResource(R.string.about_support))
                }
                if (privacyOptionsRequired) {
                    TextButton(onClick = onPrivacyOptions) {
                        Text(stringResource(R.string.about_privacy_options))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}
