package com.angussoftware.fueldashboard.presentation

import com.angussoftware.fueldashboard.engine.SwitchCommandResult
import com.angussoftware.fueldashboard.model.ClaudeCodeFleet
import com.angussoftware.fueldashboard.model.ClaudeCodeRoute
import com.angussoftware.fueldashboard.model.MultiProviderSettings
import com.angussoftware.fueldashboard.model.ProviderConfig
import com.angussoftware.fueldashboard.model.ProviderKind
import com.angussoftware.fueldashboard.settings.FuelSettingsStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two Claude subscriptions, driven through the real view model.
 *
 * [MultiClaudeAccountTest] covers the helpers in isolation; this covers what
 * they are for — the swap gate, the published fleet and IN USE, and the
 * post-swap check — with the fleet and route readers answering per config
 * directory, exactly as the desktop readers do.
 */
class MultiClaudeAccountSwapTest {

    private val workDir = "/accounts/work"

    private val planDefault = ProviderConfig(
        id = "cc-default",
        kind = ProviderKind.CLAUDE_CODE,
        displayName = "Personal plan",
        activateCommand = "/bin/echo to-default",
    )
    private val planWork = ProviderConfig(
        id = "cc-work",
        kind = ProviderKind.CLAUDE_CODE,
        displayName = "Work plan",
        claudeConfigDir = workDir,
        activateCommand = "/bin/echo to-work",
    )
    private val providers = listOf(planDefault, planWork)

    @AfterTest
    fun clearStoredProviders() {
        FuelSettingsStore.saveMultiProvider(MultiProviderSettings())
    }

    /**
     * A view model whose fleet reader answers per directory: [fleets] maps a
     * config dir (null = default ~/.claude) to what its registry says, and
     * [dirsRead] records every directory asked about.
     */
    private fun viewModel(
        fleets: Map<String?, ClaudeCodeFleet?>,
        ran: MutableList<String> = mutableListOf(),
        dirsRead: MutableSet<String?> = mutableSetOf(),
        routes: Map<String?, ClaudeCodeRoute?> = mapOf(null to ClaudeCodeRoute(), workDir to ClaudeCodeRoute()),
    ): FuelViewModel {
        FuelSettingsStore.saveMultiProvider(MultiProviderSettings(providers = providers))
        val vm = FuelViewModel(
            fleetReader = { dir -> dirsRead.add(dir); fleets[dir] },
            routeReader = { dir -> routes[dir] },
            switchRunner = { command -> ran.add(command); SwitchCommandResult(exitCode = 0, output = "ok") },
        )
        vm.updateSettings(MultiProviderSettings(providers = providers))
        return vm
    }

    private fun FuelViewModel.awaitStatus(providerId: String): SwitchRunStatus = runBlocking {
        withTimeout(10_000) {
            var seen: SwitchRunStatus? = null
            while (seen == null) {
                seen = state.value.switchResults[providerId]
                if (seen == null) delay(10)
            }
            seen
        }
    }

    private val quiet = ClaudeCodeFleet(busy = 0, idle = 2)
    private val working = ClaudeCodeFleet(busy = 1, idle = 0)

    // --- the swap gate ----------------------------------------------------

    @Test
    fun aBusySessionOnTheOtherAccountBlocksTheSwap() {
        // The property the whole multi-account gate exists for: swapping TO the
        // work account while the default account has a turn in flight would
        // kill that turn, even though the work account itself is quiet.
        val ran = mutableListOf<String>()
        val vm = viewModel(mapOf(null to working, workDir to quiet), ran = ran)
        try {
            vm.runSwitchCommandNow("cc-work")
            val status = vm.awaitStatus("cc-work")

            assertFalse(status.ok, "a turn on either account must block: ${status.message}")
            assertTrue(status.message.contains("1 of 3"), "counts span both accounts: ${status.message}")
            assertEquals(emptyList(), ran, "nothing may run while a turn is in flight")
        } finally {
            vm.close()
        }
    }

    @Test
    fun theGateReadsEveryConfiguredAccount() {
        val dirsRead = mutableSetOf<String?>()
        val vm = viewModel(mapOf(null to quiet, workDir to quiet), dirsRead = dirsRead)
        try {
            vm.runSwitchCommandNow("cc-work")
            vm.awaitStatus("cc-work")
            assertTrue(null in dirsRead, "the default account's registry must be read")
            assertTrue(workDir in dirsRead, "the work account's registry must be read")
        } finally {
            vm.close()
        }
    }

