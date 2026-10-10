package com.nebi.obdbridge

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView

/** Navigasyon uygulamasının bildiriminden okunan sıradaki manevra. */
data class NaviState(
    val app: String,
    val distance: String,    // "50 m"
    val street: String,      // "Eken Sok."
    val arrow: Bitmap?,      // manevra oku (navigasyonun kendi çizimi)
    val ts: Long,
)

/**
 * Yandex Navigator (ve Google Haritalar) arka plandayken gösterdiği bildirimden sıradaki dönüşü okur.
 * Resmi bir API yok; bildirim herkese açık bilgidir. Kullanıcı bir kez "bildirim erişimi" vermelidir.
 *
 * Yandex (31.x): android.title = "50 m", android.text = "Eken Sok."; ok resmi yalnız özel bildirim
 * şablonunda (RemoteViews) var. Şablon burada açılıp içindeki en büyük resim ok olarak alınır.
 */
class NaviListener : NotificationListenerService() {

    companion object {
        const val TAG = "ObdNavi"
        private val APPS = setOf("ru.yandex.yandexnavi", "ru.yandex.yandexmaps", "com.google.android.apps.maps")
        @Volatile var state: NaviState? = null
        @Volatile var connected = false

        /** İzin varsa dinleyiciyi bağlamasını iste: Android uygulama güncellenince her zaman kendisi bağlamıyor. */
        fun rebind(ctx: Context) {
            if (!hasAccess(ctx) || connected) return
            val cn = ComponentName(ctx, NaviListener::class.java)
            try {
                // requestRebind tek başına HyperOS'ta güncelleme sonrası yetmedi (2026-10-09):
                // bileşeni kapatıp açmak sistemi dinleyiciyi yeniden bağlamaya zorlar
                val pm = ctx.packageManager
                pm.setComponentEnabledSetting(cn, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP)
                pm.setComponentEnabledSetting(cn, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP)
                requestRebind(cn)
                Log.i(TAG, "dinleyici yeniden bağlanması istendi")
            } catch (e: Exception) {
                Log.w(TAG, "yeniden bağlama: ${e.message}")
            }
        }

        fun hasAccess(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(ctx, NaviListener::class.java).flattenToString()
            return flat.split(':').any { it == me }
        }
    }

    private var dumped = false

    override fun onListenerDisconnected() {
        connected = false
        state = null
        requestRebind(ComponentName(this, NaviListener::class.java))
    }

    override fun onListenerConnected() {
        connected = true
        Log.i(TAG, "bildirim dinleyicisi bağlandı")
        // Bağlanırken zaten süren bir rota varsa onu da al
        try { activeNotifications?.forEach { onNotificationPosted(it) } } catch (_: Exception) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in APPS) return
        val n = sbn.notification
        if (n.category != Notification.CATEGORY_NAVIGATION && sbn.packageName != "com.google.android.apps.maps") return
        val ex: Bundle = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        val arrow = largeIcon(n) ?: arrowFromTemplate(n)
        state = NaviState(sbn.packageName, title, text, arrow, System.currentTimeMillis())
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == state?.app) state = null
    }

    /** Google Haritalar oku büyük simge olarak verir. */
    private fun largeIcon(n: Notification): Bitmap? = try {
        n.getLargeIcon()?.loadDrawable(this)?.let { toBitmap(it) }
    } catch (_: Exception) { null }

    /** Yandex: özel şablonu açıp içindeki en büyük resmi (manevra oku) bul. */
    private fun arrowFromTemplate(n: Notification): Bitmap? {
        @Suppress("DEPRECATION")
        val rv = n.bigContentView ?: n.contentView ?: return null
        return try {
            val v = rv.apply(this, FrameLayout(this))
            val found = ArrayList<Pair<String, Bitmap>>()
            collect(v, found)
            if (!dumped) { dumped = true; dump(found) }
            pickArrow(found)
        } catch (e: Exception) {
            Log.w(TAG, "Bildirim şablonu açılamadı: ${e.message}")
            null
        }
    }

    /** (görünüm kaynak adı, resim) çiftleri; ad bulunamazsa "". */
    private fun collect(v: View, out: MutableList<Pair<String, Bitmap>>) {
        if (v is ImageView && v.visibility == View.VISIBLE) v.drawable?.let { d ->
            val name = try { if (v.id != View.NO_ID) v.resources.getResourceEntryName(v.id) else "" } catch (_: Exception) { "" }
            toBitmap(d)?.let { out += name to it }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i), out)
    }

    /**
     * Yandex şablonunda (31.1.3): "primaryIconTinted" 88x88 manevra oku, 44x44 trafik noktaları,
     * "etaProgressImage" 866x66 varış ilerleme şeridi (2026-10-09). Adında manevra/ok geçen varsa o; yoksa kareye yakın olanların en büyüğü.
     */
    private fun pickArrow(list: List<Pair<String, Bitmap>>): Bitmap? {
        list.firstOrNull { (n, _) -> Regex("primaryIcon|maneuver|manoeuvre|arrow|turn|direction", RegexOption.IGNORE_CASE).containsMatchIn(n) }
            ?.let { return it.second }
        return list.map { it.second }
            .filter { it.width.toFloat() / it.height in 0.6f..1.7f }
            .maxByOrNull { it.width * it.height }
    }

    private fun toBitmap(d: Drawable): Bitmap? {
        if (d is BitmapDrawable) return d.bitmap
        val w = d.intrinsicWidth; val h = d.intrinsicHeight
        if (w <= 0 || h <= 0) return null
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            d.setBounds(0, 0, w, h); d.draw(Canvas(it))
        }
    }

    /** Geliştirme: ilk bildirimde bulunan resimleri kaydet (files/navi/), doğru resmin seçildiğini görmek için. */
    private fun dump(list: List<Pair<String, Bitmap>>) {
        try {
            val dir = java.io.File(getExternalFilesDir(null), "navi").apply { mkdirs() }
            list.forEachIndexed { i, (name, b) ->
                java.io.File(dir, "img_${i}_${name.ifEmpty { "adsiz" }}_${b.width}x${b.height}.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            Log.i(TAG, "${list.size} resim kaydedildi: $dir")
        } catch (e: Exception) {
            Log.w(TAG, "kayıt hatası: ${e.message}")
        }
    }
}
