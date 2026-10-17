package com.nebi.obdbridge.dash

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nebi.obdbridge.obd.CanLearn
import com.nebi.obdbridge.obd.CanRecorder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Öğrenme modu (Geliştirici, ESP32 kutusu): uygulama ne yapılacağını söyler, kullanıcı araçta yapar, uygulama hatta
 * hangi bitlerin değiştiğini bulur. Her görev üç kez denenir; üçünde de değişen bitler kesin sayılır. Kesin sonuç
 * kullanıcıya bir kez daha yaptırılıp "kutucuk yandı mı" diye sorulur.
 *
 * Yalnız dinler. Sonuçlar files/learn/learn.json'da birikir, her değişiklikte İndirilenler/OBD-CAN'a da yazılır.
 */
class LearnActivity : Activity() {
    /** [kind]: PRESS bas-bırak, TOGGLE açık/kapalı arası geçiş (her denemede durum değişir) */
    enum class Kind { PRESS, TOGGLE }
    data class Task(val id: String, val group: String, val title: String, val how: String, val kind: Kind = Kind.PRESS)

    companion object {
        const val FREE = "free"

        /** Önce bilinmeyenler; en sonda bilinenler (yöntemin doğrulaması) */
        val TASKS = listOf(
            Task("seat_drv", "Klima paneli", "Sürücü koltuk ısıtması", "Sürücü koltuk ısıtma düğmesine bir kez bas.", Kind.TOGGLE),
            Task("seat_pas", "Klima paneli", "Yolcu koltuk ısıtması", "Yolcu koltuk ısıtma düğmesine bir kez bas.", Kind.TOGGLE),
            Task("ac", "Klima paneli", "A/C düğmesi", "A/C düğmesine bir kez bas.", Kind.TOGGLE),
            Task("fan_press", "Klima paneli", "Fan düğmesine basma", "Fan düğmesine (çevirmeden) bir kez bas.", Kind.TOGGLE),
            Task("lights_park", "Farlar", "Park lambası", "Far düğmesini bir konum çevir: 0 ↔ park lambası.", Kind.TOGGLE),
            Task("lights_on", "Farlar", "Kısa farlar", "Far düğmesini çevir: park lambası ↔ farlar.", Kind.TOGGLE),
            Task("fog_front", "Farlar", "Ön sis", "Ön sis düğmesine bas (aç ya da kapat).", Kind.TOGGLE),
            Task("fog_rear", "Farlar", "Arka sis", "Arka sis düğmesine bas (aç ya da kapat).", Kind.TOGGLE),
            Task("high_beam", "Farlar", "Uzun far durumu", "Uzun farı aç ya da kapat (kolu ileri it).", Kind.TOGGLE),
            Task("hazard", "Farlar", "Dörtlüler", "Dörtlü düğmesine bas (aç ya da kapat).", Kind.TOGGLE),
            Task("lock_btn", "Kapılar", "Merkezi kilit düğmesi", "İçerideki merkezi kilit düğmesine bas (kilitle ya da aç).", Kind.TOGGLE),
            Task("door_pas", "Kapılar", "Yolcu kapısı", "Yolcu kapısını aç ya da kapat.", Kind.TOGGLE),
            Task("door_rear", "Kapılar", "Arka kapılar", "Arka kapılardan birini aç ya da kapat.", Kind.TOGGLE),
            Task("trunk", "Kapılar", "Bagaj", "Bagajı aç ya da kapat.", Kind.TOGGLE),
            Task("window_drv", "Kapılar", "Sürücü camı", "Sürücü camını kısa bir an indir ya da kaldır."),
            Task("fuel_door", "Kapılar", "Yakıt kapağı", "Yakıt kapağını aç ya da kapat.", Kind.TOGGLE),
            Task("wheel_vol", "Direksiyon", "Direksiyon ses +", "Direksiyondaki ses + tuşuna bir kez bas."),
            Task("wheel_next", "Direksiyon", "Direksiyon ileri", "Direksiyondaki ileri (▶▶) tuşuna bir kez bas."),
            Task("key_4", "Radyo", "Radyo 4", "Radyonun 4 tuşuna bir kez bas."),
            Task("key_5", "Radyo", "Radyo 5", "Radyonun 5 tuşuna bir kez bas."),
            Task("key_6", "Radyo", "Radyo 6", "Radyonun 6 tuşuna bir kez bas."),
            Task("key_7", "Radyo", "Radyo 7", "Radyonun 7 tuşuna bir kez bas."),
            Task("key_8", "Radyo", "Radyo 8", "Radyonun 8 tuşuna bir kez bas."),
            Task("key_9", "Radyo", "Radyo 9", "Radyonun 9 tuşuna bir kez bas."),
            Task("chk_vol", "Doğrulama (bilinenler)", "Ses düğmesi", "Radyonun ses düğmesini bir tık sağa çevir."),
            Task("chk_brake", "Doğrulama (bilinenler)", "Fren pedalı", "Fren pedalına bas ve bırak."),
            Task("chk_read", "Doğrulama (bilinenler)", "READ tuşu", "Sinyal kolunun ucundaki READ tuşuna bas."),
        )

        /** Çözülmüş sinyaller (docs/can-sinyalleri.md): serbest modda ve sonuçlarda ad göstermek için */
        private val KNOWN = listOf(
            K(1, "05704000", 2, 0xC0, "Ses düğmesi çevirme"), K(1, "05704000", 1, 0x10, "TUNING çevirme"),
            K(1, "05704000", 3, 0x0C, "TUNING basma"), K(1, "05704000", 4, 0xC0, "Ses düğmesine basma"),
            K(1, "05704000", 4, 0x03, "EXIT"), K(1, "05704000", 4, 0x30, "PHONE"), K(1, "05704000", 4, 0x0C, "MENU"),
            K(1, "05704000", 5, 0x03, "AM/FM"), K(1, "05704000", 5, 0x0C, "MODE"), K(1, "05704000", 5, 0x30, "Çıkar (⏏)"),
            K(1, "05704000", 5, 0xC0, "ENTER"), K(1, "05704000", 7, 0x0F, "Yön tuşları"),
            K(1, "04A0409E", 6, 0x04, "Radyo rakam tuşu"), K(1, "04A0409E", 5, 0x0F, "Radyo rakam tuşu"),
            K(1, "04A0409E", 4, 0x0C, "AUTO / SCAN"), K(1, "04A0409E", 2, 0x06, "Klima panelinde bir tuş"),
            K(1, "021204B8", 1, 0x38, "Sinyal lambaları"), K(1, "13F041BA", 1, 0x20, "Sürücü kapısı"),
            K(1, "1760E008", 2, 0x08, "El freni"), K(1, "03C3F7FC", 5, 0x40, "READ tuşu"),
            K(1, "12404002", 0, 0xFF, "Klima ısı ayarı"), K(1, "12404002", 1, 0xFF, "Klima ısı ayarı"),
            K(1, "12404002", 3, 0x1F, "Fan seviyesi"), K(1, "12404002", 7, 0x3C, "Hava yönü"),
            K(1, "1C810066", 0, 0x40, "Arka cam ısıtması"), K(1, "1C810066", 6, 0xFF, "İç hava"),
            K(1, "09C050B8", 0, 0xFF, "Gösterge ışığı"),
            K(0, "19000026", 6, 0xF0, "Sinyal kolu"), K(0, "19000026", 5, 0x1C, "Far kolu"),
            K(0, "19000026", 7, 0xF4, "Hız sabitleyici tuşları"), K(0, "19000026", 1, 0xF4, "Hız sabitleyici tuşları"),
            K(0, "0300410E", 4, 0x40, "Fren pedalı"),
        )

        private data class K(val bus: Int, val id: String, val byte: Int, val mask: Int, val name: String)

        fun knownName(b: CanLearn.Bits): String? =
            KNOWN.firstOrNull { it.bus == b.bus && it.id == b.id && it.byte == b.byte && it.mask and b.mask != 0 }?.name
    }

