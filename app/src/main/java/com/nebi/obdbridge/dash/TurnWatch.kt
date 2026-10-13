package com.nebi.obdbridge.dash

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max

/**
 * Dönüşe yaklaşırken devir göstergesinin yerine yuvarlak harita (kullanıcı fikri, 2026-10-13). Android'siz, birim testli.
 * Girdi yalnız navigasyon bildirimindeki mesafe ("300 m", "1,2 km"): rota ve dönüş noktası bize gelmez.
 *  - Mesafe [showM]'nin altına inince görünür (hızla büyür: ~20 sn önceden, en az 300 m).
 *  - Navigasyon bir sonraki manevraya geçince (mesafe sıçrar) dönüş bitmiştir: [LINGER_MS] daha görünür kalır,
 *    yeni manevra da yakınsa hiç kapanmaz.
 *  - Bildirim kalkarsa (rota bitti/iptal) kapanır. Kırmızı ışıkta bildirim güncellenmez: tazelik şartı yok.
 * [prepare]: haritanın döşemeleri önceden insin diye biraz daha erken (görünmeden) hazırlanır.
 * Elle geçiş ([toggle], devir göstergesinde dikey kaydırma): seçim, otomatik karar bir sonraki kez değişene kadar
 * geçerli (dönüşte haritayı kapatırsan o dönüş bitene kadar gelmez; navigasyonsuz açarsan açık kalır).
 */
class TurnWatch(private val clock: () -> Long = System::currentTimeMillis) {
    /** Otomatik karar ve elle seçim birlikte: ekranda harita mı */
    val visible get() = manual ?: auto
    val prepare get() = visible || autoPrepare
    private var auto = false
    private var autoPrepare = false
    private var manual: Boolean? = null

    /** Elle geçiş: şu an görünenin tersi */
    fun toggle() { manual = !visible }
    /** Son okunan manevra mesafesi (m) */
    var distanceM: Double? = null; private set
    private var lastD = Double.NaN
    private var turnedAt = 0L

    /** [navi] bildirim metni ("300 m"), yoksa null; [speedKmh] araç hızı */
    fun update(navi: String?, speedKmh: Double?) {
        val now = clock()
        val d = navi?.let { parseDistance(it) }
        distanceM = d
        if (d == null) { setAuto(false); autoPrepare = false; lastD = Double.NaN; turnedAt = 0L; return }
        val show = showM(speedKmh)
        // Sıçrama: sıradaki manevraya geçildi (dönüş tamam)
        if (!lastD.isNaN() && d > lastD + JUMP_M && auto) turnedAt = now
        lastD = d
        val near = d <= show
        setAuto(when {
            near -> { turnedAt = 0L; true }
            turnedAt != 0L && now - turnedAt < LINGER_MS -> true
            auto && turnedAt == 0L && d <= show + HYST_M -> true   // eşikte titremesin
            else -> { turnedAt = 0L; false }
        })
        autoPrepare = d <= show * 1.6
    }

    /** Otomatik karar değişince elle seçim biter */
    private fun setAuto(v: Boolean) { if (v != auto) { auto = v; manual = null } }

    companion object {
        const val MIN_SHOW_M = 300.0
        const val LEAD_S = 20.0
        const val JUMP_M = 60.0
        const val HYST_M = 60.0
        const val LINGER_MS = 4000L

        fun showM(speedKmh: Double?) = max(MIN_SHOW_M, (speedKmh ?: 0.0) / 3.6 * LEAD_S)

        /** "300 m", "1,2 km", "1.2 km", "800 м" → metre; tanınmazsa null */
        fun parseDistance(s: String): Double? {
            val m = Regex("""(\d+(?:[.,]\d+)?)\s*(km|m|км|м)\b""", RegexOption.IGNORE_CASE).find(s.replace(' ', ' ')) ?: return null
            val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            return if (m.groupValues[2].lowercase().startsWith("k")) v * 1000 else v
        }

        /**
         * Harita yakınlığı: araç dairenin altına yakın, dönüş noktası ([distM] ileride) dairede görünsün.
         * [aheadDp] aracın önünde görünen yol (dp). MapLibre ölçeği: 512 dp'lik döşeme.
         */
        fun zoomFor(distM: Double, latDeg: Double, aheadDp: Double): Double {
            val want = distM.coerceIn(60.0, 900.0) + 50
            val z = ln(40_075_016.686 * cos(Math.toRadians(latDeg)) * aheadDp / (512 * want)) / ln(2.0)
            return z.coerceIn(14.5, 18.5)
        }
    }
}
