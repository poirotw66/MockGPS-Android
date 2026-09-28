package com.sora.mockgps.monetization.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sora.mockgps.R
import com.sora.mockgps.monetization.entitlement.EntitlementSnapshot
import com.sora.mockgps.monetization.entitlement.EntitlementState

@Composable
fun RemoveAdsDialog(
    snapshot: EntitlementSnapshot,
    onPurchase: () -> Unit,
    onRestore: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remove_ads_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.remove_ads_promise), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.remove_ads_boundary), style = MaterialTheme.typography.bodySmall)
                snapshot.formattedPrice?.let { price ->
                    Text(price, style = MaterialTheme.typography.titleMedium)
                }
                StatusText(snapshot.state)
                OutlinedButton(
                    onClick = onPurchase,
                    enabled = snapshot.state == EntitlementState.Free &&
                        snapshot.productDetailsAvailable &&
                        snapshot.purchasesEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.remove_ads_buy))
                }
                TextButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.remove_ads_restore))
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

@Composable
private fun StatusText(state: EntitlementState) {
    val text = when (state) {
        EntitlementState.Unknown,
        EntitlementState.VerificationPending,
        -> stringResource(R.string.remove_ads_verifying)
        EntitlementState.PurchasePending -> stringResource(R.string.remove_ads_pending)
        EntitlementState.OwnedVerified,
        EntitlementState.OwnedOffline,
        -> stringResource(R.string.remove_ads_owned)
        EntitlementState.Revoked -> stringResource(R.string.remove_ads_revoked)
        EntitlementState.Free -> null
    }
    text?.let {
        Text(
            it,
            modifier = Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
