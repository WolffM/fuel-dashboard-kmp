package com.angussoftware.fueldashboard.network

import com.angussoftware.fueldashboard.model.resolveClaudeConfigDir
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * Reads the OAuth access token Claude Code keeps at `.credentials.json` inside
 * its configuration directory (mode 0600, written by `/login`) — `~/.claude`
 * by default, or whatever [configDir] names for a second account.
 *
 * Returns null on every failure — absent file, unreadable, unexpected shape,
 * or an expired token — because the caller must be able to tell "no reading"
 * apart from "quota is fine". The token is returned to the adapter and
 * nothing else: it is never logged, never written anywhere, and not cached
 * here, so a `/login` refresh is picked up on the next poll.
 *
 * An expired token is treated as absent rather than sent. Claude Code
 * refreshes it on its own next request; minting tokens is not this app's job,
 * and a stale bearer would only earn a 401.
 */
internal actual fun readClaudeCodeOAuthToken(configDir: String?): String? {
    val file = File(resolveClaudeConfigDir(configDir), ".credentials.json")
    if (!file.isFile || !file.canRead()) return null

    val oauth = runCatching {
        SharedHttpClient.json
            .parseToJsonElement(file.readText())
            .jsonObject["claudeAiOauth"]
            ?.jsonObject
    }.getOrNull() ?: return null

    val expiresAt = oauth["expiresAt"]?.jsonPrimitive?.longOrNull
    if (expiresAt != null && expiresAt > 0 && System.currentTimeMillis() >= expiresAt) {
        return null
    }

    return oauth["accessToken"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
}

internal actual val claudeCodeCredentialsUnavailableHint: String =
    "No usable Claude Code credentials in this provider's config directory — " +
        "run /login in Claude Code (with CLAUDE_CONFIG_DIR set to it for a second " +
        "account), or start a session there to refresh an expired token."
