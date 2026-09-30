package com.angussoftware.fueldashboard.presentation

import com.angussoftware.fueldashboard.model.ClaudeCodeFleet
import com.angussoftware.fueldashboard.model.MultiProviderSettings
import com.angussoftware.fueldashboard.model.ProviderConfig
import com.angussoftware.fueldashboard.model.ProviderKind
import com.angussoftware.fueldashboard.model.ProviderReport
import com.angussoftware.fueldashboard.model.ProviderType
import com.angussoftware.fueldashboard.network.ClaudeLoginLaunch
import com.angussoftware.fueldashboard.settings.FuelSettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Log in button's state handling, driven through the real view model.
 *
 * The launcher itself opens a terminal, so it is replaced here; what is under
 * test is everything around it — which providers may launch, which account
 * directory is used, what happens on failure and on a double click, and how
 * long the status line lives.
 */
class ClaudeLoginFlowTest {

    private val plan = ProviderConfig(
        id = "cc-work",
        kind = ProviderKind.CLAUDE_CODE,
        displayName = "Work plan",
        claudeConfigDir = "  /accounts/work  ",
    )
    private val zai = ProviderConfig(id = "zai-1", kind = ProviderKind.ZAI, apiKey = "k")

    @AfterTest
    fun clearStoredProviders() {
        FuelSettingsStore.saveMultiProvider(MultiProviderSettings())
    }

    private fun viewModel(
        launcher: suspend (String?) -> ClaudeLoginLaunch,
        decisions: MutableList<List<String?>> = mutableListOf(),
    ): FuelViewModel {
        val settings = MultiProviderSettings(providers = listOf(plan, zai))
        FuelSettingsStore.saveMultiProvider(settings)
        // A quiet fleet, so nothing about the login depends on the swap gate.
        val vm = FuelViewModel(
            fleetReader = { ClaudeCodeFleet(idle = 1) },
            routeReader = { null },
            loginLauncher = launcher,
        )
        vm.onDecisionLogged = { agentId, modelHandle, providerId, _, complexity, _, _, reason ->
            decisions.add(listOf(agentId, modelHandle, providerId, complexity, reason))
        }
        vm.updateSettings(settings)
        return vm
    }

    private fun FuelViewModel.awaitLoginResult(id: String = "cc-work"): ClaudeLoginLaunch = runBlocking {
        withTimeout(10_000) {
            var seen: ClaudeLoginLaunch? = null
            while (seen == null) {
                seen = state.value.loginResults[id]
                if (seen == null) delay(10)
            }
            seen
        }
    }

    @Test
    fun launchesForTheProvidersOwnTrimmedConfigDir() {
        val dirs = mutableListOf<String?>()
        val vm = viewModel({ dir -> dirs.add(dir); ClaudeLoginLaunch(true, "opened") })
        try {
            vm.logInToClaudeAccount("cc-work")
            assertTrue(vm.awaitLoginResult().launched)
            assertEquals(listOf<String?>("/accounts/work"), dirs)
            assertFalse("cc-work" in vm.state.value.loggingInProviderIds, "spinner must clear")
        } finally {
            vm.close()
        }
    }

    @Test
    fun aBlankConfigDirLogsIntoTheDefaultAccount() {
        val dirs = mutableListOf<String?>()
        val vm = viewModel({ dir -> dirs.add(dir); ClaudeLoginLaunch(true, "opened") })
        try {
            vm.updateSettings(MultiProviderSettings(providers = listOf(plan.copy(claudeConfigDir = "   "))))
            vm.logInToClaudeAccount("cc-work")
            vm.awaitLoginResult()
            assertEquals(listOf<String?>(null), dirs, "blank must mean ~/.claude, not a directory named blank")
        } finally {
            vm.close()
        }
    }

    @Test
    fun otherProviderKindsHaveNoAccountToLogInTo() {
        var launches = 0
        val vm = viewModel({ launches++; ClaudeLoginLaunch(true, "opened") })
        try {
            vm.logInToClaudeAccount("zai-1")
            vm.logInToClaudeAccount("no-such-provider")
            runBlocking { delay(200) }
            assertEquals(0, launches)
            assertTrue(vm.state.value.loginResults.isEmpty())
        } finally {
            vm.close()
        }
    }

    @Test
    fun aSecondClickWhileOpeningDoesNotStartASecondLogin() {
        // Two logins for one account would race for the same credentials file.
        val release = CompletableDeferred<Unit>()
        var launches = 0
        val vm = viewModel({ launches++; release.await(); ClaudeLoginLaunch(true, "opened") })
        try {
            vm.logInToClaudeAccount("cc-work")
            runBlocking {
                withTimeout(10_000) { while ("cc-work" !in vm.state.value.loggingInProviderIds) delay(5) }
            }
            vm.logInToClaudeAccount("cc-work")
            release.complete(Unit)
            vm.awaitLoginResult()
            assertEquals(1, launches)
        } finally {
            vm.close()
        }
    }

    @Test
    fun aLauncherThatThrowsBecomesAVisibleFailureNotACrash() {
        val vm = viewModel({ throw IllegalStateException("no display") })
        try {
            vm.logInToClaudeAccount("cc-work")
            val result = vm.awaitLoginResult()
            assertFalse(result.launched)
            assertTrue(result.message.contains("no display"), result.message)
            assertFalse("cc-work" in vm.state.value.loggingInProviderIds, "a failure must not leave the spinner up")
        } finally {
            vm.close()
        }
    }

    @Test
    fun everyAttemptIsRecordedInTheDecisionLog() {
        val decisions = mutableListOf<List<String?>>()
        val vm = viewModel({ ClaudeLoginLaunch(false, "no terminal found") }, decisions)
        try {
            vm.logInToClaudeAccount("cc-work")
            vm.awaitLoginResult()
            val row = decisions.single { it[0] == "claude-login" }
            assertEquals("/accounts/work", row[1])
            assertEquals("cc-work", row[2])
            assertEquals("failed", row[3])
            assertTrue(row[4]!!.contains("no terminal found"), row[4])
        } finally {
            vm.close()
        }
    }

    // --- how long the status line lives -----------------------------------

    private fun report(id: String, available: Boolean) = ProviderReport(
        providerId = id,
        displayName = id,
        type = ProviderType.WINDOW_CREDIT,
        remainingPct = if (available) 50 else null,
        available = available,
    )

    @Test
    fun theStatusSurvivesPollsUntilTheAccountReportsAReading() {
        val vm = viewModel({ ClaudeLoginLaunch(true, "opened") })
        try {
            val results = mapOf("cc-work" to ClaudeLoginLaunch(true, "opened"))
            // Still unreadable — the operator is in the browser. Keep it.
            assertEquals(results, vm.retainLoginResults(results, mapOf("cc-work" to report("cc-work", false))))
            // Not polled at all this round. Keep it.
            assertEquals(results, vm.retainLoginResults(results, emptyMap()))
            // A real reading is the evidence the login took. Retire it.
            assertTrue(vm.retainLoginResults(results, mapOf("cc-work" to report("cc-work", true))).isEmpty())
        } finally {
            vm.close()
        }
    }

    @Test
    fun anotherProvidersReadingDoesNotRetireThisStatus() {
        val vm = viewModel({ ClaudeLoginLaunch(true, "opened") })
        try {
            val results = mapOf("cc-work" to ClaudeLoginLaunch(true, "opened"))
            assertEquals(results, vm.retainLoginResults(results, mapOf("zai-1" to report("zai-1", true))))
        } finally {
            vm.close()
        }
    }
}
