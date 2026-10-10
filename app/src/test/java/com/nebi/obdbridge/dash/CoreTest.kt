package com.nebi.obdbridge.dash

import com.nebi.obdbridge.obd.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CoreTest {

    /**
     * Gerçekçi manuel sürüş: tam sayı hız (OBD), debriyajlı vites geçişleri, kalkışta debriyaj kaydırma,
     * boşta süzülme ve gaz verme, hız ölçümünün devirden biraz geride kalması. Oranlar [PRIOR]'dan farklı.
     */
    private class ManualDrive(val ratios: DoubleArray, val maxGear: Int, seed: Long) {
        val rnd = java.util.Random(seed)
        var t = 0.0; var v = 0.0; var gear = 0; var rpm = 820.0
        var state = "stop"; var stateT = 0.0; var target = 0.0
        var clutch = false   // true: debriyaj basılı ya da boşta (gerçek vites yok)
        private var vPrev = 0.0

        fun step(dt: Double) {
            t += dt; stateT += dt; vPrev = v
            when (state) {
                "stop" -> { v = 0.0; rpm = 820 + rnd.nextGaussian() * 10; clutch = true
                    if (stateT > 5) { state = "launch"; stateT = 0.0; gear = 1; target = 30 + rnd.nextDouble() * (if (maxGear == 5) 80 else 45) } }
                "launch" -> { // debriyaj kaydırma: devir oranla uyuşmaz
                    v += 3.5 * dt; rpm = 1300 + 200 * rnd.nextDouble(); clutch = true
                    if (v >= 9) { state = "drive"; stateT = 0.0 } }
                "drive" -> { clutch = false
                    val acc = if (v < target) 2.0 else if (v > target + 3) -1.5 else 0.0
                    v = (v + acc * dt + rnd.nextGaussian() * 0.05).coerceAtLeast(0.0)
                    rpm = v * ratios[gear - 1]
                    when {
                        rpm > 2400 && gear < maxGear -> { state = "shift"; stateT = 0.0; gear++ }
                        rpm < 1150 && gear > 1 -> { state = "shift"; stateT = 0.0; gear-- }
                        stateT > 25 && rnd.nextDouble() < 0.02 -> { state = "coast"; stateT = 0.0 }
                        stateT > 15 && rnd.nextDouble() < 0.01 -> { state = "brake"; stateT = 0.0 }
                    }
                    if (stateT > 8 && rnd.nextDouble() < 0.02) target = 20 + rnd.nextDouble() * (if (maxGear == 5) 100 else 55) }
                "shift" -> { clutch = true   // ~0,8 sn: devir yeni vitese doğru düşer/çıkar, hız sabit
                    rpm += ((v * ratios[gear - 1]) - rpm) * 0.25 + (rnd.nextDouble() - 0.5) * 150
                    if (stateT > 0.8) { state = "drive"; stateT = 0.0 } }
                "coast" -> { clutch = true   // boşta süzülme, ara sıra gaz
                    v = (v - 0.4 * dt).coerceAtLeast(0.0); rpm = if ((t * 10).toInt() % 37 < 4) 1600.0 else 830 + rnd.nextGaussian() * 15
                    if (stateT > 6) { gear = (1..maxGear).minBy { abs(v * ratios[it - 1] - 1700) }; state = "drive"; stateT = 0.0 } }
                "brake" -> { clutch = gear == 0 || v < 12
                    v = (v - 3.0 * dt).coerceAtLeast(0.0)
                    rpm = if (clutch) 830.0 else v * ratios[gear - 1]
                    if (!clutch && rpm < 1100 && gear > 1) gear--
                    if (v <= 0.0) { state = "stop"; stateT = 0.0; gear = 0 } }
            }
        }
        /** OBD'nin gördüğü: tam sayı hız, hız ölçümü bir adım geride */
        fun obdSpeed() = kotlin.math.floor(vPrev)
        fun obdRpm() = Math.round(rpm * 4) / 4.0
    }

    private fun scoreDrive(g: GearEstimator, d: ManualDrive, minutes: Int, scoreFrom: Int): Triple<Int, Int, Int> {
        var ok = 0; var wrong = 0; var total = 0
        val steps = minutes * 60 * 5
        repeat(steps) { i ->
            d.step(0.2)
            val got = g.update(d.obdRpm(), d.obdSpeed())
            if (i < scoreFrom * 60 * 5) return@repeat
            if (!d.clutch && d.state == "drive" && d.stateT > 1.0 && d.v >= 8) {
                total++
                if (got == d.gear) ok++ else if (got != null) wrong++
            } else if (d.clutch && got != null && d.state != "shift") wrong++   // debriyajda kesin bir vites göstermek de hata
        }
        return Triple(ok, wrong, total)
    }

    private val trueRatios = doubleArrayOf(108.0, 58.5, 38.0, 28.6, 22.4)   // önceliklerden farklı, ~%9 küçük

    /** Yalnız şehir içi (5. vites hiç kullanılmıyor): numaralar kaymamalı, sahte vites çıkmamalı. */
    @Test fun gearEstimatorCityDriveNoFifth() {
        val g = GearEstimator()
        val (ok, wrong, total) = scoreDrive(g, ManualDrive(trueRatios, 4, 1), 20, 8)
        println("şehir: oranlar ${g.ratios.toSortedMap()} doğru $ok/$total yanlış $wrong")
        assertEquals("öğrenilen vitesler", setOf(1, 2, 3, 4), g.ratios.keys)
        assertTrue("doğruluk düşük: $ok/$total", ok >= total * 0.93)
        assertTrue("yanlış vites çok: $wrong", wrong <= total * 0.01)
    }

    /** Karışık sürüş, sonra kayıt/yükleme sonrası aynı sonuç. */
    @Test fun gearEstimatorMixedDriveAndPersist() {
        val g = GearEstimator()
        val (ok, wrong, total) = scoreDrive(g, ManualDrive(trueRatios, 5, 2), 25, 10)
        println("karışık: oranlar ${g.ratios.toSortedMap()} doğru $ok/$total yanlış $wrong")
        assertEquals(setOf(1, 2, 3, 4, 5), g.ratios.keys)
        // Düşük viteslerde tam sayıya kesilen hız oranı büyük gösterir (1.: ~%5, 2.: ~%2); eşleşme aynı ölçümle yapıldığı için zararsız
        for ((k, r) in g.ratios) assertEquals("vites $k oranı", trueRatios[k - 1], r, trueRatios[k - 1] * (when (k) { 1 -> 0.07; 2 -> 0.04; else -> 0.02 }))
        assertTrue("doğruluk düşük: $ok/$total", ok >= total * 0.93)
        assertTrue("yanlış vites çok: $wrong", wrong <= total * 0.01)
        val g2 = GearEstimator().apply { load(g.save()) }
        assertEquals(g.ratios.keys, g2.ratios.keys)
        assertNull("duruyorken vites olmamalı", g2.gear(800.0, 0.0))
        assertEquals(3, g2.gear(38.0 * 50, 50.0))
    }

    /** Eşleme: tek tepe ve atlanan vitesler doğru numaralanmalı. */
    @Test fun gearAssignmentWithGaps() {
        assertEquals(mapOf(2 to 58.0, 3 to 38.5, 5 to 22.5), GearEstimator.assign(listOf(58.0, 38.5, 22.5)))
        assertEquals(mapOf(4 to 29.0), GearEstimator.assign(listOf(29.0)))
    }

    /** Yolculuk: 60 km/h'te 30 dk = 30 km; 10 dk'dan uzun kesinti yeni yolculuk. */
    @Test fun tripIntegratesAndResets() {
        val t = Trip()
        var ts = 1_000_000L
        repeat(30 * 60 * 5) { t.update(Sample("speed", 60.0, ts)); ts += 200 }
        repeat(5 * 60 * 5) { t.update(Sample("speed", 0.0, ts)); ts += 200 }   // 5 dk bekleme
        assertEquals(30.0, t.km, 0.1)
        assertEquals(60.0, t.avgKmh, 0.5)
        assertEquals(35 * 60_000.0, t.durationMs.toDouble(), 1000.0)
        t.update(Sample("speed", 20.0, ts + 11 * 60_000))
        assertEquals(0.0, t.km, 1e-9)
        assertEquals(20.0, t.maxKmh, 1e-9)
    }

    @Test fun smootherReachesTargetWithoutOvershoot() {
        val s = Smoother(60, 250)
        s.setTarget(800.0, 1000)
        assertEquals(800.0, s.step(16.0), 1e-9)   // ilk değer doğrudan
        s.setTarget(2000.0, 1200)
        var last = 800.0
        repeat(30) { val v = s.step(16.0); assertTrue(v >= last && v <= 2000.0); last = v }
        assertTrue("0,5 sn'de hedefe yaklaşmalı: $last", last > 1900)
    }

    @Test fun boostUsesIdleMapAsZero() {
        var now = 1000L
        var map = 101.0; var rpm = 800.0; var speed = 0.0
        val src = object : SignalSource {
            override fun sample(key: String) = when (key) {
                "map" -> Sample(key, map, now); "rpm" -> Sample(key, rpm, now); "speed" -> Sample(key, speed, now); else -> null
            }
            override val name = "t"; override val online = true; override val rpmHz = 5.0
        }
        val m = DashModel(src) { now }
        repeat(50) { now += 200; m.frame(listOf("boost")) }   // rölanti: sıfır noktası öğrenilir
        map = 181.0; rpm = 2500.0; speed = 60.0
        repeat(100) { now += 50; m.frame(listOf("boost")) }
        val d = m.frame(listOf("boost")).getValue("boost")
        assertEquals(0.8, d.value, 0.02)                     // (181 - 101) kPa = 0,8 bar
        assertEquals("0.80", d.text.replace(',', '.'))
    }

    @Test fun staleAndMissing() {
        var now = 0L
        val src = object : SignalSource {
            override fun sample(key: String) = if (key == "rpm") Sample(key, 1000.0, 0) else null
            override val name = "t"; override val online = true; override val rpmHz = 5.0
        }
        val m = DashModel(src) { now }
        now = 100; assertTrue(!m.frame(listOf("rpm")).getValue("rpm").stale)
        now = 3000; val d = m.frame(listOf("rpm")).getValue("rpm")
        assertTrue(d.stale && !d.missing && d.text == "1000")   // bayat ama değer korunur, sıfıra düşmez
        assertTrue(m.frame(listOf("coolant")).getValue("coolant").missing)
    }
}
