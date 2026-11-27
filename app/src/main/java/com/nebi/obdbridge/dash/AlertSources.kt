package com.nebi.obdbridge.dash

import android.content.Context
import android.content.SharedPreferences
import com.nebi.obdbridge.t

/**
 * Uyarı hapının Insights dışındaki kaynakları (arıza lambası, bağlantı durumu) ve ayardan süzme.
 * Telefondaki Sürüş ekranı ve araç ekranı (Android Auto) aynı listeyi gösterir.
 */
class AlertSources(private val ctx: Context, private val prefs: SharedPreferences) {
    private var connText: String? = null
    private var connAt = 0L

    /** Ayarda açık olan Insights uyarıları + arıza lambası + bağlantı; önceliğe göre */
    fun all(model: DashModel, ins: Insights): List<Alert> {
        val shown = ins.alerts.filter { prefs.getBoolean("alert_${it.kind.name}", true) }
        return (shown + listOfNotNull(mil(model), conn(model))).sortedBy { it.priority }
    }

    fun mil(model: DashModel): Alert? {
        if (!prefs.getBoolean("alert_MIL", true)) return null
        val mil = model.raw("mil")?.takeIf { System.currentTimeMillis() - it.ts < 120_000 }?.value ?: return null
        if (mil == 0.0) return null
        val n = model.raw("dtc_count")?.value?.toInt()?.takeIf { it > 0 }
        return Alert(AlertKind.MIL, t("Arıza lambası yanıyor") + (n?.let { t(" · %d kod", it) } ?: ""), Level.WARN, 0)
    }

    /** Araçtan veri gelmiyorsa nedenini hapta yaz (en düşük öncelik); saniyede bir hesaplanır */
    fun conn(model: DashModel): Alert? {
        val now = System.currentTimeMillis()
        if (now - connAt > 1000) {
            connAt = now
            // GPS varken de göster (QC2): arabada GPS hep var, adaptör bağlanmadıysa görünmeliydi. Yalnız OBD canlıyken gizli
            connText = if (model.source.name == "Canlı" || prefs.getBoolean("demo", false)) null
            else when (val st = Conn.status(ctx)) {
                t("Canlı") -> null
                t("Bağlı değil") -> t("Adaptör bağlı değil · bağlanmak için dokun")
                t("Adaptör seçilmedi") -> t("Adaptör seçilmedi · seçmek için dokun")
                else -> st
            }
        }
        return connText?.let { Alert(AlertKind.CONN, it, Level.NORMAL, 9) }
    }
}
