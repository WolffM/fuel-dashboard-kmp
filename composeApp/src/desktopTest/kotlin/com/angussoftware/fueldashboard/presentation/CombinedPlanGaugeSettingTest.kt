package com.angussoftware.fueldashboard.presentation

import com.angussoftware.fueldashboard.model.AgentSettings
import com.angussoftware.fueldashboard.model.MultiProviderSettings
import com.angussoftware.fueldashboard.model.SettingsSyncData
import com.angussoftware.fueldashboard.settings.FuelSettingsKeys
import com.angussoftware.fueldashboard.settings.ThemeController
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The combined plan gauge is the default, and turning it off has to stick:
 * across restarts, and across a settings sync to another device. Runs against
 * the test JVM's sandboxed user.home, never the real preferences.
 */
class CombinedPlanGaugeSettingTest {

    private val prefs = Preferences.userRoot().node("fuel-dashboard")
    private val viewModels = mutableListOf<FuelViewModel>()

    private fun newViewModel() = FuelViewModel().also { viewModels += it }

    @BeforeTest
    fun clear() {
        prefs.remove(FuelSettingsKeys.COMBINED_PLAN_GAUGE)
        prefs.flush()
    }

    @AfterTest
    fun tearDown() {
        viewModels.forEach { it.close() }
        clear()
    }

    @Test
    fun onByDefault() {
        assertTrue(newViewModel().state.value.combinedPlanGauge)
    }

    @Test
    fun turningItOffSurvivesARestart() {
        newViewModel().setCombinedPlanGauge(false)

        // Written through to the backing store, not just held in memory.
        prefs.sync()
        assertEquals("false", prefs.get(FuelSettingsKeys.COMBINED_PLAN_GAUGE, null))
        assertFalse(newViewModel().state.value.combinedPlanGauge)
    }

    @Test
    fun turningItBackOnSurvivesARestart() {
        newViewModel().setCombinedPlanGauge(false)
        newViewModel().setCombinedPlanGauge(true)

        assertTrue(newViewModel().state.value.combinedPlanGauge)
    }

    private fun exported() = SettingsSyncData.from(
        settings = MultiProviderSettings(),
        agentSettings = AgentSettings(),
        themeController = ThemeController,
    )

    @Test
    fun anExplicitChoiceTravelsWithSettingsSync() {
        newViewModel().setCombinedPlanGauge(false)

        val payload = exported()
        assertEquals(false, payload.combinedPlanGauge)
        assertEquals(false, SettingsSyncData.fromJson(payload.toJson())?.combinedPlanGauge)
        assertEquals(false, SettingsSyncData.fromQrData(payload.forSettingsQr().toQrData())?.combinedPlanGauge)
    }

    @Test
    fun noChoiceMeansTheReceiverKeepsItsOwn() {
        // Never set here, so nothing is sent and the other device's choice
        // is left alone.
        assertNull(exported().combinedPlanGauge)
    }

    @Test
    fun anAgentsQrNeverCarriesIt() {
        newViewModel().setCombinedPlanGauge(false)
        assertNull(exported().forAgentsQr().combinedPlanGauge)
    }
}
