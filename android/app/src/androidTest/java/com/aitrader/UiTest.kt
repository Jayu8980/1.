package com.aitrader

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aitrader.data.model.TradingMode
import com.aitrader.ui.components.ModeBadge
import com.aitrader.ui.screens.LIVE_PHRASE
import com.aitrader.ui.screens.LiveDialog
import com.aitrader.ui.theme.AITraderTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun live_button_enabled_only_after_exact_phrase() {
        var confirmed: String? = null
        rule.setContent { AITraderTheme { LiveDialog(onDismiss = {}, onConfirm = { confirmed = it }) } }
        rule.onNodeWithTag("confirmLive").assertIsNotEnabled()
        rule.onNodeWithTag("livePhrase").performTextInput("I understand")
        rule.onNodeWithTag("confirmLive").assertIsNotEnabled()
        rule.onNodeWithTag("livePhrase").performTextClearance()
        rule.onNodeWithTag("livePhrase").performTextInput(LIVE_PHRASE)
        rule.onNodeWithTag("confirmLive").assertIsEnabled().performClick()
        assertEquals(LIVE_PHRASE, confirmed)
    }

    @Test fun badge_shows_demo_paper() {
        rule.setContent { AITraderTheme { ModeBadge(TradingMode.PAPER, demo = true) } }
        rule.onNodeWithTag("modeBadge").assertTextEquals("DEMO · PAPER")
    }

    @Test fun badge_shows_live() {
        rule.setContent { AITraderTheme { ModeBadge(TradingMode.LIVE, demo = false) } }
        rule.onNodeWithText("LIVE").assertExists()
    }
}
