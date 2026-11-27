package com.nebi.obdbridge.dash

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nebi.obdbridge.BridgeService
import com.nebi.obdbridge.obd.TerminalSession
import com.nebi.obdbridge.t

/**
 * Ham terminal (geliştirici, Ayarlar > Gelişmiş): komut yaz, adaptörün ham cevabını gör. Mode 21/22 araştırması ve adaptör
 * sorunları için. Açıkken canlı gösterge sorgusu durur; ekran kapanınca kendiliğinden devam eder (adaptör yeniden kurulur).
 * Yalnız okuma komutları gider (obd/Terminal.kt TerminalGuard). Oturum .txt olarak files/terminal/'e yazılır.
 */
class TerminalActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var out: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var historyRow: LinearLayout
    private val main = Handler(Looper.getMainLooper())
    private val log = StringBuilder()
    private var session: TerminalSession? = null
    private val prefs by lazy { getSharedPreferences("dash", MODE_PRIVATE) }
    private val history: MutableList<String> by lazy {
        prefs.getString("term_history", null)?.split('\n')?.filter { it.isNotBlank() }?.toMutableList() ?: mutableListOf("ATI", "ATDP", "ATRV", "0100", "0902")
    }
    private val started = System.currentTimeMillis()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val root = ui.column(14).apply { setBackgroundColor(Pal.BG) }
        root.addView(ui.row().apply {
            addView(ui.text(t("‹  Ham terminal"), 20f, bold = true).apply { setOnClickListener { finish() } }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.button(t("Kopyala")) { copy() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(8) })
            addView(ui.button(t("Paylaş")) { share() })
        })
        out = ui.text("", 14f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        scroll = ScrollView(this).apply { addView(out); background = ui.bg(Pal.CARD, 12); setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.dp(10); bottomMargin = ui.dp(10) })
        historyRow = ui.row()
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(historyRow) })
        input = EditText(this).apply {
            hint = t("Komut (ör. 010C, 22F190, ATDP)")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            typeface = Typeface.MONOSPACE; setTextColor(Pal.TEXT); setHintTextColor(Pal.DIM); isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_FULLSCREEN
            setOnEditorActionListener { _, id, ev -> if (id == EditorInfo.IME_ACTION_SEND || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { submit(); true } else false }
        }
        root.addView(ui.row().apply {
            addView(input, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.button(t("Gönder"), primary = true) { submit() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(8) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        setContentView(root)
        fillCutout(root)
        renderHistory()
        line(t("Uyarı: yalnız okuma komutları gönderilir; silme, yazma ve sıfırlama engelli. Terminal açıkken gösterge durur."))
        open()
    }

    private fun open() {
        val s = TerminalSession { cmd, answer -> main.post { line("> $cmd"); line(answer.trim().ifEmpty { "(boş)" }) } }
        val ok = BridgeService.requestJob(reinit = true) { sess ->
            if (sess == null) { main.post { line(t("Araca bağlı değil.")) }; return@requestJob }
            main.post { line(t("Bağlandı · %s", if (sess.isCan) "CAN" else t("CAN değil"))) }
            s.run(sess.request)
            main.post { if (!isFinishing) line(t("Oturum kapandı (10 dk komut gelmedi). Yeniden açmak için ekrandan çıkıp gir.")) }
        }
        if (!ok) { line(t("Araca bağlı değil. Önce adaptöre bağlan.")); return }
        session = s
    }

    private fun submit() {
        val cmd = input.text.toString().trim().uppercase()
        if (cmd.isEmpty()) return
        val s = session
        if (s == null || s.closed) { line(t("Oturum yok.")); return }
        s.send(cmd)?.let { why -> line("> $cmd"); line("✕ $why"); return }
        input.setText("")
        history.remove(cmd); history.add(0, cmd)
        while (history.size > 12) history.removeAt(history.size - 1)
        prefs.edit().putString("term_history", history.joinToString("\n")).apply()
        renderHistory()
    }

    private fun renderHistory() {
        historyRow.removeAllViews()
        for (h in history) historyRow.addView(ui.text(h, 14f, Pal.ICE).apply {
            typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
            background = ui.bg(Pal.CARD, 10); setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6))
            setOnClickListener { input.setText(h); input.setSelection(h.length) }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(6) })
    }

    private fun line(s: String) {
        log.append(s).append('\n')
        out.append(s + "\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun copy() {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("OBD terminal", log.toString()))
        toast(t("Kopyalandı"))
    }

    private fun save(): String {
        val text = "OBD Dashboard terminal\n" +
            java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", com.nebi.obdbridge.L.locale).format(java.util.Date(started)) + "\n\n" + log
        try { getExternalFilesDir("terminal")?.let { java.io.File(it, "terminal_%1\$tY%1\$tm%1\$td_%1\$tH%1\$tM%1\$tS.txt".format(java.util.Date(started))).writeText(text) } }
        catch (_: Exception) {}
        return text
    }

    private fun share() {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, save()), t("Paylaş")))
    }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        // Kapanınca canlı sorgu kaldığı yerden sürer (motor adaptörü yeniden kurar)
        session?.close(); session = null
        if (log.lines().size > 3) save()
        super.onDestroy()
    }
}
