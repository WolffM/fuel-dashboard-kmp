package com.angussoftware.fueldashboard.model

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException

/**
 * One-time-token pairing bridge — client side.
 *
 * Fleet SSOT: fleet-shared/knowledge/agent-app-bridge-pattern.md. An agent
 * mints a single-use token on the desktop dashboard (`POST /pair`, server-key
 * auth) and fires `fueldashboard://import?token=X&url=Y` on the phone; the
 * phone redeems the token itself via `GET {url}/pair/{token}` and applies the
 * payload through the normal import path. The long-lived server API key never
 * appears in any intent, transcript, or log — the token alone rides the link,
 * and it is single-use with a 60s TTL, so its appearing in an agent command is
 * acceptable by design.
 *
 * First implementation of the fleet pattern; when a second app needs it, this
 * file is the extraction seed for a shared bridge module (per the SSOT's
 * shared-kernel rule — no premature module yet).
 */
data class PairingLink(
    val token: String,
    val url: String,
    val autostart: Boolean = false,
) {
    companion object {
        const val SCHEME = "fueldashboard"
        const val HOST = "import"

        /**
         * Parses a `fueldashboard://import?token=X&url=Y[&autostart=0|1]`
         * link. Returns null when the scheme/host is wrong or token/url is
         * missing — receivers treat null as "invalid pairing link" and never
         * issue a fetch.
         *
         * Values may be percent-encoded (`%3A%2F%2F` for `://`) or plain
         * (`url=http://192.168.1.5:8322`) — both parse. An unencoded URL that
         * itself carries `&`-separated query params requires encoding; that is
         * the sender's contract (see docs/self-hosting.md → Headless pairing).
         */
        fun parse(link: String): PairingLink? {
            if (!link.startsWith("$SCHEME://", ignoreCase = true)) return null
            val rest = link.substring(SCHEME.length + 3)
            val queryStart = rest.indexOf('?')
            if (queryStart < 0) return null
            val host = rest.substring(0, queryStart)
            if (!host.equals(HOST, ignoreCase = true)) return null

            val params = parseQuery(rest.substring(queryStart + 1))
            val token = params["token"]?.trim().orEmpty()
            val url = params["url"]?.trim().orEmpty()
            if (token.isEmpty() || url.isEmpty()) return null
            return PairingLink(
                token = token,
                url = url,
                autostart = params["autostart"]?.let { isAutostartTruthy(it) } ?: false,
            )
        }

        private fun isAutostartTruthy(value: String): Boolean =
            value == "1" || value.equals("true", ignoreCase = true)

        /** Minimal `k=v&k=v` parser — later keys win, `%XX` is decoded. */
        private fun parseQuery(query: String): Map<String, String> {
            val params = mutableMapOf<String, String>()
            for (pair in query.split('&')) {
                val eq = pair.indexOf('=')
                if (eq <= 0) continue
                params[percentDecode(pair.substring(0, eq))] = percentDecode(pair.substring(eq + 1))
            }
            return params
        }

        /**
         * Decodes `%XX` sequences. Pairing params are ASCII by construction
         * (UUID tokens, URLs), so byte-accurate UTF-8 recovery is out of
         * scope — malformed sequences are kept literal and never throw: a
         * hostile link must not crash the receiver.
         */
        private fun percentDecode(value: String): String {
            if ('%' !in value) return value
            val out = StringBuilder(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '%' && i + 2 < value.length &&
                    isHexDigit(value[i + 1]) && isHexDigit(value[i + 2])
                ) {
                    out.append(((hexValue(value[i + 1]) shl 4) or hexValue(value[i + 2])).toChar())
                    i += 3
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }

        private fun isHexDigit(c: Char): Boolean =
            c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

        private fun hexValue(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            else -> c - 'A' + 10
        }
    }

    /**
     * Whether the fetch needs one explicit user tap first. Fleet SSOT:
     * HTTPS is transport-trusted; plain HTTP is fine on loopback
     * (same device); plain HTTP to another device requires consent.
     */
    fun requiresLanConsent(): Boolean {
        if (!url.startsWith("http://", ignoreCase = true)) return false
        return !isLoopbackHost(hostOf(url))
    }
}

private fun hostOf(url: String): String {
    val afterScheme = url.substringAfter("//", missingDelimiterValue = "")
    if (afterScheme.isEmpty()) return ""
    if (afterScheme.startsWith("[")) {
        // Bracketed IPv6 literal — the host ends at the closing bracket.
        val close = afterScheme.indexOf(']')
        return if (close > 0) afterScheme.substring(1, close) else ""
    }
    var end = afterScheme.length
    for (delimiter in charArrayOf('/', ':', '?', '#')) {
        val idx = afterScheme.indexOf(delimiter)
        if (idx in 0 until end) end = idx
    }
    return afterScheme.substring(0, end)
}

private fun isLoopbackHost(host: String): Boolean =
    host.equals("localhost", ignoreCase = true) ||
        host == "::1" ||
        host == "0:0:0:0:0:0:0:1" ||
        host.startsWith("127.")

/** Outcome of a one-shot pairing fetch. */
sealed interface PairingFetchResult {
    /** Settings fetched and applied. */
    data class Success(val syncData: SettingsSyncData) : PairingFetchResult

    /** 410/404 — token already used, expired, or never minted. */
    data object Expired : PairingFetchResult

    /** Anything else — network failure, unexpected status, unparseable payload. */
    data object NetworkError : PairingFetchResult
}

/**
 * Redeems a one-time pairing token and hands the payload to [apply].
 *
 * This owns the fetch and the failure mapping; the caller owns where the
 * data lands (both platforms apply via `FuelViewModel.importSyncedSettings`
 * — the same path QR/text-code imports use). No retry loops: one shot per
 * fleet SSOT, tokens are dead after one redemption anyway.
 */
object PairingClient {
    /** Total wall-clock budget for the redemption (spec: 10s). */
    private const val TIMEOUT_MS = 10_000L

    suspend fun fetchAndApply(
        link: PairingLink,
        apply: suspend (SettingsSyncData) -> Unit,
    ): PairingFetchResult {
        val redemptionUrl = link.url.trimEnd('/') + "/pair/" + link.token.encodeURLPathPart()
        return try {
            val response = client.get(redemptionUrl)
            when {
                response.status == HttpStatusCode.OK -> {
                    val syncData = SettingsSyncData.fromJson(response.bodyAsText())
                    if (syncData == null) {
                        PairingFetchResult.NetworkError
                    } else {
                        apply(syncData)
                        PairingFetchResult.Success(syncData)
                    }
                }
                response.status == HttpStatusCode.Gone ||
                    response.status == HttpStatusCode.NotFound -> PairingFetchResult.Expired
                else -> PairingFetchResult.NetworkError
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PairingFetchResult.NetworkError
        }
    }

    // Dedicated client: the shared 20s-timeout client is tuned for provider
    // polling behind corporate networks; pairing must give up at 10s.
    private val client by lazy {
        HttpClient {
            install(HttpTimeout) {
                requestTimeoutMillis = TIMEOUT_MS
                connectTimeoutMillis = TIMEOUT_MS
                socketTimeoutMillis = TIMEOUT_MS
            }
        }
    }
}
