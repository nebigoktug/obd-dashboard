package com.nebi.obdbridge.dash

import com.nebi.obdbridge.dash.RouteTracker.Companion.dist
import com.nebi.obdbridge.dash.RouteTracker.LatLon
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Kendi navigasyonumuz (2026-10-14, kullanıcı kararı: dış navigasyon uygulamasına bağımlılık kalktı).
 * Rota ve manevralar Yandex MapKit'ten gelir (YandexNav); burası Android'siz, birim testli kısım:
 * konumu rotaya izdüşürür, sıradaki manevrayı ve kalan mesafeyi verir, sesli uyarıların zamanını seçer.
 */
enum class Turn { STRAIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, LEFT, RIGHT, HARD_LEFT, HARD_RIGHT, FORK_LEFT, FORK_RIGHT,
    UTURN_LEFT, UTURN_RIGHT, ROUNDABOUT, LEAVE_ROUNDABOUT, EXIT_LEFT, EXIT_RIGHT, FINISH }

/** [atM] rota başından manevraya mesafe; [exit] göbekte kaçıncı çıkış */
data class Maneuver(val atM: Double, val turn: Turn, val street: String, val exit: Int? = null)

/** [timeS] trafikli süre (rotanın tamamı) */
data class NavRoute(val points: List<LatLon>, val maneuvers: List<Maneuver>, val timeS: Double?)

class Guidance(val route: NavRoute) {
    private val pts = route.points
    /** cum[i]: başlangıçtan i. noktaya mesafe */
    val cum: DoubleArray = cumulative(pts)
    val totalM get() = cum.last()
    private var seg = 0
    var alongM = 0.0; private set
    /** Konumun rotaya uzaklığı (m) */
    var offM = 0.0; private set

    fun onFix(p: LatLon) {
        if (pts.size < 2) return
        // Önce bulunduğumuz yerin yakınında ara (rota kendine yaklaşınca geri atlamasın), uzaksa her yerde
        var best = nearest(p, (seg - 2).coerceAtLeast(0), (seg + 60).coerceAtMost(pts.size - 2))
        if (best.second > WINDOW_OFF_M) best = nearest(p, 0, pts.size - 2).takeIf { it.second < best.second } ?: best
        val (along, d, s) = best
        seg = s; offM = d
        // Küçük geri sıçrama GPS titremesidir: yok say. Büyüğü gerçek (geri dönüş) ya da rotadan çıkış: kabul et
        alongM = if (along >= alongM || alongM - along > BACK_TOL_M || d > WINDOW_OFF_M) along else alongM
    }

    /** Rotanın bulunulan yerdeki doğrultusu (°, kuzey 0); rotadan uzaksa null */
    val routeBearing: Double? get() = if (pts.size < 2 || offM > ON_ROUTE_M) null else HeadingFilter.bearing(pts[seg], pts[seg + 1])

    /** Sıradaki manevra ([PASS_M] içinde geçilmiş sayılır) */
    val nextIndex get() = route.maneuvers.indexOfFirst { it.atM > alongM + PASS_M }
    val next get() = route.maneuvers.getOrNull(nextIndex)
    val nextDistM get() = next?.let { it.atM - alongM }
    val remainingM get() = (totalM - alongM).coerceAtLeast(0.0)
    /** Kalan süre: trafikli süre, kalan mesafeyle orantılı */
    val remainingS get() = route.timeS?.let { if (totalM > 0) it * remainingM / totalM else null }

    /** (rota üstü mesafe, uzaklık, parça) */
    private fun nearest(p: LatLon, from: Int, to: Int): Triple<Double, Double, Int> {
        val kx = 111_320.0 * cos(Math.toRadians(p.lat)); val ky = 110_574.0
        var best = Triple(0.0, Double.MAX_VALUE, from)
        for (i in from..to) {
            val ax = (pts[i].lon - p.lon) * kx; val ay = (pts[i].lat - p.lat) * ky
            val bx = (pts[i + 1].lon - p.lon) * kx; val by = (pts[i + 1].lat - p.lat) * ky
            val vx = bx - ax; val vy = by - ay
            val l2 = vx * vx + vy * vy
            val t = if (l2 == 0.0) 0.0 else (-(ax * vx + ay * vy) / l2).coerceIn(0.0, 1.0)
            val cx = ax + t * vx; val cy = ay + t * vy
            val d = sqrt(cx * cx + cy * cy)
            if (d < best.second) best = Triple(cum[i] + t * (cum[i + 1] - cum[i]), d, i)
        }
        return best
    }

    companion object {
        const val PASS_M = 8.0
        const val WINDOW_OFF_M = 40.0
        const val BACK_TOL_M = 30.0
        const val ON_ROUTE_M = 25.0

        fun cumulative(pts: List<LatLon>) = DoubleArray(pts.size).also { c ->
            for (i in 1 until pts.size) c[i] = c[i - 1] + dist(pts[i - 1], pts[i])
        }

        /** Ekran: "80 m", "350 m", "1,2 km", "12 km" (TurnWatch.parseDistance okur) */
        fun formatDist(m: Double): String = when {
            m < 1000 -> "${((m / 10).roundToInt() * 10).coerceAtLeast(0)} m"
            m < 10_000 -> "%.1f km".format(java.util.Locale.ROOT, m / 1000).replace('.', ',')
            else -> "${(m / 1000).roundToInt()} km"
        }
    }
}

/**
 * Harita yönü (yön yukarıda). Rotadaysa rotanın doğrultusu (yavaşken/dururken de sağlam); değilse hızlıyken
 * GPS yönü, yavaşken GPS yönü yalnız doğruluğu iyiyse, o da yoksa son [MOVE_M] metrelik yer değiştirmenin yönü.
 * Hiçbiri yoksa son yön kalır (kırmızı ışıkta harita dönmez).
 */
