package com.nebi.obdbridge.dash

import android.content.Context
import android.os.Build
import com.nebi.obdbridge.BridgeService
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Sorun bildirimi (forum sürümü, 2026-10-11): kullanıcının geliştiriciye gönderdiği tek .zip.
 * İçinde: bilgi.txt (sürümler, araç profili, bağlantı özeti, son arıza kodu okuması), uygulama logu,
 * son araç testi raporu ve son 3 sürüş kaydı. Şasi no (VIN) ve adaptörün tam MAC adresi çıkarılır;
 * sürüş kayıtlarında konum yok (yalnız GPS hızı).
 */
object ProblemReport {
    /**
     * Bildirimlerin gideceği destek adresi (yalnız bu uygulama için açılan ayrı bir e-posta; kişisel adres değil).
     * Boşsa kullanıcı alıcıyı kendisi seçer.
     */
    const val SUPPORT_EMAIL = ""

    fun build(ctx: Context, note: String): File {
        val out = File(ctx.cacheDir, "share/obd_dashboard_sorun.zip").apply { parentFile?.mkdirs() }
        val files = ctx.getExternalFilesDir(null)
        ZipOutputStream(out.outputStream()).use { z ->
            fun put(name: String, bytes: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
            put("bilgi.txt", info(ctx, note).toByteArray())
            put("uygulama_log.txt", logcat().toByteArray())
            File(files, "probe").listFiles { f -> f.name.endsWith(".json") }?.maxByOrNull { it.name }?.let {
                put("arac_testi.json", maskVin(it.readText()).toByteArray())
            }
            File(files, "logs").listFiles { f -> f.name.endsWith(".csv") }?.sortedBy { it.name }?.takeLast(3)?.forEach {
                put("surus/" + it.name, it.readBytes())
            }
        }
        return out
    }

    private fun info(ctx: Context, note: String): String = buildString {
        val pkg = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0) } catch (_: Exception) { null }
        append("OBD Dashboard sorun bildirimi\n")
        append(java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss", java.util.Locale("tr")).format(java.util.Date())).append("\n\n")
        append("Kullanıcının notu:\n").append(note.ifBlank { "(yok)" }).append("\n\n")
        append("Uygulama: ").append(pkg?.versionName ?: "?").append(" (").append(pkg?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() } ?: 0).append(")\n")
        append("Telefon: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" · Android ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        append("Araç profili: ").append(Vehicle.current.toJson()).append('\n')
        val p = Conn.prefs(ctx)
        append("Bağlantı: ").append(p.getString(BridgeService.PREF_LINK_TYPE, "SPP")).append(" · ")
            .append(maskMac(p.getString(BridgeService.EXTRA_ADDR, null) ?: p.getString(BridgeService.PREF_WIFI_HOST, null) ?: "-")).append('\n')
        append("Servis: ").append(if (BridgeService.running) "çalışıyor" else "kapalı").append(" · ").append(BridgeService.status).append("\n\n")
        append(BridgeService.diagnostics() ?: "Motor: bağlı değil\n")
        val ins = DashboardActivity.insights
        append("\nÖğrenilenler: yakıt K=%.2f (%d dolum) · rölanti %.0f rpm, yük %%%.0f (n=%d)\n".format(java.util.Locale.US,
            ins.fuel.k, ins.fuel.calibrations, ins.health.idleRpm, ins.health.idleLoad, ins.health.idleN))
        DtcActivity.last?.let { append('\n').append(DtcCatalog.shareText(it)) }
    }

    /**
     * Uygulamanın logu: kendi etiketimiz tümüyle, diğerlerinden yalnız uyarı/hata (sistem gürültüsü olmasın).
     * Uygulama yalnız kendi UID'inin loglarını görür; önceki süreçlerin çökme kayıtları da gelir.
     */
    private fun logcat(): String = try {
        val proc = ProcessBuilder("logcat", "-d", "-t", "5000", "-v", "time", "*:W", "${BridgeService.TAG}:V", "${com.nebi.obdbridge.NaviListener.TAG}:V")
            .redirectErrorStream(true).start()
        proc.inputStream.bufferedReader().use { it.readText() }.ifEmpty { "(boş)" }
    } catch (e: Exception) { "Log okunamadı: ${e.message}" }

    /** VIN: düz metin 17 karakter ve 0902 ham cevabı (hex) çıkarılır; üretici kodu (ilk 3 harf) kalır. */
    fun maskVin(json: String): String = json
        .replace(Regex("\"vin\"\\s*:\\s*\"([A-HJ-NPR-Z0-9]{3})[A-HJ-NPR-Z0-9]{14}\"")) { "\"vin\":\"${it.groupValues[1]}**************\"" }
        .replace(Regex("\"raw_0902\"\\s*:\\s*\"[^\"]*\""), "\"raw_0902\":\"(gizlendi)\"")

    /** 00:11:22:33:44:55 → 00:11:22:**:**:** (üretici kısmı kalır) */
    fun maskMac(s: String): String = Regex("^([0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2})(:[0-9A-Fa-f]{2}){3}$").replace(s) { it.groupValues[1] + ":**:**:**" }
}
