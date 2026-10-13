package com.nebi.obdbridge.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MotionTest {
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

    @Test fun gpsSlowsDownWhenStill() {
        var t = 0L
        val p = GpsPacer { t }
        assertEquals(GpsPacer.FAST, p.intervalMs(false, false, null))               // henüz konum yok
        assertEquals(GpsPacer.FAST, p.intervalMs(false, false, 0.0))
        t += 70_000
        assertEquals(GpsPacer.SLOW, p.intervalMs(false, false, 0.0))
        assertEquals(GpsPacer.FAST, p.intervalMs(true, false, 0.0))                 // OBD bağlı
        t += 1000
        assertEquals(GpsPacer.FAST, p.intervalMs(false, false, 0.0))                // az önce bağlıydı: 60 sn hızlı kalır
        t += 70_000
        assertEquals(GpsPacer.SLOW, p.intervalMs(false, false, 0.0))
        assertEquals(GpsPacer.FAST, p.intervalMs(false, false, 12.0))               // GPS hareket gördü
    }
}
