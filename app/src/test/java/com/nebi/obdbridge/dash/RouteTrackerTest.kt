package com.nebi.obdbridge.dash

import com.nebi.obdbridge.dash.RouteTracker.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTrackerTest {
    // Doğuya giden düz yol: 41° enlemde 0,001° boylam ≈ 84 m
    private val road = (0..20).map { LatLon(41.0, 29.0 + it * 0.001) }

    @Test fun requestsOnceThenFollows() {
        var t = 0L
        val r = RouteTracker { t }
        assertFalse(r.onFix(41.0, 29.0))                 // hedef yok
        r.setDest(41.0, 29.02)
        assertTrue(r.onFix(41.0, 29.0)); r.onRequested()
        t += 1000; assertFalse(r.onFix(41.0, 29.0005))   // cevap bekleniyor
        r.onRoute(road)
        repeat(10) { t += 1000; assertFalse(r.onFix(41.0, 29.001 + it * 0.0005)) }
    }

    @Test fun reroutesWhenOffRoute() {
        var t = 0L
        val r = RouteTracker { t }
        r.setDest(41.0, 29.02)
        r.onFix(41.0, 29.0); r.onRequested(); r.onRoute(road)
        t += 30_000
        // 200 m kuzeye saptı (0,0018° enlem)
        assertFalse(r.onFix(41.0018, 29.005)); assertFalse(r.onFix(41.0018, 29.006))
        assertTrue(r.onFix(41.0018, 29.007))
    }

    @Test fun rateLimited() {
        var t = 0L
        val r = RouteTracker { t }
        r.setDest(41.0, 29.02)
        r.onFix(41.0, 29.0); r.onRequested(); r.onRoute(road)
        t += 5000
        repeat(5) { assertFalse(r.onFix(41.0018, 29.005)) }   // sapma var ama 20 sn dolmadı
        t += 20_000
        assertTrue(r.onFix(41.0018, 29.005))
    }

    @Test fun forgetsOnArrival() {
        val r = RouteTracker { 0L }
        r.setDest(41.0, 29.02)
        r.onFix(41.0, 29.0); r.onRequested(); r.onRoute(road)
        assertFalse(r.onFix(41.0, 29.0195))   // ~40 m
        assertNull(r.dest); assertNull(r.route)
    }

    @Test fun distanceToRoute() {
        assertEquals(0.0, RouteTracker.distToRoute(LatLon(41.0, 29.0055), road), 1.0)
        assertEquals(200.0, RouteTracker.distToRoute(LatLon(41.0018, 29.0055), road), 5.0)
    }
}
