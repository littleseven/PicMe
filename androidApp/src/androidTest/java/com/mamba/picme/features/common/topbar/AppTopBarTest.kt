package com.mamba.picme.features.common.topbar

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppTopBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun action_click_invokes_callback() {
        var clicked = false
        composeRule.setContent {
            AppTopBarAction(
                icon = Icons.Outlined.Settings,
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
                icon = Icons.Outlined.Settings,
                contentDescription = "settings",
                onClick = { clicked = true },
                enabled = false
            )
        }
        composeRule.onNodeWithContentDescription("settings").performClick()
        assertFalse(clicked)
    }
}
