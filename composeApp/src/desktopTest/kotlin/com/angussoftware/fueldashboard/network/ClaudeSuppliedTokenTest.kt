package com.angussoftware.fueldashboard.network

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which token a plan provider polls with, and where it comes from.
 *
 * Two sources: a token supplied through the credential field (a pasted
 * `claude setup-token`, or a `cmd:`/`env:`/`file:` reference already resolved
 * by SecretRef), and the local Claude Code login in the account's config
 * directory. Getting the precedence backwards would either ignore a configured
 * token or break every existing install that leaves the field blank.
 *
 * The local-login cases read real credential files written to a temp
 * directory, not a stubbed reader, so path resolution and expiry handling are
 * exercised for real.
 */
class ClaudeSuppliedTokenTest {

    private val dirs = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        dirs.forEach { it.deleteRecursively() }
    }

    private fun accountDir(accessToken: String, expiresAt: Long): File {
        val dir = Files.createTempDirectory("claude-account").toFile().also { dirs += it }
        File(dir, ".credentials.json").writeText(
            """{"claudeAiOauth":{"accessToken":"$accessToken","expiresAt":$expiresAt}}""",
        )
        return dir
    }

    private val farFuture = System.currentTimeMillis() + 86_400_000L

    @Test
    fun aSuppliedTokenWinsAndTheLocalLoginIsNotRead() {
        var localReads = 0
        val token = claudeCodeBearerToken(
            suppliedToken = "supplied-token",
            configDir = "/anywhere",
            readLocalLogin = { localReads++; "local-token" },
        )
        assertEquals("supplied-token", token)
        // Not merely outranked: never consulted. With a supplied token there may
        // be no credentials file at all, and reading one must not be a precondition.
        assertEquals(0, localReads)
    }

    @Test
    fun aBlankSuppliedTokenMeansUseTheLocalLogin() {
        // A blank credential field resolves to "", and that must fall through to
        // the login rather than be sent as an empty bearer.
        for (blank in listOf(null, "", "   ")) {
            var readDir: String? = "not called"
            val token = claudeCodeBearerToken(blank, "/acct", readLocalLogin = { readDir = it; "local" })
            assertEquals("local", token, "supplied=${blank?.let { "\"$it\"" }}")
            assertEquals("/acct", readDir, "the provider's own config dir must be the one read")
        }
    }

    @Test
    fun theLocalLoginIsReadFromTheProvidersOwnConfigDir() {
        val work = accountDir("work-token", farFuture)
        val personal = accountDir("personal-token", farFuture)
        // Two accounts, two directories, two different tokens — the whole point
        // of the per-provider directory.
        assertEquals("work-token", claudeCodeBearerToken(null, work.absolutePath))
        assertEquals("personal-token", claudeCodeBearerToken(null, personal.absolutePath))
    }

    @Test
    fun anExpiredLocalLoginIsNoTokenRatherThanAStaleOne() {
        val dir = accountDir("stale-token", System.currentTimeMillis() - 1_000)
        // Sending it would only earn a 401; reporting none lets the card say
        // "log in" instead of "the endpoint rejected us".
        assertNull(claudeCodeBearerToken(null, dir.absolutePath))
    }

    @Test
    fun anAbsentAccountDirectoryIsNoTokenRatherThanAnError() {
        assertNull(readClaudeCodeOAuthToken("/nonexistent/account/dir"))
        assertNull(claudeCodeBearerToken(null, "/nonexistent/account/dir"))
    }

    @Test
    fun withNeitherSourceAPollFailsLoudlyInsteadOfReportingAFullTank() = runTest {
        val adapter = ClaudeCodeSubscriptionAdapter(
            providerId = "cc-none",
            configDir = "/nonexistent/account/dir",
        )
        val failure = runCatching { adapter.poll() }.exceptionOrNull()
        assertIs<IllegalStateException>(failure)
        assertTrue(
            failure.message.orEmpty().contains("credentials", ignoreCase = true),
            "should point at the credentials, not the network: ${failure.message}",
        )
    }
}
