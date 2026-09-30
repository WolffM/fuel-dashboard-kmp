package com.angussoftware.fueldashboard.network

/**
 * Outcome of asking for a Claude Code login.
 *
 * "Launched" is as much as this can honestly report. The OAuth flow is
 * interactive — it opens a browser and waits for the operator to come back —
 * so the app is not in a position to know whether it succeeded. What tells us
 * that is the gauge: the next poll of that account either finds a usable token
 * or does not.
 */
data class ClaudeLoginLaunch(
    val launched: Boolean,
    val message: String,
)

/**
 * Opens an interactive `claude /login` for one account.
 *
 * This is the one Claude Code operation that cannot be done for the user. A
 * subscription login is an OAuth round trip through a browser, so it needs a
 * terminal and a person; there is no headless form of it and no token this app
 * could mint. What the button buys is that the operator does not have to
 * remember which environment variable selects which account — getting that
 * wrong logs the second subscription into the first one's directory, which
 * silently overwrites a working login.
 *
 * [configDir] is the account's configuration directory, or null for the
 * default `~/.claude`. It is passed to the child through its ENVIRONMENT, never
 * interpolated into a command line, so a path typed into a settings field
 * cannot become a shell injection.
 *
 * Implementations must not wait for the flow to finish — it takes as long as a
 * person takes — and must never throw: every failure comes back as a
 * [ClaudeLoginLaunch] so it can be shown on the card.
 */
internal expect suspend fun launchClaudeLogin(configDir: String?): ClaudeLoginLaunch

/** Whether this platform can open an interactive login at all. */
internal expect val claudeLoginSupported: Boolean
