package com.angussoftware.fueldashboard.server

import com.angussoftware.fueldashboard.model.MultiProviderSettings
import com.angussoftware.fueldashboard.model.PairingClient
import com.angussoftware.fueldashboard.model.PairingFetchResult
import com.angussoftware.fueldashboard.model.PairingLink
import com.angussoftware.fueldashboard.model.ProviderConfig
import com.angussoftware.fueldashboard.model.ProviderKind
import com.angussoftware.fueldashboard.model.SettingsSyncData
import com.angussoftware.fueldashboard.settings.FuelSettingsKeys
import com.angussoftware.fueldashboard.settings.FuelSettingsStore
import com.angussoftware.fueldashboard.settings.loadStringSetting
import com.angussoftware.fueldashboard.settings.saveStringSetting
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Pairing bridge endpoints — one-time tokens, single-use semantics, TTL,
 * rate-limit eviction, and byte-parity of the redemption payload with the
 * sync_code path (spec: docs/spec-agent-pairing-bridge.md; fleet SSOT:
 * agent-app-bridge-pattern.md).
 */
class EmbeddedServerPairingTest {

    private val provider = ProviderConfig(
        id = "zai-1",
        kind = ProviderKind.ZAI,
        apiKey = "literal-key",
        activateCommand = "/bin/echo swapped",
        swapAwayBelowPct = 10,
    )

    private lateinit var savedTunnelUrl: String

    @BeforeTest
    fun snapshotSettings() {
        savedTunnelUrl = loadStringSetting(FuelSettingsKeys.TUNNEL_URL, "")
    }

    @AfterTest
    fun restoreSettings() {
        saveStringSetting(FuelSettingsKeys.TUNNEL_URL, savedTunnelUrl)
        FuelSettingsStore.saveMultiProvider(MultiProviderSettings())
    }

    private fun createServer(ttlMs: Long = EmbeddedServer.PAIR_TOKEN_TTL_MS): EmbeddedServer =
        EmbeddedServer(
            apiKey = "test-api-key",
            enableMcp = false,
            pairTokenTtlMs = ttlMs,
        )

    /** Mint response parsed from POST /pair. */
    private class Minted(val token: String, val url: String, val expiresAt: Long)

