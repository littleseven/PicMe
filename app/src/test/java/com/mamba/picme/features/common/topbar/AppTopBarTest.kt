package com.mamba.picme.features.common.topbar

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35])
class AppTopBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun action_click_invokes_callback() {
        var clicked = false
        composeRule.setContent {
            AppTopBarAction(
                icon = Icons.Rounded.Settings,
                contentDescription = "settings",
                onClick = { clicked = true }
            )
        }
        composeRule.onNodeWithContentDescription("settings").performClick()
        assertTrue(clicked)
    }

    @Test
    fun action_disabled_does_not_invoke_callback() {
        var clicked = false
        composeRule.setContent {
            AppTopBarAction(
                icon = Icons.Rounded.Settings,
                contentDescription = "settings",
                onClick = { clicked = true },
                enabled = false
            )
        }
        composeRule.onNodeWithContentDescription("settings").performClick()
        assertFalse(clicked)
    }
}
