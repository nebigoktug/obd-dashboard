package com.nebi.obdbridge.dash

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnitsTest {
    @After fun metric() {
        Units.apply(Units.defaultFor("TR"))
        SignalCatalog.configure(Vehicle())
    }

    @Test fun regionDefaults() {
        assertTrue(Units.defaultFor("US").fahrenheit)
        assertEquals(Units.Consumption.MPG_US, Units.defaultFor("us").consumption)
        assertTrue(Units.defaultFor("LR").miles)
        assertTrue(Units.defaultFor("MM").psi)
        val gb = Units.defaultFor("GB")
        assertTrue(gb.miles); assertFalse(gb.fahrenheit); assertEquals(Units.Consumption.MPG_UK, gb.consumption)
        val tr = Units.defaultFor("TR")
        assertFalse(tr.miles); assertFalse(tr.fahrenheit); assertEquals(Units.Consumption.L100, tr.consumption); assertFalse(tr.psi)
    }

    @Test fun metricIsIdentity() {
        Units.apply(Units.defaultFor("DE"))
        assertEquals(100.0, Units.speed(100.0), 1e-9)
        assertEquals(90.0, Units.temp(90.0), 1e-9)
        assertEquals(6.5, Units.fuel(6.5), 1e-9)
        assertEquals(101.3, Units.kpa(101.3), 1e-9)
        assertEquals(1.2, Units.bar(1.2), 1e-9)
    }

    @Test fun imperialConversions() {
        Units.apply(Units.defaultFor("US"))
        assertEquals(62.137, Units.speed(100.0), 0.001)
        assertEquals(1.0, Units.dist(1.609344), 1e-9)
        assertEquals(194.0, Units.temp(90.0), 1e-9)        // 90 °C = 194 °F
        assertEquals(32.0, Units.temp(0.0), 1e-9)
        assertEquals(-40.0, Units.temp(-40.0), 1e-9)
        assertEquals(23.52, Units.fuel(10.0), 0.01)        // 10 L/100 km ≈ 23,5 mpg (US)
        assertEquals(Units.MAX_ECONOMY, Units.fuel(0.0), 1e-9)   // yakıt kesik: sonsuz değil
        assertEquals(14.69, Units.kpa(101.3), 0.01)
        assertEquals(14.50, Units.bar(1.0), 0.01)
        Units.consumption = Units.Consumption.MPG_UK
        assertEquals(28.25, Units.fuel(10.0), 0.01)
        Units.consumption = Units.Consumption.KML
        assertEquals(20.0, Units.fuel(5.0), 1e-9)
    }

    @Test fun catalogFollowsUnits() {
        Units.apply(Units.defaultFor("US"))
        SignalCatalog.configure(Vehicle())
        val coolant = SignalCatalog["coolant"]!!
        assertEquals("°F", coolant.unit)
        assertEquals(194.0, coolant.toDisplay(90.0), 1e-9)
        assertEquals(Level.NORMAL, coolant.level(coolant.toDisplay(90.0)))
        assertEquals(Level.CRIT, coolant.level(coolant.toDisplay(113.0)))
        assertEquals(Level.COLD, coolant.level(coolant.toDisplay(50.0)))
        val speed = SignalCatalog["speed"]!!
        assertEquals("mph", speed.unit)
        assertEquals(120.0, speed.max, 1e-9)                // 200 km/h kadran → 120 mph
        assertEquals(31.07, speed.toDisplay(50.0), 0.01)
        assertEquals("mpg", SignalCatalog["fuel_l100"]!!.unit)
        assertEquals(14.5, SignalCatalog["rail"]!!.toDisplay(100.0), 0.01)   // ham 100 × 0,01 = 1 bar
        assertEquals("rpm", SignalCatalog["rpm"]!!.unit)
    }

    @Test fun dialMaxIsMultipleOf20InMph() {
        Units.miles = true
        for (k in Units.speedDialOptions()) assertEquals(0, Units.speedDialMax(k) % 20)
        assertEquals(listOf(120, 140, 160), Units.speedDialOptions().map(Units::speedDialMax))
        Units.miles = false
        assertEquals(220, Units.speedDialMax(220))
    }

    @Test fun navDistanceRoundTrips() {
        Units.miles = true
        assertEquals("100 ft", Units.navDist(30.0))
        assertEquals("1.2 mi", Units.navDist(1931.0).replace(',', '.'))
        assertEquals("12 mi", Units.navDist(19_312.0))
        assertEquals(152.4, Units.parseDistance("500 ft")!!, 0.01)
        assertEquals(643.7, Units.parseDistance("0.4 mi")!!, 0.1)
        for (m in listOf(60.0, 140.0, 900.0, 5000.0)) {
            val back = Units.parseDistance(Units.navDist(m))!!
            assertEquals(m, back, if (m < 160) 16.0 else 81.0)   // 50 ft ya da 0,1 mi yuvarlama
        }
        Units.miles = false
        assertEquals("80 m", Units.navDist(83.0))
    }
}