    /** Mints a token through the already-configured test app. */
    private suspend fun ApplicationTestBuilder.mintToken(apiKey: String = "test-api-key"): Minted {
        val body = client.post("/pair") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
        }.bodyAsText()
        val obj = Json.parseToJsonElement(body).jsonObject
        return Minted(
            token = obj["token"]!!.jsonPrimitive.content,
            url = obj["url"]!!.jsonPrimitive.content,
            expiresAt = obj["expiresAt"]!!.jsonPrimitive.long,
        )
    }

    /** Configures routing and mints — for tests that mint exactly once. */
    private suspend fun ApplicationTestBuilder.mint(server: EmbeddedServer): Minted {
        application { server.configureRouting(this) }
        return mintToken()
    }

    // ── Minting (POST /pair) ───────────────────────────────────────────

    @Test
    fun mintRequiresServerKey() = testApplication {
        val server = createServer()
        application { server.configureRouting(this) }

        client.post("/pair").apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun mintReturnsTokenUrlAndExpiry() = testApplication {
        val minted = mint(createServer())

        // Token is a UUID (36 chars with dashes).
        assertEquals(36, minted.token.length, "expected UUID token, got '${minted.token}'")
        assertTrue(minted.url.isNotEmpty())
        val now = System.currentTimeMillis()
        assertTrue(minted.expiresAt in now..(now + 65_000), "expiry should be ~60s out")
    }

    // ── Redemption semantics (GET /pair/{token}) ───────────────────────

    @Test
    fun redemptionNeedsNoServerKey() = testApplication {
        val minted = mint(createServer())

        // No auth header at all — the one-time token is the credential.
        client.get("/pair/${minted.token}").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun redemptionIsSingleUse() = testApplication {
        val minted = mint(createServer())

        client.get("/pair/${minted.token}").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
        // Second attempt hits the redeemed tombstone → 410, not 404.
        client.get("/pair/${minted.token}").apply {
            assertEquals(HttpStatusCode.Gone, status)
        }
    }

    @Test
    fun expiredTokenRedeemsGone() = testApplication {
        val server = createServer(ttlMs = 150)
        val minted = mint(server)

        delay(300)

        client.get("/pair/${minted.token}").apply {
            assertEquals(HttpStatusCode.Gone, status)
        }
    }

    @Test
    fun unknownTokenIsNotFound() = testApplication {
        val server = createServer()
        application { server.configureRouting(this) }

        client.get("/pair/never-minted").apply {
            assertEquals(HttpStatusCode.NotFound, status)
        }
    }

    @Test
    fun sixthMintEvictsOldest() = testApplication {
        val server = createServer()
        application { server.configureRouting(this) }

        val minted = mutableListOf<Minted>()
        repeat(6) { minted.add(mintToken()) }

        // The 1st (oldest) was evicted by the 6th mint → unknown → 404.
        client.get("/pair/${minted[0].token}").apply {
            assertEquals(HttpStatusCode.NotFound, status)
        }
        // The 6th is still redeemable.
        client.get("/pair/${minted[5].token}").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    // ── Payload parity (spec: redemption = same bytes as sync code) ────

    @Test
    fun redemptionPayloadMatchesSyncCodeBytes() = testApplication {
        val server = createServer()
        application { server.configureRouting(this) }
        FuelSettingsStore.saveMultiProvider(MultiProviderSettings(providers = listOf(provider)))

        val minted = mintToken()

        val pairBody = client.get("/pair/${minted.token}").bodyAsText()

        val syncBody = client.get("/sync") {
            header(HttpHeaders.Authorization, "Bearer test-api-key")
        }.bodyAsText()
        val syncCode = Json.parseToJsonElement(syncBody).jsonObject["sync_code"]!!.jsonPrimitive.content
        val syncInterior = String(java.util.Base64.getDecoder().decode(syncCode))

        // Byte-identical: the redemption payload IS the sync-code interior.
        assertEquals(syncInterior, pairBody)

        // And it round-trips through the model with the server key intact —
        // the pairing bridge's whole point is delivering the key without it
        // riding a transcript.
        val syncData = SettingsSyncData.fromJson(pairBody)
        assertEquals("test-api-key", syncData?.serverApiKey)
        assertEquals(listOf("zai-1"), syncData?.providers?.map { it.id })
    }

    // ── Mint URL priority (tunnel → origin → LAN, spec) ────────────────

    @Test
    fun mintUrlPrefersConfiguredTunnel() = testApplication {
        saveStringSetting(FuelSettingsKeys.TUNNEL_URL, "https://fuel.example.com")

        val minted = mint(createServer())

        assertEquals("https://fuel.example.com", minted.url)
    }

    @Test
    fun mintUrlFallsBackToLanWhenNoTunnelAndLoopbackOrigin() = testApplication {
        saveStringSetting(FuelSettingsKeys.TUNNEL_URL, "")

        val minted = mint(createServer())

        // The test client reaches us as localhost (loopback origin — useless
        // to a phone), so the URL must come from the LAN-IP helper instead.
        assertTrue(minted.url.startsWith("http"), "expected http URL, got '${minted.url}'")
        assertTrue(!minted.url.contains("localhost"), "loopback origin must not leak into the URL")
    }

    // ── PairingClient end-to-end (real socket) ─────────────────────────

    @Test
    fun pairingClientRoundtripAppliesSettings() {
        val server = createServer()
        // Real socket (port=0 → ephemeral): the redemption goes through the
        // actual HTTP stack, not the in-memory test engine — that is the
        // E2E proof the spec asks for. EmbeddedServer is a wrapper; the
        // bound connectors live on its engine.
        val embedded = embeddedServer(CIO, port = 0) {
            server.configureRouting(this)
        }.start(wait = false)
        try {
            val port = runBlocking { embedded.engine.resolvedConnectors().first().port }
            FuelSettingsStore.saveMultiProvider(MultiProviderSettings(providers = listOf(provider)))

            // Mint against the real socket — token stores are per-instance,
            // so it must be the same EmbeddedServer the redemption hits.
            val mintBody = runBlocking {
                HttpClient(io.ktor.client.engine.cio.CIO) {
                    install(HttpTimeout) { requestTimeoutMillis = 5_000 }
                }.use { client ->
                    client.post("http://localhost:$port/pair") {
                        header(HttpHeaders.Authorization, "Bearer test-api-key")
                    }.bodyAsText()
                }
            }
            val token = Json.parseToJsonElement(mintBody).jsonObject["token"]!!.jsonPrimitive.content

            val link = PairingLink(token = token, url = "http://localhost:$port")
            var applied: SettingsSyncData? = null
            val first = runBlocking {
                PairingClient.fetchAndApply(link) { syncData ->
                    applied = syncData
                }
            }
            assertIs<PairingFetchResult.Success>(first)
            assertEquals(listOf("zai-1"), applied?.providers?.map { it.id })
            assertEquals("test-api-key", applied?.serverApiKey)

            // One-time semantics through the client too: same link is dead.
            val second = runBlocking { PairingClient.fetchAndApply(link) { } }
            assertIs<PairingFetchResult.Expired>(second)
        } finally {
            embedded.stop(500, 500)
        }
    }

    @Test
    fun pairingClientMapsUnreachableServerToNetworkError() {
        val socket = java.net.ServerSocket(0)
        val port = socket.localPort
        socket.close() // now refused

        val link = PairingLink(token = "x", url = "http://localhost:$port")
        val result = runBlocking { PairingClient.fetchAndApply(link) { } }

        assertIs<PairingFetchResult.NetworkError>(result)
    }
}
