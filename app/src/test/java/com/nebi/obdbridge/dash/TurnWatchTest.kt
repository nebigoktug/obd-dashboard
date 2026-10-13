package com.nebi.obdbridge.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnWatchTest {
    @Test fun parsesDistances() {
        assertEquals(50.0, TurnWatch.parseDistance("50 m")!!, 0.01)
        assertEquals(1200.0, TurnWatch.parseDistance("1,2 km")!!, 0.01)
        assertEquals(1200.0, TurnWatch.parseDistance("1.2 km")!!, 0.01)
        assertEquals(800.0, TurnWatch.parseDistance("800 м")!!, 0.01)
        assertNull(TurnWatch.parseDistance("Eken Sok."))
    }

    @Test fun showsNearTurnAndHidesAfterIt() {
        var t = 0L
        val w = TurnWatch { t }
        w.update("1,5 km", 50.0); assertFalse(w.visible); assertFalse(w.prepare)
        w.update("450 m", 50.0); assertFalse(w.visible); assertTrue(w.prepare)   // döşemeler önceden
        w.update("290 m", 50.0); assertTrue(w.visible)
        w.update("320 m", 50.0); assertTrue(w.visible)                         // eşikte titreme yok
        w.update("20 m", 20.0); assertTrue(w.visible)
        // Dönüş bitti: sıradaki manevra 2 km
        t += 1000; w.update("2 km", 20.0); assertTrue(w.visible)               // biraz daha görünür
        t += 5000; w.update("1,9 km", 40.0); assertFalse(w.visible)
    }

    @Test fun backToBackTurnsKeepMap() {
        var t = 0L
        val w = TurnWatch { t }
        w.update("100 m", 30.0); assertTrue(w.visible)
        w.update("10 m", 20.0)
        t += 1000; w.update("150 m", 20.0); assertTrue(w.visible)
        t += 10_000; w.update("120 m", 20.0); assertTrue(w.visible)
    }

    @Test fun hidesWhenRouteEnds() {
        val w = TurnWatch { 0L }
        w.update("80 m", 30.0); assertTrue(w.visible)
        w.update(null, 30.0); assertFalse(w.visible)
    }

    @Test fun thresholdGrowsWithSpeed() {
        assertEquals(300.0, TurnWatch.showM(30.0), 0.1)
        assertEquals(500.0, TurnWatch.showM(90.0), 0.1)
    }

    @Test fun zoomKeepsTurnInView() {
        val near = TurnWatch.zoomFor(80.0, 41.0, 150.0)
        val far = TurnWatch.zoomFor(400.0, 41.0, 150.0)
        assertTrue(near > far)
        assertTrue(far in 14.5..18.5 && near in 14.5..18.5)
    }

    /** Dikey kaydırma: elle seçim otomatik karar değişene kadar geçerli */
    @Test fun manualToggleUntilNextAutoChange() {
        var t = 0L
        val w = TurnWatch { t }
        w.update(null, 0.0); assertFalse(w.visible)
        w.toggle(); w.update(null, 0.0); assertTrue(w.visible); assertTrue(w.prepare)   // navigasyonsuz harita
        w.toggle(); assertFalse(w.visible)
        w.update("2 km", 50.0); w.toggle(); assertTrue(w.visible)
        w.update("250 m", 50.0); assertTrue(w.visible)
        w.toggle(); w.update("100 m", 50.0); assertFalse(w.visible)                    // dönüşte kapattı: kapalı kalır
        w.update("10 m", 50.0)
        t += 1000; w.update("2 km", 30.0)
        t += 5000; w.update("1,9 km", 30.0); assertFalse(w.visible)
        w.update("200 m", 30.0); assertTrue(w.visible)                                  // sonraki dönüşte yine otomatik
    }
}
