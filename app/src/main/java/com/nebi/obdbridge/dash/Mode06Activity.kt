package com.nebi.obdbridge.dash

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.nebi.obdbridge.BridgeService
import com.nebi.obdbridge.obd.Mode06
import com.nebi.obdbridge.obd.Mode06Reader
import com.nebi.obdbridge.obd.Mode06Report
import com.nebi.obdbridge.obd.Mode06Test
import com.nebi.obdbridge.t

/**
 * Motor testleri (Mode 06): arıza kodları ekranından açılır. Solda durum ve işlemler, sağda izleyicilere göre testler
 * (değer, sınırlar, geçti / sınıra yakın / kaldı çubuğu). Ham kayıt aracın neyi desteklediğini görmek için kaydedilip paylaşılır.
 */
class Mode06Activity : Activity() {
    private lateinit var ui: Ui
    private lateinit var side: LinearLayout
    private lateinit var list: LinearLayout
    private val main = Handler(Looper.getMainLooper())
    private var busy = false
    private var message: String? = null
    private val demo get() = getSharedPreferences("dash", MODE_PRIVATE).getBoolean("demo", false)
    private val timeout = Runnable { if (busy) { busy = false; message = t("Cevap gelmedi. Bağlantıyı kontrol edip yeniden dene."); render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val root = ui.row().apply { setBackgroundColor(Pal.BG); gravity = Gravity.TOP }
        side = ui.column(16)
        root.addView(ScrollView(this).apply { addView(side); isVerticalScrollBarEnabled = false }, LinearLayout.LayoutParams(0, -1, 0.34f))
        list = ui.column(16)
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(0, -1, 0.66f))
        setContentView(root)
        fillCutout(root)
        if (last == null) read() else render()
    }

    override fun onDestroy() { main.removeCallbacks(timeout); super.onDestroy() }

    private fun read() {
        message = null
        if (demo) { last = DEMO.copy(ts = System.currentTimeMillis()); render(); return }
        val ok = BridgeService.requestJob { s ->
            val r = if (s == null) Mode06Report(emptyList(), emptyList(), "", t("Araca bağlı değil"), System.currentTimeMillis())
                else Mode06Reader(s.request, s.isCan, System::currentTimeMillis).run()
            main.post { done(r) }
        }
        if (!ok) { message = t("Araca bağlı değil."); render(); return }
        busy = true
        main.removeCallbacks(timeout); main.postDelayed(timeout, 90_000)
        render()
    }

    private fun done(r: Mode06Report) {
        main.removeCallbacks(timeout)
        busy = false
        message = r.error
        last = r
        render()
    }

