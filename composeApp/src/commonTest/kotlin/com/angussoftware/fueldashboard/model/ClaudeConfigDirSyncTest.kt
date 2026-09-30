package com.angussoftware.fueldashboard.model

import com.angussoftware.fueldashboard.settings.ThemeController
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The per-account config directory across settings storage and sync.
 *
 * Storage must tolerate settings written before the field existed. Sync must
 * never carry it: it is a local path — naming the sender's home directory —
 * and means nothing on another machine. The receiver strips it too (see
 * ImportStripsUntrustedFieldsTest); this is the sending side.
 */
class ClaudeConfigDirSyncTest {

    private val work = ProviderConfig(
        id = "cc-work",
        kind = ProviderKind.CLAUDE_CODE,
        displayName = "Work plan",
        claudeConfigDir = "/home/alice/.claude-accounts/work",
    )

    private fun payload() = SettingsSyncData.from(
        settings = MultiProviderSettings(providers = listOf(work)),
        agentSettings = AgentSettings(),
        themeController = ThemeController,
    )

    @Test
    fun theExportedPayloadCarriesNoConfigDir() {
        val exported = payload().providers.single()
        assertEquals("", exported.claudeConfigDir)
        // One field stripped, not the provider.
        assertEquals("cc-work", exported.id)
        assertEquals("Work plan", exported.displayName)
        assertEquals(ProviderKind.CLAUDE_CODE, exported.kind)
    }

    @Test
    fun thePathAppearsNowhereInTheSerializedPayloadOrSyncCode() {
        val payload = payload()
        assertTrue("claude-accounts" !in payload.toJson(), "path leaked into the payload JSON")
        val decoded = SettingsSyncData.fromCode(payload.toCode())!!
        assertEquals("", decoded.providers.single().claudeConfigDir)
    }

    @Test
    fun settingsSavedBeforeTheFieldExistedStillLoad() {
        // A provider list as stored by a version without claudeConfigDir.
        val legacy = """{"providers":[{"id":"cc-1","kind":"CLAUDE_CODE","displayName":"Plan"}]}"""
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val loaded = json.decodeFromString(MultiProviderSettings.serializer(), legacy)
        assertEquals("", loaded.providers.single().claudeConfigDir, "absent must mean the default account")
        assertTrue(loaded.providers.single().isConfigured)
    }

    @Test
    fun aNewerPayloadWithTheFieldIsReadableByTheCurrentDecoder() {
        // Forward direction: a payload that does carry the field (an older
        // sender without the export strip) must still decode, not fail the
        // whole import. The receiver's own strip then removes it.
        val raw = """{"providers":[{"id":"cc-1","kind":"CLAUDE_CODE","claudeConfigDir":"/x"}]}"""
        val json = Json { ignoreUnknownKeys = true }
        assertEquals("/x", json.decodeFromString(MultiProviderSettings.serializer(), raw).providers.single().claudeConfigDir)
    }
}
