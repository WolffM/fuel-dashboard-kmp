package com.angussoftware.fueldashboard.network

import com.angussoftware.fueldashboard.model.ClaudeCodeUsageLimit
import com.angussoftware.fueldashboard.model.ClaudeCodeUsageResponse
import com.angussoftware.fueldashboard.model.ClaudeCodeUsageWindow
import com.angussoftware.fueldashboard.model.ProviderAdapter
import com.angussoftware.fueldashboard.model.ProviderReport
import com.angussoftware.fueldashboard.model.ProviderType
import com.angussoftware.fueldashboard.model.ReportWindow
import com.angussoftware.fueldashboard.usage.parseIsoMillis
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlin.math.roundToInt

/**
 * Reports Claude Code **subscription** quota — the Max/Pro plan's 5-hour and
 * 7-day windows — which no other adapter here can see.
 *
 * This is the gauge [AnthropicProviderAdapter] cannot provide. That adapter
 * speaks to the developer console: admin key, `/v1/organizations/cost_report`,
 * rate-limit headers. None of it observes plan utilization, so a Max
 * subscriber currently has no fuel reading at all for the plan they actually
 * work against.
 *
 * ## Credentials
 *
 * Authentication uses the OAuth access token Claude Code already stores on
 * this machine — there is no API key to paste, and the provider needs no
 * configuration beyond being added. The token is read locally, sent only to
 * `api.anthropic.com`, and never logged, cached or persisted by this app. It
 * is deliberately not a constructor default that could be captured: the
 * provider is re-read on every poll so a `/login` refresh is picked up
 * without restarting.
 *
 * More than one account can be watched at once, in either of two ways.
 *
 * Give each provider its own [configDir] and each reads that account's local
 * login. The catch is lifetime: only the account Claude Code is currently
 * running as gets its access token refreshed, and those tokens live for hours
 * while the refresh token lives for weeks. An account nothing is running under
 * therefore goes unreadable on its own, which this reports as unknown rather
 * than minting a token — correct, but it also means an idle account is never an
 * automatic swap target (see `SwapTarget`).
 *
 * Or give each provider a [suppliedToken] through the credential field. A
 * long-lived token from `claude setup-token` does not expire on the timescale
 * of a poll, so an idle account keeps reporting and stays a candidate for an
 * automatic swap. That is the reason to prefer it, beyond keeping the
 * credential out of a file.
 *
 * Desktop only. Android and iOS have no access to the credentials file, so
 * [poll] fails there with a clear message; mobile receives this gauge the
 * same way it receives every other one, through a Remote Dashboard.
 *
 * ## Reporting unknown
 *
 * A window whose `utilization` is absent is omitted entirely rather than
 * reported as 0% or 100%. An invented reading does not merely look wrong on
 * the tile — it feeds burn-rate history, the waste tiles, the Advisor's
 * regime classification and alert suppression (see the H4 note in
 * [ZaiProviderAdapter]). Unknown must stay unknown.
 */
