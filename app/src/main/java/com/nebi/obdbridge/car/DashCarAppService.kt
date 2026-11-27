package com.nebi.obdbridge.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Android Auto girişi (İş 3, keşif 2026-11-27). Şablon dışı çizime ana ünite yalnız navigasyon yüzeyinde izin verir:
 * uygulama navigasyon kategorisinde, gösterge NavigationTemplate'in yüzeyine çizilir ([DashCarScreen]).
 * Veri telefondaki köprü servisinden okunur (BridgeService.latest); araç tarafı ikinci bir OBD bağlantısı açmaz.
 * Play'den gelmeyen kurulumda Android Auto'nun geliştirici ayarında "Bilinmeyen kaynaklar" açık olmalı.
 */
class DashCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        // Debug: DHU ve her ana ünite; yayın: kütüphanenin bildiği Google ana üniteleri (Android Auto, emülatör)
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(applicationContext).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = DashCarScreen(carContext)
    }
}
