package com.sora.mockgps.feature.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sora.mockgps.R
import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.feature.search.PlaceSearchResult
import com.sora.mockgps.feature.search.PlaceSearchSource
import com.sora.mockgps.feature.search.formatCoordinateSearchLabel
import com.sora.mockgps.feature.search.parseCoordinateSearchQuery
import com.sora.mockgps.ui.theme.BloomWalkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaceSearchContentInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun clearSearchRemovesProgressFeedback() {
        var query by mutableStateOf("Tokyo")
        var isSearching by mutableStateOf(true)
        composeRule.setContent {
            BloomWalkTheme {
                PlaceSearchContent(
                    query = query,
                    isSearching = isSearching,
                    results = emptyList(),
                    error = null,
                    onQueryChanged = {
                        query = it
                        isSearching = false
                    },
                    onPlaceSelected = {},
                )
            }
        }

        val loading = context.getString(R.string.place_search_loading)
        val searchField = composeRule.onNodeWithText("Tokyo")
        composeRule.onNodeWithText(loading).assertIsDisplayed()
        searchField.performImeAction()
        searchField.assertIsNotFocused()
        composeRule.onNodeWithContentDescription(context.getString(R.string.action_clear_search)).performClick()
        composeRule.onAllNodesWithText(loading).assertCountEquals(0)
        composeRule.onAllNodesWithText("Tokyo").assertCountEquals(0)
    }

    @Test
    fun completedSearchWithoutResultsShowsEmptyFeedback() {
        composeRule.setContent {
            BloomWalkTheme {
                PlaceSearchContent(
                    query = "NoSuchPlace",
                    isSearching = false,
                    results = emptyList(),
                    error = null,
                    onQueryChanged = {},
                    onPlaceSelected = {},
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.place_search_empty)).assertIsDisplayed()
    }

    @Test
    fun coordinateSearchResultShowsCoordinatesSection() {
        val label = formatCoordinateSearchLabel(Coordinate(25.033964, 121.564468))
        composeRule.setContent {
            BloomWalkTheme {
                PlaceSearchContent(
                    query = "25.033964, 121.564468",
                    isSearching = false,
                    results = listOf(
                        PlaceSearchResult(
                            name = label,
                            coordinate = Coordinate(25.033964, 121.564468),
                            source = PlaceSearchSource.Coordinate,
                        ),
                    ),
                    error = null,
                    onQueryChanged = {},
                    onPlaceSelected = {},
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.place_search_section_coordinates)).assertIsDisplayed()
        composeRule.onAllNodesWithText(label).assertCountEquals(2)
    }

    @Test
    fun typingCoordinatesSurfacesParsedCoordinateResult() {
        var query by mutableStateOf("")
        var results by mutableStateOf(emptyList<PlaceSearchResult>())
        composeRule.setContent {
            BloomWalkTheme {
                PlaceSearchContent(
                    query = query,
                    isSearching = false,
                    results = results,
                    error = null,
                    onQueryChanged = { typed ->
                        query = typed
                        results = parseCoordinateSearchQuery(typed)?.let { coordinate ->
                            listOf(
                                PlaceSearchResult(
                                    name = formatCoordinateSearchLabel(coordinate),
                                    coordinate = coordinate,
                                    source = PlaceSearchSource.Coordinate,
                                ),
                            )
                        } ?: emptyList()
                    },
                    onPlaceSelected = {},
                )
            }
        }

        val searchLabel = context.getString(R.string.place_search_label)
        composeRule.onNodeWithText(searchLabel).performClick()
        composeRule.onNodeWithText(searchLabel).performTextInput("25.033964, 121.564468")
        val expected = formatCoordinateSearchLabel(Coordinate(25.033964, 121.564468))
        composeRule.onNodeWithText(context.getString(R.string.place_search_section_coordinates)).assertIsDisplayed()
        composeRule.onAllNodesWithText(expected).assertCountEquals(2)
    }
}