    private fun render() {
        if (isFinishing || isDestroyed) return
        side.removeAllViews(); list.removeAllViews()
        val r = last
        side.addView(ui.text(t("‹  Motor testleri"), 22f, bold = true).apply { setPadding(0, ui.dp(4), 0, ui.dp(14)); setOnClickListener { finish() } })
        if (demo) side.addView(ui.text(t("DEMO · örnek değerler, araçtan okunmadı"), 13f, Pal.AMBER).apply { setPadding(0, 0, 0, ui.dp(12)) })
        val fails = r?.tests?.count { it.verdict == Mode06Test.Verdict.FAIL } ?: 0
        val near = r?.tests?.count { it.verdict == Mode06Test.Verdict.NEAR } ?: 0
        side.addView(ui.column(16).apply {
            background = ui.bg(Pal.CARD, 14)
            addView(ui.text(when {
                r == null || r.tests.isEmpty() -> t("Mode 06 test sonuçları")
                fails > 0 -> t("%d test sınır dışında", fails)
                near > 0 -> t("%d test sınıra yakın", near)
                else -> t("Bütün testler sınırlar içinde")
            }, 17f, if (fails > 0) Pal.RED else if (near > 0) Pal.AMBER else Pal.ICE, bold = true))
            addView(ui.text(t("Motor beyninin kendi teşhis testleri: son ölçüm ve izin verilen aralık. Arıza lambası yanmadan önce bozulmayı gösterebilir."),
                14f, Pal.DIM).apply { setPadding(0, ui.dp(6), 0, 0) })
            r?.let { addView(ui.text(t("%1\$d izleyici · %2\$d test", it.supported.size, it.tests.size), 14f, Pal.DIM).apply { setPadding(0, ui.dp(6), 0, 0) }) }
        })
        (if (busy) t("Okunuyor… (araçtaki izleyici sayısına göre yarım dakika sürebilir)") else message)?.let {
            side.addView(ui.text(it, 14f, if (busy) Pal.ICE else Pal.AMBER).apply { setPadding(ui.dp(4), ui.dp(12), ui.dp(4), 0) })
        }
        fun gap() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) }
        side.addView(ui.button(t("Yeniden oku"), primary = true) { if (!busy) read() }.apply { alpha = if (busy) 0.4f else 1f }, gap())
        if (r != null && r.raw.isNotEmpty()) side.addView(ui.button(t("Ham kaydı paylaş")) { shareRaw(r) }, gap())

        if (r == null || r.tests.isEmpty()) {
            list.addView(ui.text(if (busy) t("Okunuyor…") else t("Test sonuçları okununca burada görünür."), 16f, Pal.DIM).apply { setPadding(ui.dp(8), ui.dp(48), 0, 0) })
            return
        }
        for ((mid, tests) in r.tests.groupBy { it.mid }) {
            list.addView(ui.caps(Mode06.midName(mid)).apply { setPadding(ui.dp(6), ui.dp(14), 0, ui.dp(6)) })
            for (x in tests) list.addView(testRow(x), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })
        }
        list.addView(ui.text(t("Sınırları aracın üreticisi belirler. \"Kaldı\" çoğu zaman bir arıza kodu da kaydeder; kesin teşhis için servise danış."),
            12f, Pal.DIM).apply { setPadding(ui.dp(6), ui.dp(16), ui.dp(6), ui.dp(16)) })
    }

    private fun testRow(x: Mode06Test): View {
        val col = ui.column().apply { background = ui.bg(Pal.CARD, 14); setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(12)) }
        val color = when (x.verdict) { Mode06Test.Verdict.FAIL -> Pal.RED; Mode06Test.Verdict.NEAR -> Pal.AMBER; else -> Pal.ICE }
        col.addView(ui.row().apply {
            addView(ui.text(Mode06.tidName(x.tid), 15f), LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.text(when (x.verdict) { Mode06Test.Verdict.FAIL -> t("kaldı"); Mode06Test.Verdict.NEAR -> t("sınıra yakın"); else -> t("geçti") }, 14f, color, bold = true))
        })
        col.addView(RangeBar(this, x, color), LinearLayout.LayoutParams(-1, ui.dp(22)).apply { topMargin = ui.dp(6) })
        col.addView(ui.text(t("Değer %1\$s · aralık %2\$s – %3\$s", num(x.value, x.unit), num(x.min, x.unit), num(x.max, x.unit)), 13f, Pal.DIM)
            .apply { setPadding(0, ui.dp(4), 0, 0) })
        return col
    }

    private fun num(v: Double, unit: String): String {
        val a = kotlin.math.abs(v)
        val s = when { a == 0.0 || a >= 100 -> "%.0f"; a >= 10 -> "%.1f"; a >= 1 -> "%.2f"; else -> "%.3f" }.format(com.nebi.obdbridge.L.locale, v)
        return if (unit.isEmpty()) s else "$s $unit"
    }

    private fun shareRaw(r: Mode06Report) {
        val text = buildString {
            append("OBD Dashboard Mode 06\n")
            append(java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", com.nebi.obdbridge.L.locale).format(java.util.Date(r.ts))).append('\n')
            append(Vehicle.current.let { "${it.fuel} ${if (it.turbo) "turbo" else ""}" }).append("\n\n")
            append(r.raw).append('\n')
            for (x in r.tests) append("%02X/%02X uas %02X: %s [%s .. %s] %s\n".format(java.util.Locale.ROOT, x.mid, x.tid, x.uasid,
                x.value, x.min, x.max, x.verdict))
        }
        try { getExternalFilesDir("mode06")?.let { java.io.File(it, "mode06_%1\$tY%1\$tm%1\$td_%1\$tH%1\$tM%1\$tS.txt".format(java.util.Date(r.ts))).writeText(text) } }
        catch (_: Exception) {}
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), t("Ham kaydı paylaş")))
    }

    /** Aralık çubuğu: en az–en çok bandı, değer işareti; sınıra yakın kısımlar koyu */
    private class RangeBar(ctx: Context, private val x: Mode06Test, private val color: Int) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val span = (x.max - x.min).takeIf { it > 0 } ?: 1.0
            // Değer aralık dışındaysa ölçek onu da kapsasın
            val lo = minOf(x.min, x.value) - span * 0.08; val hi = maxOf(x.max, x.value) + span * 0.08
            fun px(v: Double) = ((v - lo) / (hi - lo) * w).toFloat()
            val cy = h / 2; val bh = h * 0.32f
            p.color = 0xFF2A3038.toInt(); r.set(0f, cy - bh / 2, w, cy + bh / 2); c.drawRoundRect(r, bh / 2, bh / 2, p)
            p.color = 0xFF2F4A5E.toInt(); r.set(px(x.min), cy - bh / 2, px(x.max), cy + bh / 2); c.drawRoundRect(r, bh / 2, bh / 2, p)
            p.color = color; val vx = px(x.value).coerceIn(3f, w - 3f)
            r.set(vx - 3 * resources.displayMetrics.density, 0f, vx + 3 * resources.displayMetrics.density, h); c.drawRoundRect(r, 4f, 4f, p)
        }
    }

    companion object {
        /** Son okuma (sorun bildirimine eklenir) */
        @Volatile var last: Mode06Report? = null

        private val DEMO = Mode06Report(listOf(0x31, 0x85, 0xB0), listOf(
            Mode06Test(0x31, 0x80, 0x24, 412.0, 0.0, 1000.0, ""),
            Mode06Test(0x85, 0x81, 0x99, 12.4, -30.0, 30.0, "kPa"),
            Mode06Test(0x85, 0x82, 0x99, 27.5, -30.0, 30.0, "kPa"),
            Mode06Test(0xB0, 0x80, 0x17, 8.6, 0.0, 25.0, "kPa"),
        ), "")

        /** Sorun bildirimi için kısa özet */
        fun summary(r: Mode06Report): String = buildString {
            append("Mode 06: ").append(r.supported.joinToString(" ") { "%02X".format(it) }).append(" · ").append(r.tests.size).append(" test")
            r.error?.let { append(" · ").append(it) }
            append('\n')
            for (x in r.tests) append("  %02X/%02X uas %02X %s [%s..%s]\n".format(java.util.Locale.ROOT, x.mid, x.tid, x.uasid, x.value, x.min, x.max))
        }
    }
}
