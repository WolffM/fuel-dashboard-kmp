package com.angussoftware.fueldashboard.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.angussoftware.fueldashboard.model.ProviderConfig
import com.angussoftware.fueldashboard.model.ProviderKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A plan provider's settings row, rendered: pointing it at another account's
 * config directory, and the fields other provider kinds must not grow.
 */
@OptIn(ExperimentalTestApi::class)
class ClaudeAccountSettingsUiTest {

    private val plan = ProviderConfig(id = "cc-work", kind = ProviderKind.CLAUDE_CODE, displayName = "Work plan")
    private val zai = ProviderConfig(id = "zai-1", kind = ProviderKind.ZAI, apiKey = "k", displayName = "My z.ai")

    private fun settingsRow(
        config: ProviderConfig,
        onUpdate: (ProviderConfig) -> Unit = {},
        assertions: ComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest {
        setContent {
            MaterialTheme {
                Surface {
                    ProviderConfigRow(
                        config = config,
                        onUpdate = onUpdate,
                        onRemove = {},
                        onMoveUp = null,
                        onMoveDown = null,
                        showHelp = false,
                    )
                }
            }
        }
        onNodeWithContentDescription("Edit").performClick()
        assertions()
    }

    @Test
    fun aPlanProviderCanBePointedAtAnotherAccount() {
        var saved: ProviderConfig? = null
        settingsRow(plan, onUpdate = { saved = it }) {
            onNodeWithText("Claude config dir (optional)").performTextInput("  ~/.claude-accounts/work  ")
            onNodeWithText("Save").performClick()
        }
        assertEquals("~/.claude-accounts/work", saved?.claudeConfigDir, "saved trimmed")
        assertEquals(ProviderKind.CLAUDE_CODE, saved?.kind)
    }

    @Test
    fun clearingTheFieldReturnsToTheDefaultAccount() {
        var saved: ProviderConfig? = null
        settingsRow(plan.copy(claudeConfigDir = "/accounts/work"), onUpdate = { saved = it }) {
            onNodeWithText("Claude config dir (optional)").performTextClearance()
            onNodeWithText("Save").performClick()
        }
        assertEquals("", saved?.claudeConfigDir)
    }

    @Test
    fun otherProvidersHaveNoConfigDirField() {
        settingsRow(zai) {
            onNodeWithText("Claude config dir (optional)").assertDoesNotExist()
            onNodeWithText("API Key").assertExists()
        }
    }
}
