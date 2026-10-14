package com.nebi.obdbridge

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * Bildirim dinleyicisi: yalnız çalan müziğin oturumlarına erişmek için (MediaHub, MediaSessionManager bunu ister).
 * Bildirim içeriği okunmaz. 2026-10-14'e kadar Yandex Navigator bildiriminden dönüş okunuyordu; artık
 * navigasyon uygulamanın kendisinde (dash/Guidance). Sınıf adı değişmedi: değişirse kullanıcının verdiği izin düşer.
 */
class NaviListener : NotificationListenerService() {

    companion object {
        const val TAG = "ObdNavi"
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

    override fun onListenerDisconnected() {
        connected = false
        requestRebind(ComponentName(this, NaviListener::class.java))
    }

    override fun onListenerConnected() {
        connected = true
        Log.i(TAG, "bildirim dinleyicisi bağlandı")
    }
}