class ClaudeCodeSubscriptionAdapter(
    override val providerId: String,
    private val baseUrl: String = "https://api.anthropic.com",
    customDisplayName: String? = null,
    /**
     * Which Claude Code configuration directory this account's credentials
     * live in; null or blank means the default `~/.claude`.
     *
     * This is the whole reason two subscriptions can be watched at once. It is
     * read on every poll rather than resolved once, so the account's token is
     * picked up after a `/login` or a switch without restarting.
     */
    private val configDir: String? = null,
    /**
     * A bearer token supplied by settings, or null to read this machine's
     * local Claude Code login.
     *
     * This is the vault path. The provider's credential field accepts the same
     * `cmd:` / `env:` / `file:` references every other provider's key does, so
     * a token can come from a broker on each activation instead of sitting in
     * a file — which is what `claude setup-token` produces and what
     * `CLAUDE_CODE_OAUTH_TOKEN` feeds Claude Code itself.
     *
     * Blank means the status quo: read the login at [configDir]. That keeps
     * every existing install working untouched, and keeps the zero-config case
     * genuinely zero-config.
     */
    private val suppliedToken: String? = null,
    /** Overridden in tests; production applies [claudeCodeBearerToken]. */
    private val tokenProvider: () -> String? = { claudeCodeBearerToken(suppliedToken, configDir) },
) : ProviderAdapter {

    override val displayName: String = customDisplayName ?: "Claude Code"
    override val providerType: ProviderType = ProviderType.WINDOW_CREDIT

    private val client = SharedHttpClient.client

    companion object {
        private const val USAGE_PATH = "/api/oauth/usage"

        /**
         * Window lengths are plan constants, NOT derived from `resets_at`.
         *
         * Deriving the length from the reset instant makes elapsed ≡ total, so
         * the hourglass pins at 100% forever (review 1975 on the z.ai session
         * window). The 5-hour window is 5 hours whether it resets in four
         * minutes or four hours.
         */
        private const val FIVE_HOUR_WINDOW_HOURS = 5.0
        private const val SEVEN_DAY_WINDOW_HOURS = 168.0

        internal const val FIVE_HOUR_NAME = "5-hour"
        internal const val SEVEN_DAY_NAME = "Weekly"
    }

    override suspend fun poll(): ProviderReport {
        val token = tokenProvider()
            ?: throw IllegalStateException(claudeCodeCredentialsUnavailableHint)

        val http: HttpResponse = client.get("$baseUrl$USAGE_PATH") {
            header(HttpHeaders.Authorization, "Bearer $token")
            // Both headers are required; the endpoint 401s without the beta opt-in.
            header("anthropic-beta", "oauth-2025-04-20")
            header("anthropic-version", "2023-06-01")
        }

        // The status MUST be checked before deserializing. Ktor defaults to
        // expectSuccess = false, and every field of ClaudeCodeUsageResponse has
        // a default, so an error body parses happily into an EMPTY response —
        // no windows, available = false. A 429 then renders as "this plan
        // reports no data" instead of "we are being throttled", and because
        // nothing threw, the failure backoff never engaged and the dashboard
        // kept hammering the endpoint that was already refusing it.
        failureFor(http.status, http.headers[HttpHeaders.RetryAfter])?.let { throw it }

        return mapToProviderReport(http.body())
    }

    /**
     * Pure mapping from the endpoint's payload to a [ProviderReport].
     *
     * Separated from [poll] so the whole translation — including every
     * degenerate payload — is testable without a network or a credentials
     * file.
     */
    internal fun mapToProviderReport(response: ClaudeCodeUsageResponse): ProviderReport {
        val severityByKind = response.limits
            .filter { it.severity != null }
            .associate { it.kind to it.severity }

        val fiveHour = windowOf(
            response.fiveHour,
            FIVE_HOUR_NAME,
            FIVE_HOUR_WINDOW_HOURS,
        )
        val weekly = windowOf(
            response.sevenDay,
            SEVEN_DAY_NAME,
            SEVEN_DAY_WINDOW_HOURS,
        )

        // The 5-hour window leads: it is the one that gates the next turn and
        // its countdown is legible in hours. The headline's %, reset and
        // window length all come from that single window — never mixed across
        // two, which would pair one window's percentage with another's
        // countdown (PR #84).
        val windows = listOfNotNull(fiveHour, weekly)
        val headline = windows.firstOrNull()

        val severity = severityByKind[ClaudeCodeUsageLimit.KIND_WEEKLY_ALL]
            ?: severityByKind[ClaudeCodeUsageLimit.KIND_SESSION]

        return ProviderReport(
            providerId = providerId,
            displayName = displayName,
            type = providerType,
            remainingPct = headline?.remainingPct,
            resetsAt = headline?.resetsAt,
            windowHours = headline?.windowHours ?: 0.0,
            available = windows.isNotEmpty(),
            windows = windows,
            detail = severity,
            rawDisplay = listOfNotNull(
                response.fiveHour?.utilization?.let { "5h:${it.roundToInt()}%" },
                response.sevenDay?.utilization?.let { "weekly:${it.roundToInt()}%" },
            ).joinToString(" ").let { if (it.isEmpty()) it else "used $it" },
        )
    }

    /**
     * One window, or null when the payload did not actually report it.
     *
     * Returning null is what keeps "unknown" distinct from "empty": the
     * caller omits the window, the headline falls through to the next one,
     * and [ProviderReport.available] goes false if nothing is known at all.
     */
    private fun windowOf(
        window: ClaudeCodeUsageWindow?,
        name: String,
        windowHours: Double,
    ): ReportWindow? {
        val utilization = window?.utilization ?: return null
        val resetsAt = window.resetsAt?.let { parseIsoMillis(it) }
        return ReportWindow(
            name = name,
            remainingPct = (100.0 - utilization).roundToInt().coerceIn(0, 100),
            resetsAt = resetsAt,
            windowHours = windowHours,
            resetEstimated = resetsAt == null,
        )
    }

    override fun close() = Unit
}

