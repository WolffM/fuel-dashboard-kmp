package com.angussoftware.fueldashboard.model

/**
 * What Claude Code's own live-session registry says about the fleet.
 *
 * Claude Code writes one file per running session under `~/.claude/sessions/`
 * carrying a `status` of `busy` or `idle`. That is the authoritative idle
 * signal — more so than anything this app could infer from polling — and it
 * is what makes it safe to act.
 *
 * This exists because a switch command is destructive: the one that motivated
 * it respawns every pane, which kills whatever turn is in flight. Completed
 * turns are durable on disk; the live one is not. So an action must wait for
 * quiet rather than interrupt.
 */
data class ClaudeCodeFleet(
    val busy: Int = 0,
    val idle: Int = 0,
    /** Sessions whose status could not be read or looked stale. */
    val unknown: Int = 0,
) {
    val total: Int get() = busy + idle + unknown

    /**
     * True only when every session is accounted for and none is working.
     *
     * `unknown` counts against quiet deliberately: a session we cannot read
     * might be mid-turn, and the cost of guessing wrong is a killed turn. An
     * empty fleet is not quiet either — it means the registry told us
     * nothing, which is not the same as "nobody is working".
     */
    val isQuiet: Boolean get() = total > 0 && busy == 0 && unknown == 0

    fun describe(): String = "busy=$busy idle=$idle unknown=$unknown"

    /**
     * Combines two accounts' registries into one fleet reading.
     *
     * Needed because one machine can now run Claude Code under several
     * configuration directories, each with its own `sessions/`. The idle gate
     * protects in-flight turns, and a turn is just as real whichever account
     * it is billed to — so the gate has to see the whole machine, not the
     * account being swapped away from. Summing is the only combination that
     * preserves [isQuiet]'s meaning: busy anywhere is busy, and one
     * unreadable registry keeps the whole fleet from reading quiet.
     */
    operator fun plus(other: ClaudeCodeFleet): ClaudeCodeFleet = ClaudeCodeFleet(
        busy = busy + other.busy,
        idle = idle + other.idle,
        unknown = unknown + other.unknown,
    )
}

/**
 * Reads the live-session registry for one Claude Code configuration directory.
 *
 * Returns null when the registry cannot be read at all — not installed, or an
 * unsupported platform — which the caller must treat as "unknown", never as
 * "quiet".
 *
 * [configDir] is the configuration directory to read, or null for the default
 * `~/.claude`. It exists because `CLAUDE_CONFIG_DIR` gives each Claude account
 * its own directory, and this registry lives inside it: reading only the
 * default location would report an empty registry for every account running
 * elsewhere. An empty registry is not quiet ([ClaudeCodeFleet.isQuiet] requires
 * `total > 0`), so getting this wrong fails closed and refuses every swap
 * rather than interrupting a live turn — but it does refuse them all, which is
 * why each configured account must be read.
 */
internal expect fun readClaudeCodeFleet(configDir: String? = null): ClaudeCodeFleet?
