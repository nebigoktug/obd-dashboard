package com.nebi.obdbridge.dash

import android.content.Context
import com.nebi.obdbridge.t

/**
 * Navigasyon adımı (2026-11-27, Android Auto için ekrandan ayrıldı): izdüşüm, varış, rota isteği (ilk / yoldan çıkınca),
 * sesli uyarı. Konumu navigasyon süresince [NavService] verir; telefon ekranı kapalıyken ya da yalnız araç ekranı
 * (Android Auto) açıkken de çalışır. Ekranlar rota değişimini [routeListeners] ile öğrenir (dönüş haritası).
 */
object NavRunner {
    val routeListeners = LinkedHashSet<(List<RouteTracker.LatLon>?) -> Unit>()
    private var voice: NavVoice? = null
    private var shownRoute: List<RouteTracker.LatLon>? = null

    private fun publish(r: List<RouteTracker.LatLon>?) {
        shownRoute = r
        routeListeners.toList().forEach { it(r) }
    }

    fun speak(ctx: Context, text: String) {
        if (!ctx.getSharedPreferences("dash", Context.MODE_PRIVATE).getBoolean("nav_voice", true)) return
        (voice ?: NavVoice(ctx.applicationContext).also { voice = it }).say(text)
    }

    /** Navigasyonu bitir (kullanıcı, araç ekranı ya da anahtar reddi): rota çizgisi de kalkar */
    fun stop(ctx: Context) {
        Nav.stop(ctx)
        publish(null)
    }

    /** Konum geldi (ana iş parçacığında). [bearing] GPS yönü, [kmh] GPS hızı. */
    fun step(ctx: Context, p: RouteTracker.LatLon, bearing: Float?, kmh: Double?) {
        val tr = Nav.tracker
        val dest = tr.dest
        Nav.lastFix = p
        Nav.guidance?.onFix(p)
        val ask = tr.onFix(p.lat, p.lon)
        if (dest != null && tr.dest == null) {
            // Hedefe varıldı (ya da süre doldu)
            if (Nav.guidance != null) speak(ctx, t("Hedefe ulaştınız"))
            Nav.stop(ctx)
        } else if (ask && dest != null) {
            if (tr.route != null) speak(ctx, t("Rota yeniden hesaplanıyor"))
            tr.onRequested()
            YandexNav.requestRoute(ctx, p, dest, bearing?.toDouble()?.takeIf { (kmh ?: 0.0) > 6 }) { r ->
                if (Nav.tracker.dest != dest) return@requestRoute   // bu arada hedef değişti/bitti
                YandexNav.unavailable(ctx)?.let { why ->
                    // Anahtar reddedildi: navigasyon biter, harita MapLibre'ye döner
                    stop(ctx)
                    YandexNav.tell(ctx, why)
                    return@requestRoute
                }
                tr.onRoute(r?.points)
                if (r != null) { Nav.guidance = Guidance(r).also { g -> Nav.lastFix?.let(g::onFix) }; Nav.voice.reset() }
                publish(tr.route)
            }
        }
        Nav.guidance?.let { g -> Nav.voice.update(g.nextIndex, g.next, g.nextDistM, kmh)?.let { speak(ctx, it) } }
        if (tr.dest == null && shownRoute != null) publish(null)
        shownRoute = tr.route
    }

    fun shutdownVoice() { voice?.shutdown(); voice = null }
}
