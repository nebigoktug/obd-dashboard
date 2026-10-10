package com.nebi.obdbridge.dash

import com.nebi.obdbridge.obd.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_760_000_000_000L   // 2025-10 civarı

    private fun latest(rpm: Double, speed: Double) = mapOf(
        "rpm" to Sample("rpm", rpm, now), "speed" to Sample("speed", speed, now), "load" to Sample("load", 60.0, now),
        "coolant" to Sample("coolant", 85.0, now), "battery" to Sample("battery", 14.1, now),
    )

    /** [min] dakika 60 km/h sürüş (2 Hz), sonra kesinti. */
    private fun drive(log: DriveLog, min: Int, speed: Double = 60.0) =
        repeat(min * 60 * 4) { log.tick(latest(2000.0, speed), live = true); now += 250 }

    @Test fun logContinuesAfterShortGapAndSplitsAfterLongOne() {
        val dir = tmp.newFolder("logs")
        val log = DriveLog(dir) { now }
        drive(log, 5)
        val first = log.file!!
        now += 3 * 60_000                         // 3 dk kopma: aynı yolculuk
        log.tick(emptyMap(), live = false)
        drive(log, 5)
        assertEquals(first, log.file)
        now += 15 * 60_000                        // 15 dk ara: yeni yolculuk
        log.tick(emptyMap(), live = false)
        drive(log, 2)
        log.close()
        val files = dir.listFiles()!!.filter { it.name.startsWith("drive_") }
        assertEquals(2, files.size)
        val rows = first.readLines()
        assertEquals("ts," + DriveLog.KEYS.joinToString(","), rows[0])
        assertTrue("2 Hz: ${rows.size}", kotlin.math.abs(10 * 60 * 2 - (rows.size - 1)) <= 5)
        assertTrue(rows[1].contains(",2000,60,60,,,85,,14.1,,"))
    }

    @Test fun summaryAndIndexCache() {
        val dir = tmp.newFolder("logs")
        val log = DriveLog(dir) { now }
        drive(log, 30)
        log.close()
        val gears = GearEstimator.fromRatios(listOf(118.0, 63.0, 42.0, 32.0, 25.5))
        val trips = HistoryIndex(dir).load(gears, FuelModel.DEFAULT_K)
        assertEquals(1, trips.size)
        val t = trips[0]
        assertEquals(30.0, t.km, 0.3)
        assertEquals(60.0, t.avgKmh, 1.0)
        assertNotNull(t.l100)
        // 2000/60 = 33,3 rpm/(km/h) → 4. vites
        assertEquals(100, t.gearPct.getOrNull(3))
        // Önbellek: ikinci yükleme aynı sonucu verir, dosya silinse de summaries.json var
        assertEquals(t, HistoryIndex(dir).load(gears, FuelModel.DEFAULT_K)[0])
        // Kısa (< 1 dk) kayıt özet vermez
        assertNull(TripSummary.of("x", 0, sequenceOf("ts,rpm,speed", "$now,800,0"), gears, 12.5))
    }

    @Test fun weekTotals() {
        val mk = { start: Long, km: Double -> TripSummary("n$start", 0, start, 600_000, km, 50.0, 90.0, km * 0.06, 0, 0, 0.0, 0, 0, 80, List(5) { 0 }) }
        val day = 86_400_000L
        // now: bu hafta; now−7 gün: her zaman geçen hafta; now−30 gün: hiçbiri
        val (a, b) = HistoryIndex.weeks(listOf(mk(now, 20.0), mk(now - 7 * day, 10.0), mk(now - 30 * day, 99.0)), now)
        assertEquals(1, a.trips); assertEquals(20.0, a.km, 1e-9)
        assertEquals(1, b.trips); assertEquals(10.0, b.km, 1e-9)
        assertEquals(6.0, a.l100!!, 1e-9)
    }
}
