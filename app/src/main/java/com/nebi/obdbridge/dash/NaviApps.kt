package com.nebi.obdbridge.dash

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Navigasyon uygulamaları (2026-10-11): ana ekrandaki "Harita" düğmesi ve Ev/İş rotaları seçili uygulamayla açılır.
 * Dönüş oku yalnız Yandex ve Google Haritalar bildiriminden okunabiliyor (NaviListener); Waze'de ok yok.
 */
/** [tested]: dönüş oku gerçek sürüşte denendi (2026-10-11: yalnız Yandex Navigator) */
enum class NaviApp(val pkg: String, val label: String, val arrows: Boolean, val tested: Boolean = false) {
    YANDEX_NAVI("ru.yandex.yandexnavi", "Yandex Navigator", true, tested = true),
    GOOGLE_MAPS("com.google.android.apps.maps", "Google Haritalar", true),
    YANDEX_MAPS("ru.yandex.yandexmaps", "Yandex Haritalar", true),
    WAZE("com.waze", "Waze", false);

    /** "enlem,boylam"a rota başlatan intent */
    fun route(lat: String, lon: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(when (this) {
        YANDEX_NAVI -> "yandexnavi://build_route_on_map?lat_to=$lat&lon_to=$lon"
        GOOGLE_MAPS -> "google.navigation:q=$lat,$lon&mode=d"
        YANDEX_MAPS -> "yandexmaps://maps.yandex.ru/?rtext=~$lat,$lon&rtt=auto"
        WAZE -> "https://waze.com/ul?ll=$lat,$lon&navigate=yes"
    })).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    companion object {
        fun installed(ctx: Context) = entries.filter { ctx.packageManager.getLaunchIntentForPackage(it.pkg) != null }

        /** Seçili uygulama; seçilmemişse ya da kaldırılmışsa yüklü olanların ilki (öncelik sırası yukarıdaki) */
        fun current(ctx: Context): NaviApp? {
            val inst = installed(ctx)
            val chosen = ctx.getSharedPreferences("dash", Context.MODE_PRIVATE).getString("navi_app", null)
            return inst.firstOrNull { it.name == chosen } ?: inst.firstOrNull()
        }

        fun open(ctx: Context): Boolean {
            val app = current(ctx) ?: return false
            ctx.startActivity(ctx.packageManager.getLaunchIntentForPackage(app.pkg)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        }

        fun routeTo(ctx: Context, latLon: String): Boolean {
            val (lat, lon) = latLon.split(',').map { it.trim() }.takeIf { it.size == 2 } ?: return false
            val app = current(ctx) ?: return false
            // Yuvarlak haritadaki rota çizgisi aynı hedefe (Navigator rotasını vermez)
            val la = lat.toDoubleOrNull(); val lo = lon.toDoubleOrNull()
            if (la != null && lo != null) DashboardActivity.route.setDest(la, lo)
            return try { ctx.startActivity(app.route(lat, lon)); true } catch (_: Exception) { open(ctx) }
        }
    }
}
