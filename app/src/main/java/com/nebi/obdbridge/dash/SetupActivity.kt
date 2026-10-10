package com.nebi.obdbridge.dash

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.LinearLayout
import android.widget.ScrollView
import com.nebi.obdbridge.NaviListener

/**
 * İlk açılış sihirbazı (forum sürümü): hoş geldin + sorumluluk → adaptör → araç → isteğe bağlı izinler → bitti.
 * Her adımda ne istendiği ve neden istendiği yazılı; geri dönülebilir, isteğe bağlı adımlar atlanabilir.
 */
class SetupActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var body: LinearLayout
    private lateinit var foot: LinearLayout
    private lateinit var dots: LinearLayout
    private val dash by lazy { getSharedPreferences("dash", MODE_PRIVATE) }
    private var step = 0
    private var v = Vehicle()
    private val steps = 5

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        v = Vehicle.fromJson(dash.getString("vehicle", null)) ?: Vehicle(logo = false)
        val root = ui.column().apply { setBackgroundColor(Pal.BG) }
        dots = ui.row().apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(14), 0, 0) }
        root.addView(dots, LinearLayout.LayoutParams(-1, -2))
        body = ui.column().apply { setPadding(ui.dp(64), ui.dp(12), ui.dp(64), ui.dp(12)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        foot = ui.row().apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; setPadding(ui.dp(48), ui.dp(8), ui.dp(48), ui.dp(16)) }
        root.addView(foot, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        show(0)
    }

    override fun onResume() {
        super.onResume()
        hideBars()
        show(step)   // Bluetooth / izin ekranlarından dönünce yenile
    }

    private fun show(i: Int) {
        step = i
        dots.removeAllViews()
        repeat(steps) { k ->
            dots.addView(View(this).apply { background = ui.bg(if (k == i) Pal.ICE else 0xFF2E343B.toInt(), 4, 0) },
                LinearLayout.LayoutParams(ui.dp(if (k == i) 26 else 8), ui.dp(8)).apply { marginEnd = ui.dp(6) })
        }
        body.removeAllViews(); foot.removeAllViews()
        when (i) {
            0 -> welcome(); 1 -> adapter(); 2 -> vehicle(); 3 -> permissions(); else -> done()
        }
    }

    private fun title(s: String, sub: String? = null) {
        body.addView(ui.text(s, 28f, light = true))
        sub?.let { body.addView(ui.text(it, 15f, Pal.DIM).apply { setPadding(0, ui.dp(6), 0, ui.dp(14)) }) }
    }

    private fun label(s: String) = body.addView(ui.caps(s).apply { setPadding(0, ui.dp(14), 0, ui.dp(8)) })

    private fun nav(next: String = "Devam", back: Boolean = true, skip: String? = null, onSkip: () -> Unit = { show(step + 1) },
                    onNext: () -> Unit = { show(step + 1) }) {
        if (back && step > 0) foot.addView(ui.button("Geri") { show(step - 1) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(10) })
        foot.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        skip?.let { foot.addView(ui.button(it, onClick = onSkip), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(10) }) }
        foot.addView(ui.button(next, primary = true, onClick = onNext))
    }

    // ---------- adımlar ----------

    private fun welcome() {
        title("OBD Dashboard'a hoş geldin",
            "Telefonunu araç göstergesine çevirir: hız, devir, vites, turbo, sıcaklık; yanında navigasyon oku ve müzik kontrolü.")
        label("Gerekenler")
        body.addView(ui.text("• Bluetooth ELM327 OBD adaptörü (Bluetooth Classic)\n• 2001 sonrası benzinli ya da 2004 sonrası dizel, OBD-II'li araç\n" +
            "• Telefon tutucusu (yatay)", 15f))
        label("Önemli")
        body.addView(ui.text("Sürüş sırasında uygulamayla uğraşma; ayarları araç dururken yap. Yakıt ve uyarılar tahmindir, " +
            "aracın kendi göstergeleri ve uyarı ışıkları esastır. Uygulama araca yalnız okuma istekleri gönderir.", 15f, Pal.DIM))
        nav("Anladım, başlayalım", back = false)
    }

    private fun adapter() {
        title("Adaptörü seç", "Adaptörü aracın OBD soketine tak ve kontağı aç. Bluetooth ya da Wi-Fi olabilir.")
        val cur = Conn.label(this)
        val card = ui.column().apply {
            background = ui.bg(if (cur != null) Pal.CARD_ON else Pal.CARD, 14, if (cur != null) Pal.ICE else Pal.EDGE)
            setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(14))
            addView(ui.text(cur ?: "Henüz seçilmedi", 18f))
            addView(ui.text(if (cur == null) "Aşağıdaki düğmeyle adaptörünü bul" else if (Conn.isWifi(this@SetupActivity)) "Wi-Fi" else "Bluetooth",
                13f, Pal.DIM))
        }
        body.addView(card, LinearLayout.LayoutParams(-1, -2))
        body.addView(ui.button(if (cur == null) "Adaptör bul" else "Değiştir", primary = cur == null) {
            AdapterPicker(this, { requestPermissions(it, 1) }) { show(step) }.show()
        }, wrap().apply { topMargin = ui.dp(12) })
        nav(skip = "Adaptörsüz dene (demo)", onSkip = { dash.edit().putBoolean("demo", true).apply(); show(step + 1) }) {
            if (!Conn.configured(this)) return@nav toastNeed("Önce adaptör seç")
            dash.edit().putBoolean("demo", false).apply(); show(step + 1)
        }
    }

    private fun vehicle() {
        title("Aracın", "Göstergelerin ölçeği, vites tahmini ve uyarılar buna göre ayarlanır. Sonra Ayarlar > Araç'tan değiştirilebilir.")
        label("Yakıt")
        body.addView(ui.chips(listOf(Fuel.DIESEL to "Dizel", Fuel.PETROL to "Benzin"), v.fuel) {
            v = v.copy(fuel = it, redline = if (it == Fuel.DIESEL) 4500 else 6500); show(step)
        })
        label("Turbo")
        body.addView(ui.chips(listOf(true to "Var", false to "Yok"), v.turbo) { v = v.copy(turbo = it) })
        label("Şanzıman")
        body.addView(ui.chips(listOf(Gearbox.MANUAL to "Manuel", Gearbox.AUTO to "Otomatik"), v.gearbox) { v = v.copy(gearbox = it); show(step) })
        if (v.manual) {
            label("İleri vites sayısı")
            body.addView(ui.chips(listOf(4 to "4", 5 to "5", 6 to "6"), v.gears) { v = v.copy(gears = it) })
        }
        label("Kırmızı bölge (devir)")
        body.addView(ui.chips((if (v.fuel == Fuel.DIESEL) listOf(4000, 4500, 5000) else listOf(6000, 6500, 7000, 7500)).map { it to "$it" },
            v.redline) { v = v.copy(redline = it) })
        body.addView(ui.text("Bilmiyorsan varsayılan kalsın: devir göstergesindeki kırmızı çizginin başladığı yer.", 13f, Pal.DIM)
            .apply { setPadding(0, ui.dp(6), 0, 0) })
        label("Volvo logosu")
        body.addView(ui.chips(listOf(true to "Göster", false to "Gösterme"), v.logo) { v = v.copy(logo = it) })
        nav { VehicleStore.save(this, v); dash.edit().remove("gears2").apply(); show(step + 1) }
    }

    private fun permissions() {
        title("İsteğe bağlı izinler", "İkisi de zorunlu değil; vermezsen ilgili özellik çalışmaz, gerisi çalışır.")
        val notif = NaviListener.hasAccess(this)
        val loc = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        permRow("Bildirim erişimi", "Navigasyonun (Yandex) dönüş okunu ve çalan müziği göstermek, müziği kontrol etmek için. " +
            "Bildirimler telefondan dışarı gönderilmez.", notif) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        permRow("Konum", "Adaptör bağlı değilken hızı GPS'ten göstermek ve dış hava sıcaklığı için.", loc) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 2)
        }
        nav()
    }

    private fun permRow(t: String, why: String, granted: Boolean, ask: () -> Unit) {
        val card = ui.row().apply { background = ui.bg(Pal.CARD, 14); setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(14)) }
        val col = ui.column()
        col.addView(ui.text(t, 17f))
        col.addView(ui.text(why, 14f, Pal.DIM).apply { setPadding(0, ui.dp(4), ui.dp(12), 0) })
        card.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(if (granted) ui.text("Verildi ✓", 15f, Pal.ICE) else ui.button("İzin ver", onClick = ask))
        body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) })
    }

    private fun done() {
        val demo = dash.getBoolean("demo", false)
        title("Hazır", if (demo) "Demo modunda başlıyor: sahte sürüş verisi. Adaptörü sonra Ayarlar > Bağlantı'dan seçebilirsin."
            else "Kontağı aç ve Bağlan'a bas. Uygulama motoru bulunca göstergeler canlanır (ilk bağlantı 10–20 sn sürebilir).")
        label("İpuçları")
        body.addView(ui.text("• Üst çubuktaki sinyal çubuklarına dokununca bağlanır / bağlantıyı keser.\n" +
            "• Alttaki değer şeridine uzun basınca gösterilen değerleri değiştirebilirsin.\n" +
            "• Vites, ilk birkaç dakikalık sürüşte öğrenilir; o zamana kadar \"–\" görünebilir.\n" +
            "• Yakıt tüketimi tahminidir; Ayarlar > Yakıt'tan depo dolumu girerek düzeltilir.", 15f, Pal.DIM))
        nav(if (demo) "Başla" else "Bağlan ve başla") {
            dash.edit().putBoolean("setup_done", true).apply()
            if (!demo) Conn.start(this)
            startActivity(Intent(this, DashboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }
    }

    private fun wrap() = LinearLayout.LayoutParams(-2, -2)
    private fun toastNeed(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    @Deprecated("Activity API")
    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        show(step)
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
