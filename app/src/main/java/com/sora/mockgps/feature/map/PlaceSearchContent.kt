package com.sora.mockgps.feature.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sora.mockgps.R
import com.sora.mockgps.feature.search.PlaceSearchResult
import com.sora.mockgps.feature.search.PlaceSearchSource
import com.sora.mockgps.feature.search.looksLikeLandmarkNickname

@Composable
internal fun PlaceSearchContent(
    query: String,
    isSearching: Boolean,
    results: List<PlaceSearchResult>,
    error: PlaceSearchError?,
    onQueryChanged: (String) -> Unit,
    onPlaceSelected: (PlaceSearchResult) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val coordinateResults = results.filter { it.source == PlaceSearchSource.Coordinate }
    val landmarkResults = results.filter { it.source == PlaceSearchSource.Landmark }
    val remoteResults = results.filter { it.source == PlaceSearchSource.Remote }
    val showNicknameHint = !isSearching &&
        looksLikeLandmarkNickname(query) &&
        coordinateResults.isEmpty() &&
        landmarkResults.isEmpty()
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChanged,
        label = { Text(stringResource(R.string.place_search_label)) },
        supportingText = { Text(stringResource(R.string.place_search_privacy)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChanged("") }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.action_clear_search),
                    )
                }
            }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
            onSearch = {
                keyboardController?.hide()
                focusManager.clearFocus()
            },
        ),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    if (isSearching) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(
                stringResource(R.string.place_search_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else if (query.trim().length >= 2 && results.isEmpty() && error == null) {
        Text(
            stringResource(R.string.place_search_empty),
            modifier = Modifier.padding(vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    error?.let { searchError ->
        Text(
            stringResource(
                when (searchError) {
                    PlaceSearchError.Network -> R.string.place_search_error_network
                    PlaceSearchError.RateLimited -> R.string.place_search_error_rate
                    PlaceSearchError.InvalidResponse -> R.string.place_search_error_invalid
                },
            ),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (coordinateResults.isNotEmpty()) {
        PlaceSearchSectionHeader(stringResource(R.string.place_search_section_coordinates))
        coordinateResults.forEach { result ->
            PlaceSearchResultButton(result, onPlaceSelected)
        }
    }
    if (landmarkResults.isNotEmpty()) {
        PlaceSearchSectionHeader(stringResource(R.string.place_search_section_landmarks))
        landmarkResults.forEach { result ->
            PlaceSearchResultButton(result, onPlaceSelected)
        }
    }
    if (remoteResults.isNotEmpty()) {
        PlaceSearchSectionHeader(stringResource(R.string.place_search_section_places))
        remoteResults.forEach { result ->
            PlaceSearchResultButton(result, onPlaceSelected)
        }
    }
    if (showNicknameHint) {
        Text(
            stringResource(R.string.place_search_hint_landmark),
            modifier = Modifier.padding(vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PlaceSearchSectionHeader(title: String) {
    Text(
        title,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PlaceSearchResultButton(
    result: PlaceSearchResult,
    onPlaceSelected: (PlaceSearchResult) -> Unit,
) {
    TextButton(
        onClick = { onPlaceSelected(result) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(result.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
