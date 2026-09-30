package com.angussoftware.fueldashboard.model

import java.io.File

/**
 * Resolves a configured Claude Code configuration directory to a real path.
 *
 * Blank or null means the default `~/.claude`, which is where Claude Code
 * keeps everything when `CLAUDE_CONFIG_DIR` is unset. Anything else is taken
 * as given, with a leading `~/` expanded because that is how a user types a
 * home-relative path into a settings field and `File` does not expand it.
 *
 * Deliberately does not verify that the directory exists or looks like a
 * Claude Code install. Each caller already returns null for an unreadable
 * file, and that is the honest answer: "nothing to read here" rather than a
 * validation error on a directory that may simply not have been logged into
 * yet.
 */
internal fun resolveClaudeConfigDir(configDir: String?): File {
    val home = System.getProperty("user.home")
    val trimmed = configDir?.trim().orEmpty()
    if (trimmed.isEmpty()) return File(home, ".claude")
    if (trimmed == "~") return File(home)
    if (trimmed.startsWith("~/")) return File(home, trimmed.substring(2))
    return File(trimmed)
}
