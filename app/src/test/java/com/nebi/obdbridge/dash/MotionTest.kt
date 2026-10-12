package com.nebi.obdbridge.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class MotionTest {
    @Test fun compassNames() {
        assertEquals("K", Heading.name(0.0)); assertEquals("K", Heading.name(359.0)); assertEquals("KD", Heading.name(40.0))
        assertEquals("D", Heading.name(90.0)); assertEquals("GB", Heading.name(225.0)); assertEquals("KB", Heading.name(315.0))
    }

    @Test fun headingIgnoredWhenSlow() {
        val h = Heading()
        h.onLocation(0, 3.0, 120.0); assertNull(h.degrees)
        h.onLocation(1, 50.0, 120.0); assertEquals(120.0, h.degrees!!, 1e-9)
        h.onLocation(2, 0.0, 10.0); assertEquals(120.0, h.degrees!!, 1e-9)   // dururken son yön kalır
    }

    /** Kuzeye düz giden yol, %5 yokuş: harita noktalarıyla eğim ~5, GPS gürültülü noktalarla ~5 ± 1,5 */
    @Test fun gradeFromDemAndGps() {
        var t = 0L
        val tr = AltitudeTrack { t }
        val rnd = java.util.Random(1)
        var lat = 39.0
        for (i in 0..120) {          // 10 m adım, 1,2 km
            t = i * 500L
            lat += 10 / 111_195.0
            val dist = i * 10.0
            tr.onLocation(t, lat, 32.0, 5f, 800 + dist * 0.05 + rnd.nextGaussian() * 6, 8f)
            if (i % 10 == 0) tr.onDem(tr.distance, 800 + tr.distance * 0.05)
        }
        assertEquals(AltitudeTrack.Src.DEM, tr.source)
        assertEquals(5.0, tr.grade!!, 0.3)
        assertEquals(800 + tr.distance * 0.05, tr.altitude!!, 3.0)

        // Yalnız GPS
        t = 0L
        val g = AltitudeTrack { t }
        lat = 39.0
        for (i in 0..120) {
            t = i * 500L; lat += 10 / 111_195.0
            g.onLocation(t, lat, 32.0, 5f, 800 + i * 10.0 * 0.05 + rnd.nextGaussian() * 6, 8f)
        }
        assertEquals(AltitudeTrack.Src.GPS, g.source)
        assertEquals(5.0, g.grade!!, 1.5)
    }

    @Test fun baroUsesCarMapOnlyWithEngineStopped() {
        var t = 0L
        val b = Baro { t }
        // Rölanti ve yakıt kesmede MAP dış basınçtan yüksek: kullanılmaz
        repeat(100) { t += 500; b.onCar(106.0, 790.0, 0.0) }
        repeat(100) { t += 500; b.onCar(110.0, 1500.0, 50.0) }
        assertNull(b.source)
        // Kontak açık, motor durmuş
        repeat(10) { t += 500; b.onCar(101.0, 0.0, 0.0) }
        assertEquals(Baro.Src.CAR, b.source)
        assertEquals(1010.0, b.hpa!!, 1.0)
        // Motor çalıştıktan sonra da saatlerce geçerli
        t += 2 * 3600_000L
        assertEquals(Baro.Src.CAR, b.source)
        // Hava servisi varsa o öncelikli; fark MAP sensörünün kayması
        b.onWeather(1009.0)
        assertEquals(Baro.Src.WEATHER, b.source)
        assertEquals(1.0, b.mapOffset!!, 1.0)
    }

    /**
     * Telefon tutucuda eğik (ekran sürücüye, 70° dik, 25° sola dönük). Araç düz yolda 0,3 g hızlanıp 0,4 g fren yapıyor,
     * sonra 0,35 g sağa viraj. G-ölçer ileri yönü kendisi bulmalı.
     */
    @Test fun gmeterLearnsMountOrientation() = gmeterScenario(gravitySensor = true, tol = 0.05)

    /** Yerçekimi sensörü olmayan telefon: alçak geçirgen yedek yol; uzun virajda biraz kayar */
    @Test fun gmeterFallbackWithoutGravitySensor() = gmeterScenario(gravitySensor = false, tol = 0.1)

    private fun gmeterScenario(gravitySensor: Boolean, tol: Double) {
        // Araç ekseni: x ileri, y sağ, z aşağı. Telefon ekseni = R * araç
        val pitch = Math.toRadians(70.0); val yaw = Math.toRadians(25.0)
        fun toPhone(v: DoubleArray): FloatArray {
            // önce yaw (z), sonra pitch (y)
            val x1 = v[0] * cos(yaw) - v[1] * sin(yaw); val y1 = v[0] * sin(yaw) + v[1] * cos(yaw); val z1 = v[2]
            val x2 = x1 * cos(pitch) + z1 * sin(pitch); val z2 = -x1 * sin(pitch) + z1 * cos(pitch)
            return floatArrayOf(x2.toFloat(), y1.toFloat(), z2.toFloat())
        }
        val g = GMeter()
        val rnd = java.util.Random(3)
        var ts = 0L; var v = 60.0   // yolda seyir (fren hızı sıfırın altına indirmesin)
        var mLong = 0.0; var mLat = 0.0   // son 1 sn ortalaması (ekran da yumuşatarak gösterir)
        fun drive(seconds: Double, longG: Double, latG: Double) {
            val n = (seconds * 50).toInt()
            mLong = 0.0; mLat = 0.0
            repeat(n) { i ->
                ts += 20
                v = (v + longG * GMeter.G * 0.02 * 3.6).coerceAtLeast(0.0)
                if (i % 10 == 0) g.onSpeed(ts, v)
                // Telefon ivmeölçeri: özgül kuvvet = ivme − yerçekimi; araçta yerçekimi +z (aşağı), ölçülen −g z... Android: dururken +9,81 yukarı
                val car = doubleArrayOf(longG * GMeter.G + rnd.nextGaussian() * 0.8, latG * GMeter.G + rnd.nextGaussian() * 0.8, -GMeter.G)
                g.onAccel(ts, toPhone(car), if (gravitySensor) toPhone(doubleArrayOf(0.0, 0.0, -GMeter.G)) else null)
                if (i >= n - 50) { mLong += g.long / 50; mLat += g.lat / 50 }
            }
        }
        drive(3.0, 0.0, 0.0)
        repeat(4) { drive(4.0, 0.3, 0.0); drive(3.0, -0.4, 0.0); drive(1.0, 0.0, 0.0) }
        assertEquals(GMeter.State.READY, g.state)
        val f = g.forward(); val e = toPhone(doubleArrayOf(1.0, 0.0, 0.0))
        assertTrue("ileri yön hatası", f[0] * e[0] + f[1] * e[1] + f[2] * e[2] > cos(Math.toRadians(5.0)))
        drive(2.0, 0.3, 0.0); assertEquals(0.3, mLong, tol); assertEquals(0.0, mLat, tol)
        drive(2.0, -0.4, 0.0); assertEquals(-0.4, mLong, tol)
        drive(3.0, 0.0, 0.35); assertEquals(0.35, mLat, tol); assertEquals(0.0, mLong, tol)
        assertTrue(g.maxBrake > 0.35)
    }

    @Test fun gmeterRejectsBrokenSensor() {
        val g = GMeter()
        repeat(100) { g.onAccel(it * 20L, floatArrayOf(0f, 0f, 4f), null) }
        assertEquals(GMeter.State.UNRELIABLE, g.state)
    }

    @Test fun gpsSlowsDownWhenStill() {
        var t = 0L
        val p = GpsPacer { t }
        val still = floatArrayOf(0f, 0f, 9.81f)
        assertEquals(GpsPacer.FAST, p.intervalMs(false, false, 0.0, true))
        repeat(70) { t += 1000; p.onAccel(still) }
        assertEquals(GpsPacer.SLOW, p.intervalMs(false, false, 0.0, true))
        assertEquals(GpsPacer.FAST, p.intervalMs(true, false, 0.0, true))          // OBD bağlı
        t += 1000
        assertEquals(GpsPacer.FAST, p.intervalMs(false, false, 0.0, true))         // az önce bağlıydı: 60 sn hızlı kalır
        repeat(70) { t += 1000; p.onAccel(still) }
        assertEquals(GpsPacer.SLOW, p.intervalMs(false, false, 0.0, true))
        p.onAccel(floatArrayOf(4f, 0f, 9.81f))                                       // telefon alındı (~0,8 m/s² sarsıntı)
        assertEquals(GpsPacer.FAST, p.intervalMs(false, false, 0.0, true))
    }
}
