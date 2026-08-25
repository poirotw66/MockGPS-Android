package com.sora.mockgps.feature.map

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sora.mockgps.R
import com.sora.mockgps.route.RouteExecutionMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JourneyStateRestorationInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun automaticJourneySelectionsSurviveStateRestoration() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            MaterialTheme {
                AutoJourneyDialog(onDismiss = {}, onGenerate = {})
            }
        }
        composeRule.waitForIdle()

        val japan = context.getString(R.string.region_japan)
        val longDuration = context.getString(R.string.duration_120_minutes)
        val drive = context.getString(R.string.transport_drive)
        val perfectShape = context.getString(R.string.route_style_perfect_shape)
        composeRule.onNodeWithText(japan).performClick()
        composeRule.onNodeWithText(longDuration).performClick()
        composeRule.onNodeWithText(drive).performClick()
        composeRule.onNodeWithText(perfectShape).performClick()

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(japan).assertIsSelected()
        composeRule.onNodeWithText(longDuration).assertIsSelected()
        composeRule.onNodeWithText(drive).assertIsSelected()
        composeRule.onNodeWithText(perfectShape).assertIsSelected()
    }

    @Test
    fun shapeSelectionSurvivesStateRestoration() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            MaterialTheme {
                ShapeRouteDialog(onDismiss = {}, onGenerate = {})
            }
        }
        composeRule.waitForIdle()

        val dog = context.getString(R.string.shape_dog)
        composeRule.onNodeWithText(dog).performClick()

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(dog).assertIsSelected()
    }

    @Test
    fun routeSimulationOptionsSurviveStateRestoration() {
        val expected = RouteSimulationOptions(
            preset = MovementPreset.Drive,
            customSpeedText = "42.5",
            mode = RouteExecutionMode.Loop,
            smoothMovement = false,
            gpsDriftEnabled = true,
        )
        var initializationCount = 0
        var observed = RouteSimulationOptions()
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            var options by rememberSaveable(stateSaver = RouteSimulationOptionsSaver) {
                mutableStateOf(if (initializationCount++ == 0) expected else RouteSimulationOptions())
            }
            observed = options
        }

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(expected, observed)
            assertEquals(1, initializationCount)
        }
    }
}