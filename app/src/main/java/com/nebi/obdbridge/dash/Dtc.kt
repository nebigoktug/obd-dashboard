package com.nebi.obdbridge.dash

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.LinearLayout
import android.widget.ScrollView
import com.nebi.obdbridge.BridgeService
import com.nebi.obdbridge.obd.DtcReport

// Arıza kodu ekranı (2026-10-11, forum sürümü): oku, Türkçe açıkla, paylaş, onaylı sil.

/**
 * Kod → Türkçe açıklama. Tablo (assets/dtc_tr.tsv) SAE J2012 genel kod başlıklarından derlendi;
 * yalnız motor beyninin döndürebileceği P ve U kodları. Tabloda olmayanlara kodun yapısından alan adı verilir.
 */
object DtcCatalog {
    @Volatile private var table: Map<String, String> = emptyMap()

    fun load(ctx: Context) {
        if (table.isNotEmpty()) return
        table = try { ctx.assets.open("dtc_tr.tsv").bufferedReader().use { parse(it.readLines()) } } catch (_: Exception) { emptyMap() }
    }

    fun parse(lines: List<String>): Map<String, String> =
        lines.mapNotNull { l -> l.split('\t', limit = 2).takeIf { it.size == 2 && it[0].length == 5 }?.let { it[0].uppercase() to it[1].trim() } }.toMap()

    data class Info(val text: String, val known: Boolean, val generic: Boolean, val area: String)

    fun describe(code: String, t: Map<String, String> = table): Info {
        val c = code.uppercase()
        val generic = isGeneric(c)
        val area = area(c)
        val known = t[c]
        return when {
            known != null -> Info(known, true, generic, area)
            generic -> Info("Genel kod, açıklaması listede yok ($area)", false, true, area)
            else -> Info("Üreticiye özel kod ($area). Anlamı markaya göre değişir.", false, false, area)
        }
    }

    /** SAE: P0, P2, P34–P39, U0, U3 dışı... Basitçe: P0/P2/U0 genel, P1/P3/U1/U2/U3 üreticiye özel. */
    fun isGeneric(c: String) = c.length == 5 && when (c[0]) {
        'P' -> c[1] == '0' || c[1] == '2' || (c[1] == '3' && c[2] in "456789")
        'U' -> c[1] == '0'
        'C', 'B' -> c[1] == '0'
        else -> false
    }

    fun area(c: String): String = when (c.firstOrNull()) {
        'P' -> when (c.getOrNull(2)) {
            '0', '1' -> "yakıt ve hava ölçümü"
            '2' -> "yakıt ve hava ölçümü, enjektörler"
            '3' -> "ateşleme / tekleme"
            '4' -> "emisyon kontrol"
            '5' -> "hız ve rölanti kontrolü"
            '6' -> "motor beyni ve çıkış devreleri"
            '7', '8', '9' -> "şanzıman"
            'A' -> "hibrit sistem"
            else -> "motor ve şanzıman"
        }
        'U' -> "modüller arası iletişim"
        'C' -> "şasi (ABS, direksiyon)"
        'B' -> "gövde (hava yastığı, klima, kapılar)"
        else -> "bilinmeyen"
    }

    /** Forumda sormak için düz metin. */
    fun shareText(r: DtcReport, vehicle: String = "", t: Map<String, String> = table): String = buildString {
        append("OBD Dashboard arıza kodu raporu\n")
        append(java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale("tr")).format(java.util.Date(r.ts))).append('\n')
        if (vehicle.isNotEmpty()) append(vehicle).append('\n')
        append("Arıza lambası: ").append(when (r.mil) { true -> "YANIYOR"; false -> "sönük"; null -> "bilinmiyor" }).append('\n')
        r.readyTotal?.let { append("Hazırlık testleri: ${r.readyDone}/$it tamam\n") }
        for ((title, codes) in sections(r)) {
            append('\n').append(title).append(":\n")
            if (codes.isEmpty()) append("  yok\n")
            for (c in codes) append("  ").append(c).append(" - ").append(describe(c, t).text).append('\n')
        }
    }

    fun sections(r: DtcReport): List<Pair<String, List<String>>> = buildList {
        add("Kayıtlı" to r.stored)
        add("Bekleyen" to r.pending)
        r.permanent?.let { add("Kalıcı" to it) }
    }
}

