package com.angussoftware.fueldashboard.presentation

import com.angussoftware.fueldashboard.model.ClaudeCodeFleet
import com.angussoftware.fueldashboard.model.MultiProviderSettings
import com.angussoftware.fueldashboard.model.ProviderConfig
import com.angussoftware.fueldashboard.model.ProviderKind
import com.angussoftware.fueldashboard.settings.FuelSettingsStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Watching more than one Claude subscription from one dashboard.
 *
 * Each account has its own `CLAUDE_CONFIG_DIR`, which is where its credentials,
 * its session registry and its routing all live. Two things have to hold once
 * there is more than one:
 *
 *  - the idle gate sees the whole machine, not just the default account, or a
 *    swap kills a turn running under the other subscription;
 *  - "which account is in use" comes from a sensor rather than list order.
 */
class MultiClaudeAccountTest {

    @AfterTest
    fun clearStoredProviders() {
        FuelSettingsStore.saveMultiProvider(MultiProviderSettings())
    }

    private fun vm() = FuelViewModel()

    private val planDefault = ProviderConfig(id = "cc-default", kind = ProviderKind.CLAUDE_CODE)
    private val planWork = ProviderConfig(
        id = "cc-work",
        kind = ProviderKind.CLAUDE_CODE,
        claudeConfigDir = "~/.claude-accounts/work",
    )
    private val zai = ProviderConfig(id = "zai-1", kind = ProviderKind.ZAI, apiKey = "k")

    // --- which directories get read ---------------------------------------

    @Test
    fun readsOneDirectoryPerConfiguredAccount() {
        val vm = vm()
        try {
            assertEquals(
                listOf(null, "~/.claude-accounts/work"),
                vm.claudeConfigDirs(listOf(planDefault, planWork)),
            )
        } finally {
            vm.close()
        }
    }

    @Test
    fun blankAndWhitespaceConfigDirsBothMeanTheDefault() {
        val vm = vm()
        try {
            val padded = planDefault.copy(id = "cc-padded", claudeConfigDir = "   ")
            // Two providers, one directory: the registry must not be read twice
            // and then summed, which would double-count every live session and
            // could report a quiet machine as busy.
            assertEquals(listOf(null), vm.claudeConfigDirs(listOf(planDefault, padded)))
        } finally {
            vm.close()
        }
    }

    @Test
    fun withNoPlanProviderTheDefaultDirectoryIsStillRead() {
        // The gate protects live turns whether or not the dashboard happens to
        // be watching the plan, so an install with no CLAUDE_CODE provider must
        // keep reading exactly the one location it always did.
        val vm = vm()
        try {
            assertEquals(listOf(null), vm.claudeConfigDirs(listOf(zai)))
            assertEquals(listOf(null), vm.claudeConfigDirs(emptyList()))
        } finally {
            vm.close()
        }
    }

    // --- the idle gate ----------------------------------------------------

    @Test
    fun aBusySessionUnderEitherAccountBlocksTheGate() {
        val vm = vm()
        try {
            val readings = listOf(
                ClaudeCodeFleet(busy = 0, idle = 2),
                ClaudeCodeFleet(busy = 1, idle = 0),
            )
            val fleet = vm.aggregateFleet(readings)
            assertFalse(fleet!!.isQuiet, "a turn under the other subscription is just as killable")
            assertEquals(1, fleet.busy)
            assertEquals(3, fleet.total)
        } finally {
            vm.close()
        }
    }

    @Test
    fun anAccountThatHasNeverBeenLoggedIntoDoesNotDeadlockTheGate() {
        // A configured account with no sessions/ directory reads back null. If
        // that counted as unknown, isQuiet would be permanently false and every
        // swap would be refused forever — protecting nothing, blocking
        // everything. It contributes nothing instead, as long as some other
        // directory did read.
        val vm = vm()
        try {
            val fleet = vm.aggregateFleet(listOf(ClaudeCodeFleet(busy = 0, idle = 1), null))
            assertTrue(fleet!!.isQuiet)
            assertEquals(1, fleet.total)
        } finally {
            vm.close()
        }
    }

    @Test
    fun everyRegistryUnreadableIsStillUnknown() {
        // Exactly what a single-account install returned before: null, which the
        // gate treats as "we cannot tell" and refuses on.
        val vm = vm()
        try {
            assertNull(vm.aggregateFleet(listOf(null, null)))
            assertNull(vm.aggregateFleet(emptyList()))
        } finally {
            vm.close()
        }
    }

    // --- which account is live -------------------------------------------

    @Test
    fun theAccountWithLiveSessionsIsTheOneInUse() {
        val vm = vm()
        try {
            val byDir = mapOf<String?, ClaudeCodeFleet?>(
                null to ClaudeCodeFleet(),
                "~/.claude-accounts/work" to ClaudeCodeFleet(busy = 1, idle = 2),
            )
            assertEquals(
                "cc-work",
                vm.liveClaudeProviderId(listOf(planDefault, planWork), byDir),
                "not the first in the list — the one the registry says is running",
            )
        } finally {
            vm.close()
        }
    }

    @Test
    fun idleSessionsStillCountAsTheAccountInUse() {
        // "In use" is about which subscription the sessions are pointed at, not
        // whether they happen to be mid-turn right now.
        val vm = vm()
        try {
            val byDir = mapOf<String?, ClaudeCodeFleet?>(
                null to ClaudeCodeFleet(busy = 0, idle = 4),
                "~/.claude-accounts/work" to ClaudeCodeFleet(),
            )
            assertEquals("cc-default", vm.liveClaudeProviderId(listOf(planDefault, planWork), byDir))
        } finally {
            vm.close()
        }
    }

    @Test
    fun bothAccountsRunningIsReportedAsUnknown() {
        // Per-account config dirs make this genuinely possible, and "both" is
        // not something the IN USE badge can show. Naming one would be a coin
        // flip, so the honest answer is no answer.
        val vm = vm()
        try {
            val byDir = mapOf<String?, ClaudeCodeFleet?>(
                null to ClaudeCodeFleet(busy = 0, idle = 1),
                "~/.claude-accounts/work" to ClaudeCodeFleet(busy = 1, idle = 0),
            )
            assertNull(vm.liveClaudeProviderId(listOf(planDefault, planWork), byDir))
        } finally {
            vm.close()
        }
    }

    @Test
    fun noSessionsAnywhereIsUnknownNotTheFirstProvider() {
        val vm = vm()
        try {
            val byDir = mapOf<String?, ClaudeCodeFleet?>(
                null to ClaudeCodeFleet(),
                "~/.claude-accounts/work" to null,
            )
            assertNull(vm.liveClaudeProviderId(listOf(planDefault, planWork), byDir))
        } finally {
            vm.close()
        }
    }

    @Test
    fun anUnreadableRegistryDoesNotNameItsAccountLive() {
        // null is "cannot see", which is not evidence that this account is the
        // one running.
        val vm = vm()
        try {
            val byDir = mapOf<String?, ClaudeCodeFleet?>(null to null)
            assertNull(vm.liveClaudeProviderId(listOf(planDefault), byDir))
        } finally {
            vm.close()
        }
    }
}
