package com.sora.mockgps.feature.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import com.sora.mockgps.R
import com.sora.mockgps.feature.routes.domain.RecentRouteSummary
import com.sora.mockgps.feature.routes.domain.SavedRouteSummary
import java.util.Locale

@Composable
internal fun RouteLibraryDialog(
    savedRoutes: List<SavedRouteSummary>,
    recentRoutes: List<RecentRouteSummary>,
    onLoadSaved: (SavedRouteSummary) -> Unit,
    onLoadRecent: (RecentRouteSummary) -> Unit,
    onReverse: (SavedRouteSummary) -> Unit,
    onRename: (SavedRouteSummary) -> Unit,
    onDuplicate: (SavedRouteSummary) -> Unit,
    onDelete: (SavedRouteSummary) -> Unit,
    onImportGpx: () -> Unit,
    onImportBackup: () -> Unit,
    onExportBackup: () -> Unit,
    onClearRecents: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.route_library_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onImportGpx) { Text(stringResource(R.string.action_import_gpx)) }
                    TextButton(onClick = onImportBackup) { Text(stringResource(R.string.action_import_backup)) }
                    TextButton(onClick = onExportBackup) { Text(stringResource(R.string.action_export_backup)) }
                }
                Text(
                    stringResource(R.string.route_backup_excludes_favorites),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (savedRoutes.isEmpty() && recentRoutes.isEmpty()) {
                    Text(stringResource(R.string.route_library_empty))
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        if (savedRoutes.isNotEmpty()) {
                            item { RouteLibraryHeading(stringResource(R.string.saved_routes_title)) }
                            items(savedRoutes, key = { "saved-${it.id}" }) { route ->
                                RouteLibraryItem(
                                    name = route.name,
                                    distanceMeters = route.distanceMeters,
                                    onLoad = { onLoadSaved(route) },
                                    actions = {
                                        var actionsExpanded by remember(route.id) { mutableStateOf(false) }
                                        Box {
                                            IconButton(onClick = { actionsExpanded = true }) {
                                                Icon(
                                                    Icons.Filled.MoreVert,
                                                    contentDescription = stringResource(R.string.action_route_options),
                                                )
                                            }
                                            DropdownMenu(
                                                expanded = actionsExpanded,
                                                onDismissRequest = { actionsExpanded = false },
                                            ) {
                                                RouteActionItem(R.string.action_reverse_route) {
                                                    actionsExpanded = false
                                                    onReverse(route)
                                                }
                                                RouteActionItem(R.string.action_rename) {
                                                    actionsExpanded = false
                                                    onRename(route)
                                                }
                                                RouteActionItem(R.string.action_duplicate) {
                                                    actionsExpanded = false
                                                    onDuplicate(route)
                                                }
                                                RouteActionItem(R.string.action_delete) {
                                                    actionsExpanded = false
                                                    onDelete(route)
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                        }
                        if (recentRoutes.isNotEmpty()) {
                            item { RouteLibraryHeading(stringResource(R.string.recent_routes_title)) }
                            items(recentRoutes, key = { "recent-${it.id}" }) { route ->
                                RouteLibraryItem(
                                    name = route.name,
                                    distanceMeters = route.distanceMeters,
                                    onLoad = { onLoadRecent(route) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if (recentRoutes.isNotEmpty()) TextButton(onClick = onClearRecents) { Text(stringResource(R.string.action_clear_history)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        },
    )
}

@Composable
private fun RouteActionItem(label: Int, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        onClick = onClick,
    )
}

@Composable
private fun RouteLibraryHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun RouteLibraryItem(
    name: String,
    distanceMeters: Double,
    onLoad: () -> Unit,
    actions: @Composable () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.route_library_distance, String.format(Locale.US, "%.2f", distanceMeters / 1_000.0)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            actions()
            TextButton(onClick = onLoad) { Text(stringResource(R.string.action_load_route)) }
        }
        HorizontalDivider()
    }
}