/** Arıza kodları: Detay sekmesinden açılır. Solda durum ve işlemler, sağda kodlar. */
class DtcActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var side: LinearLayout
    private lateinit var list: LinearLayout
    private val main = Handler(Looper.getMainLooper())
    private var busy: String? = null
    private var message: String? = null
    private val timeout = Runnable { if (busy != null) { busy = null; message = "Cevap gelmedi. Bağlantıyı kontrol edip yeniden deneyin."; render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        DtcCatalog.load(this)
        VehicleStore.load(this)
        val root = ui.row().apply { setBackgroundColor(Pal.BG); gravity = Gravity.TOP }
        side = ui.column(16)
        root.addView(ScrollView(this).apply { addView(side); isVerticalScrollBarEnabled = false }, LinearLayout.LayoutParams(0, -1, 0.34f))
        list = ui.column(16)
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(0, -1, 0.66f))
        setContentView(root)
        if (last == null || System.currentTimeMillis() - last!!.ts > 60_000) read(false) else render()
    }

    override fun onResume() {
        super.onResume()
        hideBars()
    }

    override fun onDestroy() {
        main.removeCallbacks(timeout)
        super.onDestroy()
    }

    private val demo get() = getSharedPreferences("dash", MODE_PRIVATE).getBoolean("demo", false)

    private fun read(clear: Boolean) {
        message = null
        if (demo) {
            last = if (clear) DEMO.copy(stored = emptyList(), pending = emptyList(), mil = false, readyDone = 2, cleared = true, ts = System.currentTimeMillis())
                else DEMO.copy(ts = System.currentTimeMillis())
            render(); return
        }
        val ok = BridgeService.requestDtc(clear) { r -> main.post { done(r) } }
        if (!ok) { message = "Araca bağlı değil."; render(); return }
        busy = if (clear) "Kodlar siliniyor…" else "Kodlar okunuyor…"
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, 30_000)
        render()
    }

    private fun done(r: DtcReport) {
        main.removeCallbacks(timeout)
        busy = null
        message = r.error ?: when (r.cleared) {
            true -> "Kodlar silindi. Arıza giderilmediyse kod birkaç sürüş içinde geri gelir."
            false -> "Motor beyni silmeyi reddetti. Motor çalışıyorsa durdurun, kontağı açık bırakıp yeniden deneyin."
            null -> null
        }
        if (r.error == null) last = r
        render()
    }

    private fun render() {
        if (isFinishing || isDestroyed) return
        side.removeAllViews(); list.removeAllViews()
        val r = last
        side.addView(ui.text("‹  Arıza kodları", 22f, bold = true).apply {
            setPadding(0, ui.dp(4), 0, ui.dp(14)); setOnClickListener { finish() }
        })
        val mil = r?.mil
        side.addView(statusCard(
            when (mil) { true -> "Arıza lambası yanıyor"; false -> "Arıza lambası sönük"; null -> "Arıza lambası: bilinmiyor" },
            when (mil) { true -> Pal.RED; false -> Pal.ICE; null -> Pal.DIM },
            listOfNotNull(
                r?.let { "Son okuma: " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale("tr")).format(java.util.Date(it.ts)) },
                r?.readyTotal?.let { "Hazırlık testleri: ${r.readyDone}/$it tamam" },
            ).joinToString("\n").ifEmpty { "Henüz okunmadı" },
        ))
        (busy ?: message)?.let { side.addView(ui.text(it, 14f, if (busy != null) Pal.ICE else Pal.AMBER).apply { setPadding(ui.dp(4), ui.dp(12), ui.dp(4), 0) }) }
        fun gap() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) }
        val idle = busy == null
        side.addView(ui.button("Yeniden oku", primary = true) { if (idle) read(false) }.apply { alpha = if (idle) 1f else 0.4f }, gap())
        if (!demo && !BridgeService.running) side.addView(ui.button("Bağlan") { if (Conn.start(this)) { message = "Bağlanıyor… Bağlanınca Yeniden oku'ya dokunun."; render() } }, gap())
        if (r != null) {
            side.addView(ui.button("Paylaş") { share(r) }, gap())
            if (r.stored.isNotEmpty() || r.pending.isNotEmpty() || mil == true) side.addView(ui.button("Kodları sil") { if (idle) confirmClear() }.apply { setTextColor(Pal.RED) }, gap())
        }

        if (r == null) {
            list.addView(ui.text(if (busy != null) "Okunuyor…" else "Kod listesi okununca burada görünür.", 16f, Pal.DIM).apply { setPadding(ui.dp(8), ui.dp(48), 0, 0) })
            return
        }
        val total = r.stored.size + r.pending.size + (r.permanent?.size ?: 0)
        if (total == 0) {
            list.addView(ui.text("✓  Arıza kodu yok", 24f, Pal.ICE).apply { setPadding(ui.dp(8), ui.dp(40), 0, ui.dp(8)) })
            list.addView(ui.text("Motor beyni kayıtlı, bekleyen ya da kalıcı kod bildirmedi.", 15f, Pal.DIM).apply { setPadding(ui.dp(8), 0, 0, 0) })
        }
        for ((title, codes) in DtcCatalog.sections(r)) {
            if (codes.isEmpty()) continue
            list.addView(ui.caps("$title · ${codes.size}").apply { setPadding(ui.dp(6), ui.dp(14), 0, ui.dp(6)) })
            list.addView(ui.text(HINTS[title] ?: "", 13f, Pal.DIM).apply { setPadding(ui.dp(6), 0, 0, ui.dp(6)) })
            for (c in codes) list.addView(codeRow(c, title), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })
        }
        list.addView(ui.text("Açıklamalar genel SAE tanımlarıdır. Kesin teşhis için servise ya da markanın forumuna danışın.", 12f, Pal.DIM)
            .apply { setPadding(ui.dp(6), ui.dp(16), ui.dp(6), ui.dp(16)) })
    }

    private fun statusCard(title: String, color: Int, body: String) = ui.column(16).apply {
        background = ui.bg(Pal.CARD, 14)
        addView(ui.text(title, 17f, color, bold = true))
        addView(ui.text(body, 14f, Pal.DIM).apply { setPadding(0, ui.dp(6), 0, 0) })
    }

    private fun codeRow(code: String, kind: String): View {
        val info = DtcCatalog.describe(code)
        val row = ui.row().apply {
            background = ui.bg(Pal.CARD, 14); setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(14)); minimumHeight = ui.dp(72)
        }
        row.addView(ui.text(code, 22f, if (kind == "Kayıtlı") Pal.AMBER else Pal.TEXT, bold = true).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }, LinearLayout.LayoutParams(ui.dp(104), -2))
        val col = ui.column()
        col.addView(ui.text(info.text, 16f, if (info.known) Pal.TEXT else Pal.DIM))
        if (info.known) col.addView(ui.text(info.area.replaceFirstChar { it.uppercase() } + if (info.generic) " · genel kod" else "", 13f, Pal.DIM).apply { setPadding(0, ui.dp(3), 0, 0) })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun confirmClear() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Arıza kodları silinsin mi?")
            .setMessage("• Kayıtlı ve bekleyen kodlar ile arıza lambası sıfırlanır.\n" +
                "• Emisyon hazırlık testleri de sıfırlanır; muayeneden hemen önce silmeyin.\n" +
                "• Arıza giderilmediyse kod geri gelir.\n\n" +
                "Önce Paylaş ile kodları kaydetmeniz önerilir. Kontak açık, motor kapalıyken silin.")
            .setPositiveButton("Sil") { _, _ -> read(true) }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun share(r: DtcReport) {
        val text = DtcCatalog.shareText(r, "")
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Arıza kodlarını paylaş"))
    }

    private fun hideBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else @Suppress("DEPRECATION") {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    companion object {
        /** Son okuma: ekran kapanıp açılınca yeniden sorulmasın (1 dk) */
        @Volatile var last: DtcReport? = null

        private val HINTS = mapOf(
            "Kayıtlı" to "Onaylanmış arızalar. Arıza lambasını bunlar yakar.",
            "Bekleyen" to "Bir kez görülmüş, henüz onaylanmamış. Tekrarlarsa kayıtlıya geçer.",
            "Kalıcı" to "Silinemez; arıza giderilip araç kendi testlerini bitirince kendiliğinden kalkar.",
        )

        private val DEMO = DtcReport(listOf("P0341", "U300B"), listOf("P2002"), emptyList(), false, 5, 7, ts = 0)
    }
}
