package com.angussoftware.fueldashboard.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What counts as "quiet enough to restart the fleet".
 *
 * The asymmetry drives every case here: a false "busy" costs a delayed
 * switch, a false "quiet" costs someone's in-flight turn. So anything short
 * of complete confidence reads as not-quiet.
 */
class ClaudeCodeFleetTest {

    @Test
    fun allIdleIsQuiet() {
        val fleet = ClaudeCodeFleet(busy = 0, idle = 25, unknown = 0)
        assertTrue(fleet.isQuiet)
        assertEquals(25, fleet.total)
    }

    @Test
    fun oneBusySessionBlocks() {
        assertFalse(ClaudeCodeFleet(busy = 1, idle = 24, unknown = 0).isQuiet)
    }

    @Test
    fun anUnreadableSessionBlocks() {
        // It might be mid-turn. Guessing wrong kills that turn, so an
        // unreadable session counts against quiet rather than being ignored.
        assertFalse(ClaudeCodeFleet(busy = 0, idle = 24, unknown = 1).isQuiet)
    }

    @Test
    fun anEmptyRegistryIsNotQuiet() {
        // Zero sessions means the registry told us nothing — not that nobody
        // is working. Treating it as quiet would let a switch fire against a
        // fleet we simply failed to see.
        assertFalse(ClaudeCodeFleet().isQuiet)
        assertEquals(0, ClaudeCodeFleet().total)
    }

    @Test
    fun describeNamesEveryBucketForTheDecisionLog() {
        assertEquals(
            "busy=2 idle=20 unknown=1",
            ClaudeCodeFleet(busy = 2, idle = 20, unknown = 1).describe(),
        )
    }

    // --- Several accounts on one machine ----------------------------------

    @Test
    fun summingKeepsBusyMeaningBusy() {
        // A turn under either subscription is equally killable by a respawn, so
        // busy anywhere must make the whole machine non-quiet.
        val quiet = ClaudeCodeFleet(busy = 0, idle = 3)
        val working = ClaudeCodeFleet(busy = 1, idle = 0)
        assertFalse((quiet + working).isQuiet)
        assertFalse((working + quiet).isQuiet)
        assertEquals(1, (quiet + working).busy)
        assertEquals(3, (quiet + working).idle)
    }

    @Test
    fun summingKeepsOneUnreadableRegistryBlocking() {
        // Same invariant as a single account: a session we cannot read might be
        // mid-turn, and that doubt has to survive being added to a quiet fleet.
        val quiet = ClaudeCodeFleet(busy = 0, idle = 2)
        val murky = ClaudeCodeFleet(unknown = 1)
        assertFalse((quiet + murky).isQuiet)
        assertEquals(1, (quiet + murky).unknown)
    }

    @Test
    fun twoQuietAccountsAreQuiet() {
        val a = ClaudeCodeFleet(busy = 0, idle = 1)
        val b = ClaudeCodeFleet(busy = 0, idle = 4)
        assertTrue((a + b).isQuiet)
        assertEquals(5, (a + b).total)
    }

    @Test
    fun twoEmptyRegistriesAreStillNotQuiet() {
        // total == 0 means the registries told us nothing, which is not the
        // same as "nobody is working" — and summing two nothings is nothing.
        assertFalse((ClaudeCodeFleet() + ClaudeCodeFleet()).isQuiet)
    }
}
