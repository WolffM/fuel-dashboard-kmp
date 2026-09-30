package com.angussoftware.fueldashboard.model

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Turning the string a user types into a settings field into a real directory.
 *
 * The test JVM's `user.home` is redirected into the build directory (see
 * composeApp/build.gradle.kts), so these assertions are about the shape of the
 * resolution rather than any real Claude install.
 */
class ClaudeConfigDirTest {

    private val home: String = System.getProperty("user.home")

    @Test
    fun blankMeansTheDefaultAccount() {
        // Every provider that existed before this field did has it blank, and
        // must keep reading exactly where it always read.
        val expected = File(home, ".claude")
        assertEquals(expected, resolveClaudeConfigDir(null))
        assertEquals(expected, resolveClaudeConfigDir(""))
        assertEquals(expected, resolveClaudeConfigDir("   "))
    }

    @Test
    fun aTildePathIsExpandedBecauseFileWillNotDoIt() {
        // "~/.claude-accounts/work" is how a home-relative path gets typed into
        // a text field. java.io.File treats the tilde as a literal directory
        // name, which would silently look in the wrong place.
        assertEquals(
            File(home, ".claude-accounts/work"),
            resolveClaudeConfigDir("~/.claude-accounts/work"),
        )
        assertEquals(File(home), resolveClaudeConfigDir("~"))
    }

    @Test
    fun anAbsolutePathIsTakenAsGiven() {
        assertEquals(
            File("/srv/claude-accounts/ops"),
            resolveClaudeConfigDir("/srv/claude-accounts/ops"),
        )
    }

    @Test
    fun surroundingWhitespaceIsIgnored() {
        // Paths get pasted, and a trailing space or newline is not a different
        // directory.
        assertEquals(
            File(home, ".claude-accounts/work"),
            resolveClaudeConfigDir("  ~/.claude-accounts/work \n"),
        )
    }

    @Test
    fun aTildeInsideThePathIsNotExpanded() {
        // Only a leading ~/ is a home reference; anywhere else it is a literal
        // character in a directory name, and rewriting it would resolve to a
        // path the user did not ask for.
        assertEquals(
            File("/srv/back~up/claude"),
            resolveClaudeConfigDir("/srv/back~up/claude"),
        )
    }
}
