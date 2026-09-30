package com.angussoftware.fueldashboard.model

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop readers, pointed at real account directories on disk.
 *
 * Each account's session registry and routing file live inside its own config
 * directory; these check the readers find them there, and that blank still
 * means the default `~/.claude` (the test JVM's user.home is sandboxed into the
 * build directory, so that location is safe to write).
 */
class ClaudeAccountReadersTest {

    private val cleanup = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        cleanup.forEach { it.deleteRecursively() }
    }

    private fun tempAccount(): File =
        Files.createTempDirectory("claude-account").toFile().also { cleanup += it }

    /** A live pid: this test JVM. A dead one: far above any pid_max. */
    private val livePid = ProcessHandle.current().pid()
    private val deadPid = 999_999_999L

    private fun File.session(pid: Long, status: String) {
        File(this, "sessions").apply { mkdirs() }
            .resolve("$pid.json")
            .writeText("""{"pid":$pid,"status":"$status","sessionId":"s-$pid"}""")
    }

    @Test
    fun theFleetIsReadFromTheAccountsOwnDirectory() {
        val work = tempAccount().apply { session(livePid, "busy") }
        val personal = tempAccount().apply { session(livePid, "idle") }

        assertEquals(ClaudeCodeFleet(busy = 1), readClaudeCodeFleet(work.absolutePath))
        assertEquals(ClaudeCodeFleet(idle = 1), readClaudeCodeFleet(personal.absolutePath))
    }

    @Test
    fun aRegistryEntryForADeadProcessIsNotASession() {
        val dir = tempAccount().apply {
            session(livePid, "idle")
            session(deadPid, "busy")
        }
        assertEquals(ClaudeCodeFleet(idle = 1), readClaudeCodeFleet(dir.absolutePath))
    }

    @Test
    fun anAccountWithNoSessionsDirectoryIsUnreadableNotEmpty() {
        // What a configured-but-never-used account looks like. Null, which the
        // view model treats as "contributes nothing" — not an empty fleet,
        // which would read as quiet.
        assertNull(readClaudeCodeFleet(tempAccount().absolutePath))
    }

    @Test
    fun routingIsReadFromTheAccountsOwnSettings() {
        val dir = tempAccount()
        File(dir, "settings.json").writeText(
            """{"env":{"ANTHROPIC_BASE_URL":"https://gateway.example/anthropic"},"permissions":{"defaultMode":"auto"}}""",
        )
        val route = readClaudeCodeRoute(dir.absolutePath)!!
        assertEquals("https://gateway.example/anthropic", route.baseUrl)
        assertEquals("auto", route.permissionMode)
    }

    @Test
    fun anAccountOnTheStockEndpointReadsAsDefaultAnthropic() {
        val dir = tempAccount()
        File(dir, "settings.json").writeText("""{"env":{}}""")
        assertTrue(readClaudeCodeRoute(dir.absolutePath)!!.isDefaultAnthropic)
    }

    @Test
    fun blankStillMeansTheDefaultAccount() {
        // Every provider that existed before the per-account directory did has
        // it blank, and must keep reading ~/.claude.
        val home = File(System.getProperty("user.home"), ".claude")
        val created = !home.exists()
        home.mkdirs()
        val settings = File(home, "settings.json")
        val previous = settings.takeIf { it.exists() }?.readText()
        try {
            settings.writeText("""{"env":{"ANTHROPIC_BASE_URL":"https://default.example"}}""")
            assertEquals("https://default.example", readClaudeCodeRoute(null)?.baseUrl)
            assertEquals("https://default.example", readClaudeCodeRoute("  ")?.baseUrl)
        } finally {
            if (previous != null) settings.writeText(previous) else settings.delete()
            if (created) home.deleteRecursively()
        }
    }
}