    @Test
    fun bothAccountsQuietLetsTheSwapRun() {
        val ran = mutableListOf<String>()
        val vm = viewModel(mapOf(null to quiet, workDir to quiet), ran = ran)
        try {
            vm.runSwitchCommandNow("cc-work")
            vm.awaitStatus("cc-work")
            assertEquals(listOf("/bin/echo to-work"), ran)
        } finally {
            vm.close()
        }
    }

    @Test
    fun anAccountNeverLoggedIntoDoesNotBlockForever() {
        // The work account has no sessions/ directory yet, so its reader
        // answers null. That must not make the machine unswappable.
        val ran = mutableListOf<String>()
        val vm = viewModel(mapOf(null to quiet, workDir to null), ran = ran)
        try {
            vm.runSwitchCommandNow("cc-work")
            val status = vm.awaitStatus("cc-work")
            assertEquals(listOf("/bin/echo to-work"), ran, "refused with: ${status.message}")
        } finally {
            vm.close()
        }
    }

    @Test
    fun noReadableRegistryAnywhereStillRefuses() {
        // All-unreadable is still "we cannot tell", exactly as for one account.
        val ran = mutableListOf<String>()
        val vm = viewModel(mapOf(null to null, workDir to null), ran = ran)
        try {
            vm.runSwitchCommandNow("cc-work")
            assertFalse(vm.awaitStatus("cc-work").ok)
            assertEquals(emptyList(), ran)
        } finally {
            vm.close()
        }
    }

    // --- what a refresh publishes ------------------------------------------

    @Test
    fun aRefreshPublishesTheCombinedFleetAndTheLiveAccount() = runBlocking {
        val vm = viewModel(
            mapOf(null to ClaudeCodeFleet(), workDir to ClaudeCodeFleet(busy = 1, idle = 3)),
        )
        try {
            vm.pollOnce()
            val state = vm.state.value
            assertEquals(ClaudeCodeFleet(busy = 1, idle = 3), state.claudeCodeFleet)
            assertEquals(
                "cc-work",
                state.claudeCodeRoute?.matchedProviderId,
                "IN USE must follow the sessions, not the provider list order",
            )
        } finally {
            vm.close()
        }
    }

    @Test
    fun routingIsReadFromTheLiveAccountsDirectory() = runBlocking {
        // Each account keeps its own settings.json. With the work account live
        // and routed elsewhere, the route shown must be the work account's.
        val vm = viewModel(
            fleets = mapOf(null to ClaudeCodeFleet(), workDir to quiet),
            routes = mapOf(
                null to ClaudeCodeRoute(),
                workDir to ClaudeCodeRoute(baseUrl = "https://gateway.example/anthropic"),
            ),
        )
        try {
            vm.pollOnce()
            assertEquals("https://gateway.example/anthropic", vm.state.value.claudeCodeRoute?.baseUrl)
        } finally {
            vm.close()
        }
    }

    @Test
    fun bothAccountsRunningPublishesNoLiveAccount() = runBlocking {
        val vm = viewModel(mapOf(null to quiet, workDir to working))
        try {
            vm.pollOnce()
            assertNull(
                vm.state.value.claudeCodeRoute?.matchedProviderId,
                "with both accounts in use, marking one IN USE would be a coin flip",
            )
        } finally {
            vm.close()
        }
    }

    // --- verifying a swap took effect -------------------------------------

    @Test
    fun aSwapIsConfirmedOnlyWhenTheTargetAccountIsTheLiveOne() {
        // After the command, sessions show up under the work directory: that is
        // the evidence the swap took, not the command's exit code.
        val vm = viewModel(mapOf(null to ClaudeCodeFleet(), workDir to quiet))
        try {
            vm.runSwitchCommandNow("cc-work")
            val status = vm.awaitStatus("cc-work")
            assertTrue(status.ok, status.message)
            assertTrue(status.message.startsWith("Swapped to Work plan"), status.message)
        } finally {
            vm.close()
        }
    }

    @Test
    fun aSwapWhoseSessionsStayOnTheOldAccountIsNotReportedAsDone() {
        // Exit 0, but every session is still registered under the default
        // account: the command ran, the account did not change.
        val vm = viewModel(mapOf(null to quiet, workDir to ClaudeCodeFleet()))
        try {
            vm.runSwitchCommandNow("cc-work")
            val status = vm.awaitStatus("cc-work")
            assertFalse(
                status.message.startsWith("Swapped to"),
                "must not claim success while the old account is still live: ${status.message}",
            )
        } finally {
            vm.close()
        }
    }
}
