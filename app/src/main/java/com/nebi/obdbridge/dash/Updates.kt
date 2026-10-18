package com.nebi.obdbridge.dash

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.nebi.obdbridge.BridgeService
import com.nebi.obdbridge.obd.EngineState

/**
 * Güncelleme kontrolü (forum sürümü, 2026-10-11): GitHub Releases'teki son sürümün etiketi (v1.2) uygulamanınkinden
 * yeniyse haber verir. İndirmeyi ve kurmayı kullanıcı yapar (sürüm sayfası tarayıcıda açılır); uygulama APK indirmez.
 * Günde en çok bir istek; Ayarlar'dan kapatılabilir.
 */
object Updates {
    const val REPO = "nebigoktug/obd-dashboard"
    const val PAGE = "https://github.com/$REPO/releases/latest"
    private const val DAY = 24 * 3600_000L

    data class Release(val version: String, val url: String, val notes: String)

    /** "v1.10" > "1.9"; sayı olmayan ekler (-beta) yok sayılır */
    fun newer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(latest); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun current(ctx: Context): String = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0" } catch (_: Exception) { "0" }

    /** Ağ isteği (arka plan thread'inde çağrılır). Hata ya da sürüm yoksa null. */
    fun fetch(): Release? = try {
        val con = (java.net.URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        if (con.responseCode != 200) null else {
            val o = org.json.JSONObject(con.inputStream.bufferedReader().use { it.readText() })
            Release(o.getString("tag_name").removePrefix("v"), o.optString("html_url", PAGE), o.optString("body", "").take(600))
        }
    } catch (_: Exception) { null }

    /**
     * Dashboard açılışında: otomatik denetim açıksa ve son denetimden bir gün geçtiyse sorar; yeni sürüm varsa
     * ve kullanıcı bu sürümü atlamadıysa pencere gösterir. Araç giderken pencere açılmaz (bir sonraki açılışa kalır).
     */
    fun checkDaily(a: Activity) {
        val p = a.getSharedPreferences("dash", Context.MODE_PRIVATE)
        if (!p.getBoolean("upd_auto", true)) return
        val now = System.currentTimeMillis()
        if (now - p.getLong("upd_checked", 0) < DAY) return
        Thread {
            val r = fetch() ?: return@Thread
            p.edit().putLong("upd_checked", now).putString("upd_latest", r.version).apply()
            if (!newer(r.version, current(a)) || p.getString("upd_skip", null) == r.version) return@Thread
            a.runOnUiThread { if (!a.isFinishing && !driving()) offer(a, r) }
        }.start()
    }

    private fun driving() = BridgeService.engineStatus?.state == EngineState.LIVE && (BridgeService.latest["speed"]?.value ?: 0.0) > 3

    fun offer(a: Activity, r: Release) {
        AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Yeni sürüm: ${r.version}")
            .setMessage((if (r.notes.isNotBlank()) r.notes + "\n\n" else "") +
                "Sürüm sayfası açılır; APK'yı indirip kur (çoğu telefon: arm64-v8a). Ayarların ve kayıtların korunur.")
            .setPositiveButton("İndir") { _, _ -> open(a, r.url) }
            .setNeutralButton("Bu sürümü atla") { _, _ ->
                a.getSharedPreferences("dash", Context.MODE_PRIVATE).edit().putString("upd_skip", r.version).apply()
            }
            .setNegativeButton("Sonra", null)
            .show()
    }

    fun open(ctx: Context, url: String = PAGE) {
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
    }
}
