package com.nebi.obdbridge.dash

import com.nebi.obdbridge.dash.RouteTracker.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceTest {
    // Doğuya 840 m (0,001° ≈ 84 m), sonra sağa (güneye) 555 m
    private val east = (0..10).map { LatLon(41.0, 29.0 + it * 0.001) }
    private val south = (1..5).map { LatLon(41.0 - it * 0.001, 29.010) }
    private val pts = east + south
    private val turnAt = Guidance.cumulative(pts)[10]
    private val route = NavRoute(pts, listOf(Maneuver(turnAt, Turn.RIGHT, "Eflak Cad."), Maneuver(Guidance.cumulative(pts).last(), Turn.FINISH, "")), 600.0)

    @Test fun followsAndPassesTurn() {
        val g = Guidance(route)
        g.onFix(LatLon(41.00005, 29.005))                 // yolun 5 m kuzeyi, yarı yolda
        assertEquals(turnAt / 2, g.alongM, 5.0)
        assertEquals(5.5, g.offM, 1.0)
        assertEquals(Turn.RIGHT, g.next!!.turn)
        assertEquals(turnAt / 2, g.nextDistM!!, 5.0)
        assertEquals(600.0 * (1 - turnAt / 2 / g.totalM), g.remainingS!!, 600.0 * 0.01)  // kalan mesafeyle orantılı
        g.onFix(LatLon(40.9990, 29.0101))                  // dönüşten sonra
        assertEquals(Turn.FINISH, g.next!!.turn)
    }

    @Test fun gpsJitterDoesNotGoBackwards() {
        val g = Guidance(route)
        g.onFix(LatLon(41.0, 29.005))
        val a = g.alongM
        g.onFix(LatLon(41.0, 29.0048))                     // ~17 m geri (titreme)
        assertEquals(a, g.alongM, 0.01)
    }

    @Test fun formatsDistances() {
        assertEquals("80 m", Guidance.formatDist(83.0))
        assertEquals("1,2 km", Guidance.formatDist(1234.0))
        assertEquals("12 km", Guidance.formatDist(12_400.0))
        // TurnWatch aynı metni okuyabilmeli
        assertEquals(1200.0, TurnWatch.parseDistance(Guidance.formatDist(1234.0))!!, 0.1)
    }

    @Test fun voiceFarThenNearOnce() {
        val v = VoicePlanner()
        val m = Maneuver(1000.0, Turn.RIGHT, "Eflak Cad.")
        assertNull(v.update(0, m, 900.0, 50.0))            // 50 km/h: uzak eşik 350 m
        assertEquals("350 metre sonra sağa dönün", v.update(0, m, 340.0, 50.0))
        assertNull(v.update(0, m, 300.0, 50.0))
        assertEquals("Sağa dönün, Eflak Caddesi", v.update(0, m, 100.0, 50.0))
        assertNull(v.update(0, m, 50.0, 50.0))
    }

    @Test fun voiceCloseManeuverSkipsFar() {
        val v = VoicePlanner()
        val m = Maneuver(0.0, Turn.ROUNDABOUT, "", exit = 3)
        assertNull(v.update(4, m, 90.0, 30.0))             // uzak uyarıya yer yok, yakın eşik 66 m
        assertEquals("Göbekte üçüncü çıkıştan çıkın", v.update(4, m, 60.0, 30.0))
    }

    @Test fun finishSaysFarOnlyAndArrivalIsSeparate() {
        val v = VoicePlanner()
        val m = Maneuver(0.0, Turn.FINISH, "")
        assertTrue(v.update(1, m, 340.0, 50.0)!!.endsWith("hedefe varacaksınız"))
        assertNull(v.update(1, m, 50.0, 50.0))
    }

    @Test fun spokenDistances() {
        assertEquals("1,5 kilometre", VoicePlanner.spokenDist(1480.0))
        assertEquals("1 kilometre", VoicePlanner.spokenDist(990.0))
        assertEquals("Bağdat Caddesi", VoicePlanner.speakStreet("Bağdat Cad."))
        assertEquals("Caddebostan Sokağı", VoicePlanner.speakStreet("Caddebostan Sok."))
    }

    @Test fun headingFromRouteWhileSlow() {
        val g = Guidance(route)
        g.onFix(LatLon(41.0, 29.005))
        assertEquals(90.0, g.routeBearing!!, 0.5)          // doğu
        val h = HeadingFilter()
        assertEquals(90.0, h.update(LatLon(41.0, 29.005), 300f, 90f, 2.0, 5f, g.routeBearing)!!, 0.5)
    }

    @Test fun headingFromDisplacementWhenGpsBearingUnreliable() {
        val h = HeadingFilter()
        assertNull(h.update(LatLon(41.0, 29.0), 123f, 90f, 3.0, 5f, null))       // yavaş, yön doğruluğu kötü
        assertNull(h.update(LatLon(41.00005, 29.0), 200f, 90f, 3.0, 5f, null))    // 5,5 m: yetmez
        assertEquals(0.0, h.update(LatLon(41.00015, 29.0), 200f, 90f, 3.0, 5f, null)!!, 0.5)   // 16 m kuzey
        assertEquals(0.0, h.update(LatLon(41.00015, 29.0), null, null, 0.0, 5f, null)!!, 0.5)  // durunca kalır
        assertEquals(270.0, h.update(LatLon(41.00015, 29.0), 270f, 5f, 40.0, 5f, null)!!, 0.5) // hızlı: GPS yönü
    }
}
