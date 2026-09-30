package com.angussoftware.fueldashboard.model

/**
 * Reads Claude Code's current routing from local configuration.
 *
 * Returns null when it cannot be determined — not installed, unreadable, or
 * an unsupported platform. Null means "unknown", never "stock Anthropic":
 * the caller must be able to tell those apart, since an absent
 * ANTHROPIC_BASE_URL is itself a positive answer.
 *
 * [configDir] selects which Claude Code configuration directory to read, or
 * null for the default `~/.claude`. Each account under `CLAUDE_CONFIG_DIR`
 * keeps its own `settings.json`, so routing is per-account too.
 */
internal expect fun readClaudeCodeRoute(configDir: String? = null): ClaudeCodeRoute?
