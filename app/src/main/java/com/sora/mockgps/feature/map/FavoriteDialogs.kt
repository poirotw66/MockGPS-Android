package com.sora.mockgps.feature.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sora.mockgps.R
import com.sora.mockgps.feature.favorites.domain.FavoriteLocation
import com.sora.mockgps.feature.favorites.domain.RecentLocation
import java.util.Locale

@Composable
internal fun FavoriteNameDialog(
    title: String,
    initialName: String,
    fieldLabelResource: Int = R.string.favorite_name,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    val normalizedName = name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 100) name = it },
                label = { Text(stringResource(fieldLabelResource)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(normalizedName) },
                enabled = normalizedName.isNotEmpty(),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
internal fun FavoritesDialog(
    favorites: List<FavoriteLocation>,
    recentLocations: List<RecentLocation>,
    onSelect: (FavoriteLocation) -> Unit,
    onSelectRecent: (RecentLocation) -> Unit,
    onRename: (FavoriteLocation) -> Unit,
    onDelete: (FavoriteLocation) -> Unit,
    onClearAll: () -> Unit,
    onClearRecentLocations: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.favorites_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onExportBackup,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Text(stringResource(R.string.action_export_favorites_backup), maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = onImportBackup,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Text(stringResource(R.string.action_import_favorites_backup), maxLines = 1)
                    }
                }
                Text(
                    stringResource(R.string.favorites_backup_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (favorites.isEmpty() && recentLocations.isEmpty()) {
                    Text(stringResource(R.string.favorites_empty))
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (favorites.isNotEmpty()) {
                            item { Text(stringResource(R.string.favorites_title), style = MaterialTheme.typography.titleSmall) }
                        }
                        items(favorites, key = { "favorite-${it.id}" }) { favorite ->
                            Column(modifier = Modifier.fillMaxWidth()) {
                                TextButton(
                                    onClick = { onSelect(favorite) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.medium,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                    colors = ButtonDefaults.textButtonColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                        contentColor = MaterialTheme.colorScheme.onSurface,
                                    ),
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        val coordinateLabel = String.format(
                                            Locale.US,
                                            "%.6f, %.6f",
                                            favorite.latitude,
                                            favorite.longitude,
                                        )
                                        Text(favorite.name, style = MaterialTheme.typography.titleSmall)
                                        if (favorite.name != coordinateLabel) {
                                            Text(
                                                coordinateLabel,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                Row(modifier = Modifier.padding(horizontal = 4.dp)) {
                                    TextButton(onClick = { onRename(favorite) }) {
                                        Text(stringResource(R.string.action_rename))
                                    }
                                    TextButton(onClick = { onDelete(favorite) }) {
                                        Text(stringResource(R.string.action_delete))
                                    }
                                }
                            }
                        }
                        if (recentLocations.isNotEmpty()) {
                            item { Text(stringResource(R.string.recent_locations_title), style = MaterialTheme.typography.titleSmall) }
                        }
                        items(recentLocations, key = { "recent-${it.id}" }) { recent ->
                            TextButton(
                                onClick = { onSelectRecent(recent) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium,
                                colors = ButtonDefaults.textButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                ),
                            ) {
                                Text(
                                    String.format(Locale.US, "%.6f, %.6f", recent.latitude, recent.longitude),
                                    modifier = Modifier.fillMaxWidth(),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if (favorites.isNotEmpty()) TextButton(onClick = onClearAll) { Text(stringResource(R.string.action_clear_all)) }
                if (recentLocations.isNotEmpty()) TextButton(onClick = onClearRecentLocations) {
                    Text(stringResource(R.string.action_clear_history))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        },
    )
}

@Composable
internal fun ConfirmClearDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_clear_all)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
internal fun DeleteFavoriteDialog(
    favoriteName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.favorite_delete_title)) },
        text = { Text(stringResource(R.string.favorite_delete_message, favoriteName)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
