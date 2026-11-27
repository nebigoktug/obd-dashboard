package com.nebi.obdbridge.dash

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.nebi.obdbridge.R
import com.nebi.obdbridge.t

/**
 * Navigasyon süresince konum (2026-11-27). Önceden GPS'i gösterge ekranı alıyordu: telefon kilitlenince ya da yalnız
 * Android Auto açıkken rota ilerlemiyordu. Bu servis hedef varken çalışır, hedef bitince kendini kapatır.
 * Gösterge ekranı kendi GPS'ini (hız, dönüş haritası) almaya devam eder; navigasyon adımını yalnız bu servis atar.
 */
class NavService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val listener = LocationListener { loc ->
        if (!Nav.active) { stopSelf(); return@LocationListener }
        lastGps = System.currentTimeMillis()
        NavRunner.step(this, RouteTracker.LatLon(loc.latitude, loc.longitude),
            if (loc.hasBearing()) loc.bearing else null, if (loc.hasSpeed()) loc.speed * 3.6 else null)
        updateNotif()
    }
    private var lastGps = 0L
    /** İçeride/garajda GPS yokken: rota yaklaşık konumdan istensin (ekrandaki eski davranış), ilerleme yalnız GPS'le */
    private val coarse = LocationListener { loc -> coarseFix(loc) }

    private fun coarseFix(loc: android.location.Location) {
        if (!Nav.active || System.currentTimeMillis() - lastGps < 15_000) return
        if (Nav.lastFix == null) Nav.lastFix = RouteTracker.LatLon(loc.latitude, loc.longitude)
        if (Nav.guidance == null) { NavRunner.step(this, RouteTracker.LatLon(loc.latitude, loc.longitude), null, null); updateNotif() }
    }
    /** Hedef başka yerden bitirildiyse (araç ekranı, Rotayı bitir) GPS'i bırak */
    private val watch = object : Runnable {
        override fun run() { if (!Nav.active) stopSelf() else handler.postDelayed(this, 3000) }
    }
    private var lastText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, t("Navigasyon"), NotificationManager.IMPORTANCE_LOW))
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, notif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(NOTIF_ID, notif())
        } catch (e: Exception) {
            // Arka plandan başlatılamadı (konum izni yok vb.): navigasyon ekran açıkken eskisi gibi ilerlemez, iz bırak
            Log.w(TAG, "navigasyon servisi başlatılamadı", e); stopSelf(); return
        }
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) { stopSelf(); return }
        if (YandexNav.init(this)) com.yandex.mapkit.MapKitFactory.getInstance().onStart()   // rota istekleri için
        mapkit = YandexNav.inited
        val lm = getSystemService(LocationManager::class.java)
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10_000L, 0f, coarse, Looper.getMainLooper())
            lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }?.let(::coarseFix)
        } catch (e: Exception) { Log.w(TAG, "konum açılamadı", e) }
        handler.post(watch)
    }
    private var mapkit = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) NavRunner.stop(this)
        if (!Nav.active) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        runCatching { getSystemService(LocationManager::class.java).run { removeUpdates(listener); removeUpdates(coarse) } }
        if (mapkit) runCatching { com.yandex.mapkit.MapKitFactory.getInstance().onStop() }
        super.onDestroy()
    }

    private fun text(): String {
        val st = Nav.state() ?: return t("Rota hesaplanıyor…")
        return listOfNotNull(st.distance, st.street.ifEmpty { null }, st.eta).joinToString(" · ")
    }

    private fun updateNotif() {
        val s = text()
        if (s == lastText) return
        lastText = s
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif())
    }

    private fun notif(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, DashboardActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, NavService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_obd)
            .setContentTitle(Nav.destName.ifEmpty { t("Navigasyon") })
            .setContentText(text().also { lastText = it })
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, t("Rotayı bitir"), stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        const val TAG = "NavService"
        private const val CHANNEL = "nav"
        private const val NOTIF_ID = 7
        private const val ACTION_STOP = "com.nebi.obdbridge.NAV_STOP"
        @Volatile var running = false; private set

        /** Hedef varsa servisi başlat (ekrandan, araç ekranından ya da kayıtlı hedefe devam ederken) */
        fun start(ctx: Context) {
            if (!Nav.active || running) return
            try { ctx.startForegroundService(Intent(ctx, NavService::class.java)) }
            catch (e: Exception) { Log.w(TAG, "başlatılamadı", e) }
        }
    }
}
