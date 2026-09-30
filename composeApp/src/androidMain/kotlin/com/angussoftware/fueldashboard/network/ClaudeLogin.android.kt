package com.angussoftware.fueldashboard.network

/**
 * Claude Code does not run on Android, so there is no login to open here. The
 * plan gauge reaches this device through a Remote Dashboard pointed at the
 * desktop app, and the login happens on that machine.
 */
internal actual suspend fun launchClaudeLogin(configDir: String?): ClaudeLoginLaunch =
    ClaudeLoginLaunch(
        launched = false,
        message = "Log in to Claude Code on the desktop machine — it is where the accounts live.",
    )

internal actual val claudeLoginSupported: Boolean = false