    private enum class St { WAIT_BOX, INTRO, BASELINE, IDLE, COUNTDOWN, TRIAL, VERIFY, DONE }

    private lateinit var ui: Ui
    private lateinit var list: LinearLayout
    private lateinit var pane: LinearLayout
    private lateinit var boxLine: TextView
    private val results = JSONObject()
    private var exportUri: Uri? = null

    private var st = St.INTRO
    private var task: Task = TASKS[0]
    private val trials = ArrayList<List<CanLearn.Change>>()
    private var verdict: CanLearn.Verdict? = null
    private var countdownEnd = 0L
    private var lastBytes = 0L
    private var lastBytesAt = 0L
    private var streamOk = false
    private var seenHits = 0
    private var flashUntil = 0L
    private var freeLast: List<CanLearn.Change> = emptyList()

    // Değişen görünümler
    private var bigText: TextView? = null
    private var smallText: TextView? = null
    private var box: View? = null

    private val ticker = object : Runnable {
        override fun run() { tick(); pane.postDelayed(this, 100) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        load()
        val root = ui.row().apply { setBackgroundColor(Pal.BG); gravity = Gravity.TOP }
        val left = ui.column(16)
        left.addView(ui.text("‹  Öğrenme modu", 22f, light = true).apply {
            setPadding(ui.dp(8), ui.dp(4), 0, ui.dp(6)); setOnClickListener { finish() }
        })
        boxLine = ui.text("", 13f, Pal.DIM).apply { setPadding(ui.dp(8), 0, ui.dp(8), ui.dp(10)) }
        left.addView(boxLine)
        list = ui.column(0)
        left.addView(list)
        root.addView(ScrollView(this).apply { addView(left); isVerticalScrollBarEnabled = false }, LinearLayout.LayoutParams(0, -1, 0.34f))
        pane = ui.column(20)
        root.addView(ScrollView(this).apply { addView(pane) }, LinearLayout.LayoutParams(0, -1, 0.66f))
        setContentView(root)
        fillCutout(root)
        task = TASKS.firstOrNull { !results.has(it.id) } ?: TASKS[0]
        buildList()
        render()
    }

    override fun onResume() { super.onResume(); hideBars(); pane.post(ticker) }

    override fun onPause() { pane.removeCallbacks(ticker); super.onPause() }

    override fun onDestroy() { CanLearn.end(); super.onDestroy() }

    // ---------- akış ----------

    private fun tick() {
        val now = System.currentTimeMillis()
        // Kutudan veri geliyor mu: kayıt baytları artıyor mu
        val b = CanRecorder.bytes
        if (b != lastBytes) { lastBytes = b; lastBytesAt = now }
        val ok = now - lastBytesAt < 3000
        if (ok != streamOk) { streamOk = ok; if (st == St.WAIT_BOX && ok) { st = St.INTRO; render() } }
        boxLine.text = if (ok) "● Kutudan veri geliyor" + (if (CanLearn.phase != CanLearn.Phase.OFF) " · ${CanLearn.frames / 1000}k çerçeve" else "")
            else "○ Kutudan veri yok: uygulama ESP32 kutusuna bağlı olmalı"
        boxLine.setTextColor(if (ok) Pal.ICE else Pal.AMBER)

        when (st) {
            St.BASELINE -> {
                val r = CanLearn.remaining()
                bigText?.text = "${(r + 999) / 1000}"
                if (CanLearn.phase == CanLearn.Phase.READY) { st = St.IDLE; render() }
            }
            St.COUNTDOWN -> {
                val r = countdownEnd - now
                if (r <= 0) {
                    CanLearn.startTrial(); st = St.TRIAL; render()
                } else bigText?.text = "${(r + 999) / 1000}"
            }
            St.TRIAL -> {
                val r = CanLearn.remaining()
                bigText?.text = "Şimdi yap · ${(r + 999) / 1000}"
                CanLearn.trialResult()?.let { onTrial(it) }
            }
            St.VERIFY -> {
                val h = CanLearn.watchHits
                if (h != seenHits) { seenHits = h; flashUntil = now + 500 }
                box?.background = ui.bg(if (now < flashUntil) Pal.ICE else Pal.CARD, 18, if (now < flashUntil) Pal.ICE else Pal.EDGE)
                verdict?.sure?.firstOrNull()?.let { smallText?.text = "Şu anki değer: 0x%02X · %d kez değişti".format(CanLearn.value(it).coerceAtLeast(0), h) }
            }
            else -> {}
        }
    }

    private fun startListening() {
        CanLearn.begin(); trials.clear(); verdict = null
        st = St.BASELINE; render()
    }

    private fun startTrial() {
        if (CanLearn.phase == CanLearn.Phase.OFF) { startListening(); return }
        countdownEnd = System.currentTimeMillis() + 3000
        st = St.COUNTDOWN; render()
    }

    private fun onTrial(r: List<CanLearn.Change>) {
        if (task.id == FREE) { freeLast = r; st = St.IDLE; render(); return }
        trials += r
        if (trials.size < 3) { st = St.IDLE; render(); return }
        val v = CanLearn.combine(trials)
        verdict = v
        if (v.sure.isNotEmpty()) {
            CanLearn.watch(v.sure.first()); seenHits = CanLearn.watchHits
            st = St.VERIFY
        } else st = St.DONE
        render()
    }

    private fun select(t: Task) {
        if (st == St.COUNTDOWN || st == St.TRIAL) return
        task = t; trials.clear(); verdict = null; freeLast = emptyList(); CanLearn.watch(null)
        if (st != St.WAIT_BOX && st != St.INTRO && st != St.BASELINE) st = St.IDLE
        buildList(); render()
    }

    private fun next() {
        val i = TASKS.indexOf(task)
        val n = TASKS.drop(i + 1).firstOrNull { !results.has(it.id) } ?: TASKS.firstOrNull { !results.has(it.id) }
        if (n != null) select(n) else { st = St.IDLE; buildList(); render() }
    }

    // ---------- sonuçlar ----------

    private fun bitsJson(l: List<CanLearn.Bits>) = JSONArray().apply {
        for (b in l) put(JSONObject().put("bus", b.busName).put("id", b.id).put("byte", b.byte).put("mask", "0x%02X".format(b.mask))
            .apply { knownName(b)?.let { put("bilinen", it) } })
    }

    private fun save(status: String, user: Boolean?, name: String? = null, id: String = task.id) {
        val v = verdict
        val o = JSONObject().put("gorev", name ?: task.title).put("nasil", task.how).put("durum", status)
            .put("deneme", trials.size).put("zaman", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        if (user != null) o.put("kullanici_onayi", user)
        if (v != null) { o.put("kesin", bitsJson(v.sure)); o.put("belki", bitsJson(v.maybe)) }
        if (id.startsWith(FREE)) o.put("kesin", bitsJson(freeLast.map { it.bits }))
        results.put(id, o)
        persist()
        CanLearn.watch(null)
    }

    private fun file() = File(File(filesDir, "learn").apply { mkdirs() }, "learn.json")

    private fun load() {
        try { val f = file(); if (f.exists()) { val o = JSONObject(f.readText()); o.keys().forEach { results.put(it, o.get(it)) } } }
        catch (_: Exception) {}
    }

    private fun persist() {
        val text = results.toString(2)
        file().writeText(text)
        exportToDownloads(text)
    }

    /** İndirilenler/OBD-CAN/learn_<oturum>.json: oturum başına bir dosya, her kayıtta üstüne yazılır */
    private fun exportToDownloads(text: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val uri = exportUri ?: contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "learn_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".json")
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OBD-CAN")
            })?.also { exportUri = it } ?: return
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
        } catch (_: Exception) {}
    }

    // ---------- görünüm ----------

    private fun statusMark(id: String): Pair<String, Int> {
        val o = results.optJSONObject(id) ?: return "" to Pal.DIM
        return when (o.optString("durum")) {
            "bulundu" -> (if (o.optBoolean("kullanici_onayi", false)) "✓" else "✓?") to Pal.ICE
            "gorunmuyor" -> "—" to Pal.DIM
            "belirsiz" -> "?" to Pal.AMBER
            "atlandi" -> "»" to Pal.DIM
            else -> "" to Pal.DIM
        }
    }

    private fun buildList() {
        list.removeAllViews()
        val done = TASKS.count { results.has(it.id) }
        list.addView(ui.text("$done / ${TASKS.size} görev", 13f, Pal.DIM).apply { setPadding(ui.dp(8), 0, 0, ui.dp(6)) })
        listRow(Task(FREE, "", "Serbest dinleme", "İstediğin bir şeyi yap; değişen bitler ve bilinen adları gösterilir."), "")
        var group = ""
        for (t in TASKS) {
            if (t.group != group) { group = t.group; list.addView(ui.caps(group).apply { setPadding(ui.dp(8), ui.dp(14), 0, ui.dp(6)) }) }
            listRow(t, t.id)
        }
    }

    private fun listRow(t: Task, id: String) {
        val on = t.id == task.id
        val (mark, color) = if (id.isEmpty()) "" to Pal.DIM else statusMark(id)
        val r = ui.row().apply {
            minimumHeight = ui.dp(44); setPadding(ui.dp(12), 0, ui.dp(12), 0)
            background = if (on) ui.bg(Pal.CARD_ON, 12, Pal.ICE) else null
            setOnClickListener { select(t) }
        }
        r.addView(ui.text(t.title, 15f, if (on) Pal.TEXT else Pal.DIM), LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(ui.text(mark, 15f, color, bold = true))
        list.addView(r, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(2) })
    }

    private fun render() {
        pane.removeAllViews(); bigText = null; smallText = null; box = null
        if (!streamOk && st == St.INTRO && lastBytesAt == 0L) st = St.WAIT_BOX
        val free = task.id == FREE
        pane.addView(ui.caps(if (free) "Serbest dinleme" else task.group))
        pane.addView(ui.text(task.title, 26f, light = true).apply { setPadding(0, ui.dp(4), 0, ui.dp(10)) })
        when (st) {
            St.WAIT_BOX -> {
                para("Uygulama ESP32 kutusuna bağlı değil ya da kutudan veri gelmiyor. Kontağı aç, uygulamanın kutuya bağlanmasını bekle.")
                buttons(ui.button("Yine de başla") { startListening() })
            }
            St.INTRO -> {
                para("Önce 10 saniye hiçbir şeye dokunma: uygulama hattın kendi kendine değişen kısımlarını (sayaçlar, sıcaklıklar) öğrenir.")
                para("Sonra her görev için ne yapacağını yazar, 3'ten geriye sayar, sen 6 saniye içinde yaparsın. Her görev üç kez tekrarlanır.")
                para("Araç dururken kullan. Uygulama yalnız dinler, araca hiçbir şey göndermez.", Pal.DIM)
                buttons(ui.button("Dinlemeye başla", primary = true) { startListening() })
            }
            St.BASELINE -> {
                para("Hiçbir şeye dokunma, hat öğreniliyor…")
                big("10")
            }
            St.IDLE -> {
                para(task.how)
                if (free) {
                    if (freeLast.isNotEmpty()) showChanges(freeLast) else if (trials.isEmpty()) para("Başlata dokun, 3'ten geriye sayınca yap.", Pal.DIM)
                    buttons(ui.button("Dinle (6 sn)", primary = true) { startTrial() },
                        ui.button("Adlandır ve kaydet") { nameFree() }.apply { isEnabled = freeLast.isNotEmpty(); alpha = if (freeLast.isEmpty()) 0.4f else 1f })
                } else {
                    dots()
                    trials.lastOrNull()?.let { showChanges(it) }
                    buttons(ui.button(if (trials.isEmpty()) "Başlat" else "Deneme ${trials.size + 1}", primary = true) { startTrial() },
                        ui.button("Atla") { save("atlandi", null); next() })
                }
            }
            St.COUNTDOWN -> {
                para("Hazır ol: ${task.how}")
                big("3")
            }
            St.TRIAL -> {
                para(task.how)
                big("Şimdi yap")
                if (!free) dots()
            }
            St.VERIFY -> {
                val v = verdict!!
                para("Üç denemede de değişen bulundu:")
                mono(v.sure.joinToString("\n") { line(it) })
                para("Doğrulama: bir kez daha yap. Kutucuk yanıp sönüyor mu?")
                box = View(this).apply { background = ui.bg(Pal.CARD, 18) }
                pane.addView(box, LinearLayout.LayoutParams(ui.dp(120), ui.dp(64)).apply { topMargin = ui.dp(6); bottomMargin = ui.dp(4) })
                smallText = ui.text("", 13f, Pal.DIM).also { pane.addView(it) }
                buttons(ui.button("Evet, yandı", primary = true) { save("bulundu", true); next() },
                    ui.button("Hayır") { save("belirsiz", false); next() },
                    ui.button("Baştan dene") { trials.clear(); verdict = null; CanLearn.watch(null); st = St.IDLE; render() })
            }
            St.DONE -> {
                val v = verdict
                if (v == null || v.maybe.isEmpty()) {
                    para("Üç denemede de bu iki hatta değişen bir şey bulunamadı. Bu sinyal büyük ihtimalle hatta yayınlanmıyor " +
                        "(ya da başka bir hatta, örneğin direksiyon tuşları LIN'de).")
                    buttons(ui.button("Kaydet: görünmüyor", primary = true) { save("gorunmuyor", null); next() },
                        ui.button("Tekrar dene") { trials.clear(); verdict = null; st = St.IDLE; render() })
                } else {
                    para("Kesin sonuç yok; bazı denemelerde değişenler:")
                    mono(v.maybe.joinToString("\n") { line(it) })
                    buttons(ui.button("Bir deneme daha", primary = true) { st = St.IDLE; render() },
                        ui.button("Kaydet: belirsiz") { save("belirsiz", null); next() },
                        ui.button("Baştan") { trials.clear(); verdict = null; st = St.IDLE; render() })
                }
            }
        }
        if (!free) results.optJSONObject(task.id)?.let { o ->
            pane.addView(ui.caps("Önceki sonuç").apply { setPadding(0, ui.dp(22), 0, ui.dp(6)) })
            val arr = o.optJSONArray("kesin")
            val bits = (0 until (arr?.length() ?: 0)).joinToString("\n") { k ->
                val b = arr!!.getJSONObject(k); "${b.optString("bus")} ${b.optString("id")} b${b.optInt("byte")} ${b.optString("mask")}" + (b.optString("bilinen").takeIf { it.isNotEmpty() }?.let { "  ($it)" } ?: "")
            }
            para("${o.optString("durum")} · ${o.optString("zaman")}" + if (bits.isNotEmpty()) "\n$bits" else "", Pal.DIM)
        }
    }

    private fun line(b: CanLearn.Bits) = b.toString() + (knownName(b)?.let { "   ← $it" } ?: "   ← yeni")

    private fun showChanges(r: List<CanLearn.Change>) {
        if (r.isEmpty()) { para("Son denemede değişen bir şey yok.", Pal.DIM); return }
        para("Son denemede değişenler:", Pal.DIM)
        mono(r.sortedWith(compareBy({ knownName(it.bits) == null }, { it.bits.bus }, { it.bits.id })).take(12).joinToString("\n") {
            line(it.bits) + "   (%02X→%02X)".format(it.before.coerceAtLeast(0), it.after.coerceAtLeast(0))
        } + if (r.size > 12) "\n… ${r.size - 12} tane daha" else "")
    }

    private fun nameFree() {
        val input = EditText(this).apply { hint = "ör. Korna"; setTextColor(Pal.TEXT); setHintTextColor(Pal.DIM) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Ne yaptın?").setView(input)
            .setPositiveButton("Kaydet") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "Serbest" }
                save("serbest", null, name, FREE + "_" + System.currentTimeMillis())
                freeLast = emptyList(); render()
            }
            .setNegativeButton("Vazgeç", null).show()
    }

    private fun dots() {
        val r = ui.row().apply { setPadding(0, ui.dp(6), 0, ui.dp(6)) }
        for (i in 0 until 3) {
            val done = i < trials.size
            val found = done && trials[i].isNotEmpty()
            r.addView(ui.text(if (!done) "○" else if (found) "●" else "◌", 22f, if (done) Pal.ICE else Pal.DIM).apply { setPadding(0, 0, ui.dp(10), 0) })
        }
        r.addView(ui.text("Deneme ${trials.size}/3", 14f, Pal.DIM))
        pane.addView(r)
    }

    private fun para(s: String, color: Int = Pal.TEXT) =
        pane.addView(ui.text(s, 17f, color).apply { setPadding(0, ui.dp(4), 0, ui.dp(8)) })

    private fun mono(s: String) = pane.addView(ui.text(s, 14f, Pal.TEXT).apply {
        typeface = android.graphics.Typeface.MONOSPACE; background = ui.bg(Pal.CARD, 12); setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
    }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })

    private fun big(s: String) {
        bigText = ui.text(s, 54f, Pal.ICE, light = true).apply { setPadding(0, ui.dp(8), 0, ui.dp(8)) }
        pane.addView(bigText)
    }

    private fun buttons(vararg b: View) {
        val r = ui.row().apply { setPadding(0, ui.dp(12), 0, 0) }
        for (v in b) r.addView(v, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(10) })
        pane.addView(r)
    }

    @Suppress("DEPRECATION")
    private fun hideBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

}
