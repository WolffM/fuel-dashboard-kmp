package com.angussoftware.fueldashboard.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.angussoftware.fueldashboard.util.epochMillis
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProviderBurnRateTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repo: FuelSnapshotRepository
    private lateinit var queries: FuelDatabaseQueries

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        FuelDatabase.Schema.create(driver)
        repo = FuelSnapshotRepository(driver)
        queries = FuelDatabase(driver).fuelDatabaseQueries
    }

    @AfterTest
    fun tearDown() = driver.close()

    private fun snapshot(minutesAgo: Long, pct: Double, windowHours: Double) {
        queries.insertProviderFuelSnapshot(
            timestamp = epochMillis() - minutesAgo * 60_000,
            provider_id = "claude",
            provider_name = "Claude",
            provider_type = "WINDOW_CREDIT",
            remaining_pct = pct,
            reset_at = null,
            window_hours = windowHours,
        )
    }

    @Test
    fun burnRateIgnoresSnapshotsFromAnotherWindow() {
        // The headline was the 5-hour window, then the weekly one became the
        // limit. 97% -> 2% across that seam is not a 95-point burn.
        snapshot(minutesAgo = 50, pct = 97.0, windowHours = 5.0)
        snapshot(minutesAgo = 40, pct = 97.0, windowHours = 5.0)
        snapshot(minutesAgo = 30, pct = 4.0, windowHours = 168.0)
        snapshot(minutesAgo = 15, pct = 3.0, windowHours = 168.0)
        snapshot(minutesAgo = 0, pct = 2.0, windowHours = 168.0)

        val rate = repo.getAllProviderBurnRates().single()

        assertEquals(2.0, rate.currentPct)
        assertEquals(listOf(4.0, 3.0, 2.0), rate.history)
        val burn = assertNotNull(rate.burnRatePerHr)
        assertEquals(4.0, burn, 0.01, "2 points over 30 minutes")
    }

    @Test
    fun tooFewSnapshotsInTheCurrentWindowGiveNoRate() {
        snapshot(minutesAgo = 50, pct = 97.0, windowHours = 5.0)
        snapshot(minutesAgo = 40, pct = 96.0, windowHours = 5.0)
        snapshot(minutesAgo = 30, pct = 95.0, windowHours = 5.0)
        snapshot(minutesAgo = 0, pct = 2.0, windowHours = 168.0)

        assertNull(repo.getAllProviderBurnRates().single().burnRatePerHr)
    }
}
