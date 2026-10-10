package com.nebi.obdbridge.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightsTest {
    private var ts = 1_000_000L
    private fun inp(rpm: Double? = 800.0, speed: Double? = 0.0, load: Double? = 47.0, map: Double? = 103.0, coolant: Double? = 85.0,
                    battery: Double? = 14.1, gear: Int? = null, gps: Double? = null, online: Boolean = true) =
        Inputs(ts, rpm, speed, load, map, coolant, battery, 36000.0, gear, gps, online)

    private fun run(ins: Insights, seconds: Int, f: () -> Inputs) = repeat(seconds * 5) { ins.update(f()); ts += 200 }

    @Test fun fuelCutAndCoasting() {
        val ins = Insights()
        run(ins, 1) { inp(rpm = 1800.0, speed = 60.0, load = 0.0, gear = 4) }
        assertTrue(ins.fuelCut); assertEquals(0.0, ins.fuelLph!!, 1e-9)
        run(ins, 1) { inp(rpm = 900.0, speed = 50.0, load = 45.0, gear = null) }
        assertFalse(ins.fuelCut); assertTrue(ins.coasting)
        run(ins, 1) { inp(rpm = 2000.0, speed = 60.0, load = 70.0, gear = 4) }
        assertFalse(ins.coasting); assertTrue(ins.fuelLph!! > 0)
    }

    @Test fun dpfRegenDetectedAgainstLearnedIdle() {
        val ins = Insights()
        run(ins, 120) { inp(rpm = 800.0 + (ts / 200 % 3) * 5, load = 47.0) }    // normal sıcak rölanti
        assertFalse(ins.health.dpfActive)
        assertEquals(805.0, ins.health.idleRpm, 10.0)
        run(ins, 30) { inp(rpm = 950.0, load = 62.0) }                          // temizlenme: devir ve yük yüksek
        assertTrue(ins.health.dpfActive)
        assertEquals(AlertKind.DPF, ins.alerts.first().kind)
        run(ins, 40) { inp(rpm = 800.0, load = 47.0) }
        assertFalse(ins.health.dpfActive)
        assertEquals(1, ins.health.dpfCount)
    }

    @Test fun chargeAndColdAlerts() {
        val ins = Insights()
        run(ins, 60) { inp(battery = 12.6) }
        assertTrue(ins.alerts.any { it.kind == AlertKind.CHARGE_LOW })
        val cold = Insights()
        run(cold, 2) { inp(coolant = 40.0, rpm = 2800.0, speed = 50.0, gear = 3, load = 60.0) }
        assertEquals(AlertKind.COLD, cold.alerts.first().kind)
        assertEquals(Level.WARN, cold.alerts.first().level)
        val off = Insights()
        run(off, 2) { inp(rpm = 0.0, battery = 11.9) }
        assertTrue(off.alerts.any { it.kind == AlertKind.BATTERY_WEAK })
    }

    @Test fun zeroToHundredTimed() {
        val ins = Insights()
        run(ins, 2) { inp(speed = 0.0) }
        assertEquals("hazır", ins.perf.state)
        var v = 0.0
        run(ins, 12) { v = (v + 2.0).coerceAtMost(110.0); inp(rpm = 3000.0, speed = kotlin.math.floor(v), load = 100.0, gear = 2) }  // 10 km/h/sn
        assertEquals(10.0, ins.perf.last0100!!, 0.5)
        assertEquals(4.0, ins.perf.last60100!!, 0.5)
        assertNotNull(ins.perf.best0100)
    }

    @Test fun speedoErrorLearned() {
        val ins = Insights()
        run(ins, 60) { inp(rpm = 2000.0, speed = 84.0, load = 60.0, gear = 5, gps = 80.0) }
        assertEquals(0.05, ins.health.speedoError!!, 0.002)
    }

    @Test fun fuelCalibrationScalesK() {
        val f = FuelModel()
        repeat(3600 * 5) { f.accumulate(ts, 5.0, 100.0); ts += 200 }   // 1 sa, 5 L/sa tahmin
        assertEquals(5.0, f.estLiters, 0.05)
        val k0 = f.k
        assertTrue(f.fillUp(6.0))
        assertEquals(k0 * 1.2, f.k, 0.01)
        assertEquals(0.0, f.estLiters, 1e-9)
    }

    @Test fun saveLoadRoundTrip() {
        val a = Insights()
        run(a, 120) { inp() }
        a.perf.best0100 = 11.2
        val b = Insights().apply { load(a.save()) }
        assertEquals(a.health.idleRpm, b.health.idleRpm, 1e-6)
        assertEquals(11.2, b.perf.best0100!!, 1e-9)
        assertNull(Insights().apply { load("bozuk") }.perf.best0100)
    }
}
