# Fuel Dashboard: agent pairing bridge (settings sync without QR)

Spec author: Coda (Agent Conductor) · Oct 7 2026 · For: fuel-dashboard-kmp (Angus)

## Context

Fleet SSOT for agent↔app bridges: fleet-shared/knowledge/agent-app-bridge-pattern.md — read first. This feature is the pattern's first cross-device implementation. Follow the pattern; do not invent a second pairing scheme.

Today, mobile gets settings via QR scan (human-in-loop) or text-code paste (leaks keys into transcripts). Agents must be able to sync a phone without either.

## User story

As the fleet orchestrator, I trigger one command; the Android Agents Fuel app pulls current settings from the desktop dashboard server; the user is notified and no secret transits any agent transcript.

## Design

### 1. Desktop (Ktor embedded server, EmbeddedServer.kt)

`POST /pair` — auth: existing Bearer serverApiKey (same as MCP).
Response 200: `{ "token": "<uuid>", "url": "<this server's reachable URL>", "expiresAt": "<epoch-ms>" }`

- Token: UUID, single-use, TTL 60s, in-memory store (ConcurrentHashMap; no persistence needed).
- Single-use enforcement: remove on first successful redemption; later uses → 410 Gone.
- Reachable URL: prefer existing tunnelUrl setting if set, else request origin, else LAN IP (mirror existing QR-sync URL logic — reuse whatever helper the QR path uses for URL selection).
- Rate limit: max 5 outstanding tokens (oldest evicted).

`GET /pair/{token}` — auth: the one-time token itself (NOT the server key).
Response 200: full SettingsSyncData JSON (same bytes as get_sync_data MCP output — reuse the same serializer path).
Response 410 if expired/used, 404 if unknown.

Tests (JVM, EmbeddedServerEndpointsTest pattern):
- mint → redeem → second redeem = 410
- mint → wait TTL → redeem = 410
- no server key on POST /pair = 401
- token auth on GET /pair/{t} returns payload identical (field-by-field) to get_sync_data output for same settings
- 6th concurrent mint evicts oldest

### 2. Android (receiver)

Manifest: exported activity-alias or intent-filter on MainActivity for scheme `fueldashboard`, host `import` → `fueldashboard://import?token=X&url=...&autostart=<0|1>`

On intent:
1. Parse token+url. If either missing → toast "Invalid pairing link" and stop.
2. POST nothing; GET `{url}/pair/{token}` (OkHttp/ktor client already in deps) with 10s timeout, on Dispatchers.IO.
3. On 200: apply via the EXISTING import-apply path (same function the QR/text-code import uses — settings scope full). Toast "Settings synced from dashboard". If app was launched fresh by this intent (no prior UI), show brief confirmation then finish to home state as normal.
4. On 410/404/network fail: toast "Pairing link expired — try again", finish. NO retry loops.
5. `autostart=1` param: after successful import, trigger the app's normal data refresh once.

Security properties (must hold):
- The long-lived serverApiKey NEVER appears in the intent, broadcast, or logs.
- Token is single-use (server-enforced) and short-TTL.
- Receiver does transport-layer validation only via HTTPS or explicit user consent on plain HTTP LAN (if url is http:// non-loopback, show a confirm dialog "Sync over local network?" before fetching — one tap, only in the non-HTTPS case).

Tests (androidTest or commonTest where possible):
- intent parse: missing token → no fetch, toast path
- mock server 200 → settings applied (verify via settings store read-back)
- mock server 410 → no settings change, expired toast
- http:// non-loopback URL → consent dialog path
- roundtrip: desktop JVM test mints token, android test (or JVM shared test with the client logic extracted common) redeems — prefer extracting the fetch+apply client logic into commonMain so the roundtrip is a JVM test.

### 3. Extraction for reuse (per fleet SSOT shared-kernel rule)

Put the client fetch+apply logic in commonMain (e.g. `PairingClient.kt` next to SettingsSync.kt) so desktop tests can exercise it and future apps can port it. Do NOT create the shared com.angussoftware.bridge module yet — this is the first implementation; extraction happens when the second app needs it (avoid premature abstraction). Note the file path in the PR description for the future port.

### 4. Docs

- docs/agentctl-or-bridge docs not needed in this repo; add a short section to docs/self-hosting.md: "Headless pairing" with the two curl/am-start examples.

## Non-goals

- Standing background re-sync (future PR).
- Beamdown-side integration (separate).
- iOS.

## Acceptance

- CI green (existing suites + new tests).
- E2E proof: desktop test server + JVM client test redeem roundtrip documented in PR.
- No new dependencies.
