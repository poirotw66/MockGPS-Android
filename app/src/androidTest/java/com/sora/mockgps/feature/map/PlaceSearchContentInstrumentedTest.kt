package com.sora.mockgps.feature.map

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sora.mockgps.R
import com.sora.mockgps.ui.theme.BloomWalkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaceSearchContentInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

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
}