class HeadingFilter {
    var heading: Double? = null; private set
    private var anchor: LatLon? = null

    fun update(p: LatLon, gpsBearing: Float?, bearingAccDeg: Float?, kmh: Double?, accM: Float?, routeBearing: Double?): Double? {
        val v = kmh ?: 0.0
        when {
            routeBearing != null -> { heading = routeBearing; anchor = p }
            gpsBearing != null && (v > FAST_KMH || v > SLOW_KMH && (bearingAccDeg ?: 99f) <= GOOD_ACC_DEG) -> { heading = gpsBearing.toDouble(); anchor = p }
            (accM ?: 0f) > MAX_ACC_M -> {}
            else -> {
                val a = anchor
                if (a == null) anchor = p
                else if (dist(a, p) >= MOVE_M) { heading = bearing(a, p); anchor = p }
            }
        }
        return heading
    }

    companion object {
        const val FAST_KMH = 6.0
        const val SLOW_KMH = 1.5
        const val GOOD_ACC_DEG = 30f
        const val MAX_ACC_M = 25f
        const val MOVE_M = 12.0

        /** a'dan b'ye yön (°, kuzey 0, saat yönü) */
        fun bearing(a: LatLon, b: LatLon): Double {
            val dx = (b.lon - a.lon) * cos(Math.toRadians((a.lat + b.lat) / 2)); val dy = b.lat - a.lat
            return (Math.toDegrees(kotlin.math.atan2(dx, dy)) + 360) % 360
        }
    }
}

/**
 * Sesli yönlendirme metinleri ve zamanı. Her manevra için en çok iki uyarı:
 * uzak ("400 metre sonra sağa dönün", ~25 sn önce) ve yakın ("Sağa dönün, Eflak Caddesi", ~8 sn önce).
 */
class VoicePlanner {
    private var idx = -1
    private var farDone = false
    private var nearDone = false

    fun reset() { idx = -1; farDone = false; nearDone = false }

    /** Söylenecek metin ya da null. [i] manevra sırası (değişince yeni manevra) */
    fun update(i: Int, m: Maneuver?, distM: Double?, kmh: Double?): String? {
        if (m == null || distM == null) return null
        if (i != idx) { idx = i; farDone = false; nearDone = false }
        val v = (kmh ?: 0.0) / 3.6
        val near = (v * 8).coerceIn(60.0, 250.0)
        val far = (v * 25).coerceIn(350.0, 1500.0)
        if (!nearDone && distM <= near) {
            nearDone = true; farDone = true
            // Varış ayrıca, hedefe gerçekten gelince söylenir (RouteTracker.ARRIVE_M)
            return if (m.turn == Turn.FINISH) null else nearText(m)
        }
        if (!farDone && distM <= far && distM > near + 100) {
            farDone = true
            return "${spokenDist(distM)} sonra ${phrase(m)}"
        }
        return null
    }

    companion object {
        fun phrase(m: Maneuver): String = when (m.turn) {
            Turn.STRAIGHT -> "düz devam edin"
            Turn.SLIGHT_LEFT -> "hafif sola dönün"
            Turn.SLIGHT_RIGHT -> "hafif sağa dönün"
            Turn.LEFT -> "sola dönün"
            Turn.RIGHT -> "sağa dönün"
            Turn.HARD_LEFT -> "keskin sola dönün"
            Turn.HARD_RIGHT -> "keskin sağa dönün"
            Turn.FORK_LEFT -> "ayrımda soldan devam edin"
            Turn.FORK_RIGHT -> "ayrımda sağdan devam edin"
            Turn.UTURN_LEFT, Turn.UTURN_RIGHT -> "U dönüşü yapın"
            Turn.ROUNDABOUT -> m.exit?.let { "göbekte ${ordinal(it)} çıkıştan çıkın" } ?: "göbeğe girin"
            Turn.LEAVE_ROUNDABOUT -> "göbekten çıkın"
            Turn.EXIT_LEFT -> "soldaki çıkışı kullanın"
            Turn.EXIT_RIGHT -> "sağdaki çıkışı kullanın"
            Turn.FINISH -> "hedefe varacaksınız"
        }

        fun nearText(m: Maneuver): String {
            val s = speakStreet(m.street)
            return phrase(m).replaceFirstChar { it.uppercase(java.util.Locale("tr")) } + if (s.isNotEmpty()) ", $s" else ""
        }

        /** 50 m'ye yuvarlanmış "300 metre", "1,5 kilometre" */
        fun spokenDist(m: Double): String = if (m < 950) "${((m / 50).roundToInt() * 50).coerceAtLeast(50)} metre"
            else "%.1f".format(java.util.Locale.ROOT, m / 1000).removeSuffix(".0").replace('.', ',') + " kilometre"

        private fun ordinal(n: Int) = listOf("birinci", "ikinci", "üçüncü", "dördüncü", "beşinci", "altıncı").getOrNull(n - 1) ?: "$n."

        /** Kısaltmalar seslendirmede açık okunsun */
        fun speakStreet(s: String): String = s
            .replace(Regex("""\bCad\.?(?=\s|$)"""), "Caddesi")
            .replace(Regex("""\bSok\.?(?=\s|$)"""), "Sokağı")
            .replace(Regex("""\bSk\.?(?=\s|$)"""), "Sokağı")
            .replace(Regex("""\bBul\.?(?=\s|$)"""), "Bulvarı")
            .replace(Regex("""\bMah\.?(?=\s|$)"""), "Mahallesi")
            .trim()
    }
}