/**
 * The bearer token a plan provider polls with: a token supplied through
 * settings when there is one, otherwise the local Claude Code login for
 * [configDir].
 *
 * A top-level function rather than inline in the adapter so the precedence is
 * tested directly. Blank counts as "not supplied", because that is what an
 * empty credential field resolves to, and it must mean "use the local login"
 * rather than "send an empty bearer".
 */
internal fun claudeCodeBearerToken(
    suppliedToken: String?,
    configDir: String?,
    readLocalLogin: (String?) -> String? = { readClaudeCodeOAuthToken(it) },
): String? = suppliedToken?.takeIf { it.isNotBlank() } ?: readLocalLogin(configDir)

/**
 * Raised when the usage endpoint answers with a non-2xx status.
 *
 * [retryAfterMs] is non-null only when the server said how long to wait; the
 * caller uses it to park this provider instead of re-asking on the next tick.
 */
internal class ClaudeCodeUsageHttpException(
    message: String,
    val retryAfterMs: Long?,
) : IllegalStateException(message)

/**
 * The exception to throw for [status], or null when the response is usable.
 *
 * Split out from [ClaudeCodeSubscriptionAdapter.poll] so every status can be
 * covered without standing up an HTTP server.
 */
internal fun failureFor(
    status: HttpStatusCode,
    retryAfterHeader: String?,
): ClaudeCodeUsageHttpException? {
    if (status.isSuccess()) return null

    val retryAfterMs = retryAfterHeader?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.times(1000)
    val message = when (status.value) {
        429 -> "Rate limited by api.anthropic.com" +
            (retryAfterMs?.let { " — retrying in ${it / 1000}s" } ?: " — backing off")
        401, 403 -> "Claude Code token rejected (${status.value}) — run /login in Claude Code"
        in 500..599 -> "api.anthropic.com is failing (${status.value}) — backing off"
        else -> "Usage endpoint returned ${status.value}"
    }
    return ClaudeCodeUsageHttpException(message, retryAfterMs)
}

/**
 * The OAuth access token Claude Code stores on this machine, or null when it
 * cannot be read (not logged in, expired, or an unsupported platform).
 *
 * [configDir] selects which account's credentials to read, or null for the
 * default `~/.claude`.
 *
 * Implementations must never log the value or copy it anywhere on disk, and
 * should read the file on each call rather than watch it: the read costs
 * nothing next to the HTTP request it precedes, and a watcher would add a
 * background thread and native resources to a process meant to run for days,
 * for a file that changes a few times a day.
 */
internal expect fun readClaudeCodeOAuthToken(configDir: String? = null): String?

/** Why the token is unavailable, phrased for whichever platform this is. */
internal expect val claudeCodeCredentialsUnavailableHint: String
