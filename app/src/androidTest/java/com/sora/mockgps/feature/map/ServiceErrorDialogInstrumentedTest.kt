package com.sora.mockgps.feature.map

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sora.mockgps.R
import com.sora.mockgps.service.MockServiceErrorKind
import com.sora.mockgps.ui.theme.BloomWalkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServiceErrorDialogInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun setupFailureOffersDeveloperOptionsRecovery() {
        var openedSettings = false
        composeRule.setContent {
            BloomWalkTheme {
                ServiceErrorDialog(
                    kind = MockServiceErrorKind.MockAppSetup,
                    onDismiss = {},
                    onOpenDeveloperOptions = { openedSettings = true },
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.mock_error_setup_required)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.action_open_developer_options)).performClick()
        assertEquals(true, openedSettings)
    }

    @Test
    fun googlePlayServicesFailureDoesNotOfferDeveloperOptions() {
        composeRule.setContent {
            BloomWalkTheme {
                ServiceErrorDialog(
                    kind = MockServiceErrorKind.GooglePlayServices,
                    onDismiss = {},
                    onOpenDeveloperOptions = {},
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.mock_error_google_play_services)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.action_open_developer_options)).assertDoesNotExist()
    }
}