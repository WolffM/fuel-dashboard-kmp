package com.angussoftware.fueldashboard.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.angussoftware.fueldashboard.model.ProviderConfig
import com.angussoftware.fueldashboard.model.ProviderKind
import com.angussoftware.fueldashboard.model.ProviderReport
import com.angussoftware.fueldashboard.model.ProviderType
import com.angussoftware.fueldashboard.network.ClaudeLoginLaunch
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Log in control on a plan provider's card, rendered: when it appears,
 * what it says, what it does, and where its outcome is shown.
 */
@OptIn(ExperimentalTestApi::class)
class ClaudeLoginUiTest {


    private val plan = ProviderConfig(id = "cc-work", kind = ProviderKind.CLAUDE_CODE, displayName = "Work plan")
    private val zai = ProviderConfig(id = "zai-1", kind = ProviderKind.ZAI, apiKey = "k", displayName = "My z.ai")

    private fun reading(available: Boolean) = ProviderReport(
        providerId = "cc-work",
        displayName = "Work plan",
        type = ProviderType.WINDOW_CREDIT,
        remainingPct = if (available) 60 else null,
        windowHours = 5.0,
        available = available,
    )

    private fun card(
        config: ProviderConfig = plan,
        report: ProviderReport? = reading(available = false),
        error: String? = null,
        onLogIn: (() -> Unit)? = {},
        isLoggingIn: Boolean = false,
        loginStatus: ClaudeLoginLaunch? = null,
        assertions: ComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest {
        setContent {
            MaterialTheme {
                Surface {
                    ProviderContent(
                        config = config,
                        report = report,
                        error = error,
                        showHelp = false,
                        titleStyle = MaterialTheme.typography.titleSmall,
                        contentSpacing = 8.dp,
                        isChecking = false,
                        onCheckJunieBalance = null,
                        boxedCreditBalance = false,
                        isLoggingIn = isLoggingIn,
                        onLogIn = onLogIn,
                        loginStatus = loginStatus,
                    )
                }
            }
        }
        assertions()
    }

    // --- the card ---------------------------------------------------------

    @Test
    fun anAccountWithNoReadingOffersLogIn() {
        var clicks = 0
        card(onLogIn = { clicks++ }) {
            onNodeWithText("Log in").assertExists().performClick()
            onNodeWithText("Re-log in").assertDoesNotExist()
        }
        assertEquals(1, clicks)
    }

    @Test
    fun aHealthyAccountStillOffersReLogIn() {
        // Never disabled: re-authenticating a working account is legitimate.
        var clicks = 0
        card(report = reading(available = true), onLogIn = { clicks++ }) {
            onNodeWithText("Re-log in").assertExists().performClick()
            onNodeWithText("Log in").assertDoesNotExist()
        }
        assertEquals(1, clicks)
    }

    @Test
    fun noLauncherMeansNoControl() {
        // What every other provider kind, and every platform without a
        // terminal, gets: nothing, rather than a button that cannot work.
        card(config = zai, onLogIn = null) {
            onNodeWithText("Log in").assertDoesNotExist()
            onNodeWithText("Re-log in").assertDoesNotExist()
        }
    }

    @Test
    fun whileOpeningTheButtonBecomesProgress() {
        card(isLoggingIn = true) {
            onNodeWithText("Opening login…").assertExists()
            onNodeWithText("Log in").assertDoesNotExist()
        }
    }

    @Test
    fun theOutcomeIsShownOnTheCard() {
        card(loginStatus = ClaudeLoginLaunch(launched = false, message = "Run this yourself: claude /login")) {
            onNodeWithText("⚠ Run this yourself: claude /login").assertExists()
        }
    }

    @Test
    fun theOutcomeIsShownOnAnErroredCard() {
        // The usual situation: the account has no usable login, so the card is
        // showing an error — which is exactly why the operator pressed Log in.
        card(
            report = null,
            error = "No usable Claude Code credentials in this provider's config directory",
            loginStatus = ClaudeLoginLaunch(launched = true, message = "Opened a login for /accounts/work"),
        ) {
            onNodeWithText("→ Opened a login for /accounts/work").assertExists()
            onNodeWithText("Log in").assertExists()
        }
    }

    @Test
    fun theOutcomeIsShownWhileTheCardIsStillConnecting() {
        card(report = null, loginStatus = ClaudeLoginLaunch(launched = true, message = "Opened a login")) {
            onNodeWithText("→ Opened a login").assertExists()
        }
    }
}
