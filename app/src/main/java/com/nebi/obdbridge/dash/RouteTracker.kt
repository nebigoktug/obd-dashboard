package com.nebi.obdbridge.dash

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Yuvarlak haritadaki rota çizgisi (2026-10-13): Yandex Navigator rotasını vermez; Ev/İş ile başlatılan
 * yolculukta aynı hedefe rotayı Yandex MapKit'le biz hesaplarız (aynı motor + trafik: çoğunlukla tutar).
 * Bu sınıf yalnız ne zaman istek atılacağına karar verir (Android'siz, birim testli):
 *  - hedef seçilince ilk konumda,
 *  - araç rotadan [OFF_M]'den uzaklaşıp [OFF_FIXES] konum boyunca öyle kalırsa (Navigator da yeniden hesaplar),
 *  - istekler arası en az [MIN_GAP_MS] (kota).
 * Hedefe [ARRIVE_M] yaklaşınca ya da [MAX_AGE_MS] sonra hedef unutulur. Navigator'ın bildirimi kalkınca
 * silinmez: Navigator öndeyken bildirim göstermez.
 */
class RouteTracker(private val clock: () -> Long = System::currentTimeMillis) {
    data class LatLon(val lat: Double, val lon: Double)

    var dest: LatLon? = null; private set
    var route: List<LatLon>? = null; private set
    private var destTs = 0L
    private var lastReq = Long.MIN_VALUE / 2
    private var off = 0
    private var pending = false

    fun setDest(lat: Double, lon: Double) { dest = LatLon(lat, lon); destTs = clock(); route = null; off = 0; lastReq = Long.MIN_VALUE / 2; pending = false }

    fun clear() { dest = null; route = null; pending = false }

    /** Konum geldi: true ise şimdi rota istenmeli (istek atılınca [onRequested]) */
    fun onFix(lat: Double, lon: Double): Boolean {
        val d = dest ?: return false
        val now = clock()
        if (now - destTs > MAX_AGE_MS || dist(LatLon(lat, lon), d) < ARRIVE_M) { clear(); return false }
        val r = route
        off = if (r != null && distToRoute(LatLon(lat, lon), r) > OFF_M) off + 1 else 0
        if (pending || now - lastReq < MIN_GAP_MS) return false
        return r == null || off >= OFF_FIXES
    }

    fun onRequested() { pending = true; lastReq = clock() }

    fun onRoute(points: List<LatLon>?) { pending = false; if (points != null && dest != null) { route = points; off = 0 } }

    companion object {
        const val OFF_M = 60.0
        const val OFF_FIXES = 3
        const val MIN_GAP_MS = 20_000L
        const val ARRIVE_M = 80.0
        const val MAX_AGE_MS = 4 * 3600_000L

        /** Kısa mesafede düzlem yaklaşımı (m) */
        fun dist(a: LatLon, b: LatLon): Double {
            val kx = 111_320.0 * cos(Math.toRadians((a.lat + b.lat) / 2)); val ky = 110_574.0
            val dx = (a.lon - b.lon) * kx; val dy = (a.lat - b.lat) * ky
            return sqrt(dx * dx + dy * dy)
        }

        /** Noktanın çoklu çizgiye en kısa uzaklığı (m) */
        fun distToRoute(p: LatLon, r: List<LatLon>): Double {
            if (r.size == 1) return dist(p, r[0])
            val kx = 111_320.0 * cos(Math.toRadians(p.lat)); val ky = 110_574.0
            var best = Double.MAX_VALUE
            for (i in 0 until r.size - 1) {
                val ax = (r[i].lon - p.lon) * kx; val ay = (r[i].lat - p.lat) * ky
                val bx = (r[i + 1].lon - p.lon) * kx; val by = (r[i + 1].lat - p.lat) * ky
                val vx = bx - ax; val vy = by - ay
                val l2 = vx * vx + vy * vy
                val t = if (l2 == 0.0) 0.0 else (-(ax * vx + ay * vy) / l2).coerceIn(0.0, 1.0)
                val cx = ax + t * vx; val cy = ay + t * vy
                best = minOf(best, sqrt(cx * cx + cy * cy))
            }
            return best
        }
    }
}
