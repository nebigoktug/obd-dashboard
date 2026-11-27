package com.nebi.obdbridge.dash

import com.nebi.obdbridge.L
import com.nebi.obdbridge.t
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.nebi.obdbridge.BridgeService
import com.nebi.obdbridge.NaviListener

/**
 * Ayarlar (forum sürümü, 2026-10-10): solda bölümler, sağda seçenekler. Her seçeneğin altında ne işe yaradığı
 * tek cümleyle yazılı. Geliştirici araçları "Hakkında > Sürüm"e 7 kez dokununca açılan Gelişmiş bölümünde.
 */
class SettingsActivity : Activity() {
    private lateinit var ui: Ui
    private val dash by lazy { getSharedPreferences("dash", MODE_PRIVATE) }
    private lateinit var nav: LinearLayout
    private lateinit var pane: LinearLayout
    private lateinit var scroll: ScrollView
    private val rows = ArrayList<SettingRow>()
    private var section = 0
    private val ticker = object : Runnable {
        override fun run() { rows.forEach { it.refresh() }; pane.postDelayed(this, 1000) }
    }

    private data class Section(val title: String, val build: () -> Unit, val visible: () -> Boolean = { true })

    private val sections by lazy {
        listOf(
            Section(t("Bağlantı"), ::connection),
            Section(t("Araç"), ::vehicle),
            Section(t("Ekran"), ::display),
            Section(t("Navigasyon ve medya"), ::naviMedia),
            Section(t("Yakıt"), ::fuel),
            Section(t("Uyarılar"), ::alerts),
            Section(t("Kayıtlar"), ::logs),
            Section(t("Hakkında"), ::about),
            Section(t("Gelişmiş"), ::advanced) { dash.getBoolean("dev", false) },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        VehicleStore.load(this)
        UserThemes.load(this)
        section = intent.getIntExtra("section", 0)
        val root = ui.row().apply { setBackgroundColor(Pal.BG); gravity = android.view.Gravity.TOP }
        nav = ui.column(16)
        val navScroll = ScrollView(this).apply { addView(nav); isVerticalScrollBarEnabled = false }
        root.addView(navScroll, LinearLayout.LayoutParams(0, -1, 0.32f))
        pane = ui.column(16)
        scroll = ScrollView(this).apply { addView(pane) }
        root.addView(scroll, LinearLayout.LayoutParams(0, -1, 0.68f))
        setContentView(root)
        fillCutout(root)
        buildNav()
        show(section)
    }

    override fun onResume() {
        super.onResume()
        hideBars()
        show(section)   // izin ekranından dönünce durumlar güncellensin
        resumedNow = this
        showPendingTheme()
        pane.post(ticker)
    }

    override fun onPause() {
        pane.removeCallbacks(ticker)
        if (resumedNow === this) resumedNow = null
        super.onPause()
    }

    private fun buildNav() {
        nav.removeAllViews()
        nav.addView(ui.text(t("‹  Ayarlar"), 22f, light = true).apply {
            setPadding(ui.dp(8), ui.dp(4), 0, ui.dp(16))
            setOnClickListener { finish() }
        })
        sections.forEachIndexed { i, s ->
            if (!s.visible()) return@forEachIndexed
            val on = i == section
            nav.addView(ui.text(s.title, 16f, if (on) Pal.TEXT else Pal.DIM).apply {
                minHeight = ui.dp(50); gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(ui.dp(16), 0, ui.dp(12), 0)
                background = if (on) ui.bg(Pal.CARD_ON, 14, Pal.ICE) else null
                setOnClickListener { show(i) }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(4) })
        }
    }

    private fun show(i: Int) {
        section = i
        buildNav()
        pane.removeAllViews()
        rows.clear()
        sections[i].build()
        scroll.scrollTo(0, 0)
    }

    // ---------- yardımcılar ----------

    private fun header(s: String) = pane.addView(ui.caps(s).apply { setPadding(ui.dp(6), ui.dp(18), 0, ui.dp(8)) })
    private fun note(s: String) = pane.addView(ui.text(s, 13f, Pal.DIM).apply { setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(8)) })
    private fun add(r: SettingRow) {
        rows += r
        pane.addView(r, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(6) })
    }

    private fun choose(title: String, options: List<String>, current: Int, done: (Int) -> Unit) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), current) { d, w -> done(w); d.dismiss(); show(section) }
            .setNegativeButton(t("Vazgeç"), null)
            .show()
    }

    /** Tema seçici: küçük önizlemeli iki sütunlu ızgara */
    private fun chooseTheme(themes: List<ImageTheme>, cur: ImageTheme, done: (ImageTheme) -> Unit) {
        val cols = if (resources.configuration.screenWidthDp >= 600) 3 else 2
        var dlg: AlertDialog? = null
        val grid = android.widget.GridLayout(this).apply { columnCount = cols; setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)) }
        for (t in themes) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
                if (t == cur) background = android.graphics.drawable.GradientDrawable().apply {
                    setStroke(ui.dp(2), t.accent); cornerRadius = ui.dp(8).toFloat()
                }
                setOnClickListener { done(t); dlg?.dismiss(); show(section) }
                // Kendi temaları uzun basınca silinir
                if (t.dir != null) setOnLongClickListener { dlg?.dismiss(); deleteTheme(t); true }
            }
            cell.addView(android.widget.ImageView(this).apply {
                if (t.dir != null) setImageBitmap(t.decode(resources, ImageTheme.Part.THUMB)) else setImageResource(t.thumb)
                adjustViewBounds = true
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            })
            cell.addView(android.widget.TextView(this).apply {
                text = t.name; textSize = 13f; setTextColor(0xFFE0E4E8.toInt())
                gravity = android.view.Gravity.CENTER; setPadding(0, ui.dp(2), 0, 0)
            })
            grid.addView(cell, android.widget.GridLayout.LayoutParams(
                android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED),
                android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)).apply { width = 0 })
        }
        dlg = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(t("Tema"))
            .setView(android.widget.ScrollView(this).apply { addView(grid) })
            .setNegativeButton(t("Vazgeç"), null)
            .show()
    }

    private fun confirm(title: String, msg: String, yes: String, act: () -> Unit) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title).setMessage(msg)
            .setPositiveButton(yes) { _, _ -> act(); show(section) }
            .setNegativeButton(t("Vazgeç"), null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private var vehicle: Vehicle
        get() = Vehicle.current
        set(v) { VehicleStore.save(this, v); show(section) }

    // ---------- bölümler ----------

    private fun connection() {
        header(t("Durum"))
        add(ui.item(t("Bağlantı"), t("Adaptör → araç beyni bağlantısının şu anki durumu"), { Conn.status(this) }, onClick = null))
        add(ui.item(if (BridgeService.running) t("Bağlantıyı kes") else t("Bağlan"),
            if (BridgeService.running) t("Adaptörle bağlantıyı kapatır") else t("Kontak açıkken bas; motor bulununca göstergeler canlanır"),
            null) {
            if (BridgeService.running) Conn.stop(this)
            else if (!Conn.start(this)) toast(t("Önce adaptör seç"))
            pane.postDelayed({ show(section) }, 400)
        })
        header(t("Adaptör"))
        add(ui.item(t("OBD adaptörü"), t("Bluetooth ya da Wi-Fi ELM327 adaptörü"),
            { Conn.label(this)?.let { (if (Conn.isWifi(this)) t("Wi-Fi · ") else t("Bluetooth · ")) + it } ?: t("Seçilmedi") }) {
            picker()
        })
        add(ui.item(t("Adaptör nasıl bağlanır?"), t("Listede adaptör yoksa"), null) {
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(t("Adaptörü bağlama"))
                .setMessage(t("1. Adaptörü aracın OBD soketine tak (genelde direksiyonun sol altında) ve kontağı aç.\n\n" +
                    "Bluetooth: Adaptör seçiminde yakındaki adaptörler listelenir. Bazı adaptörler önce telefonun Bluetooth " +
                    "ayarlarından eşleştirilmek ister (OBDII, ELM327, V-LINK gibi; PIN genelde 1234 ya da 0000).\n\n" +
                    "Wi-Fi: Telefonu adaptörün Wi-Fi ağına bağla, \"internet yok\" uyarısında bağlı kal; adres genelde 192.168.0.10:35000.\n\n" +
                    "En iyi denenmiş olan klasik Bluetooth ELM327. Bluetooth LE ve Wi-Fi adaptör desteği deneyseldir."))
                .setPositiveButton(t("Bluetooth ayarlarını aç")) { _, _ -> Conn.openBtSettings(this) }
                .setNegativeButton(t("Tamam"), null).show()
        })
        add(ui.toggle(t("Açılışta otomatik bağlan"), t("Uygulama açılınca seçili adaptöre kendiliğinden bağlanır"),
            { dash.getBoolean("autoconnect", true) }) { dash.edit().putBoolean("autoconnect", it).apply() })
        // Bazı telefonlar (HyperOS gibi) arka plandaki bağlantı servisini kapatıyor; muafiyet bunu önler
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        add(ui.item(t("Arka planda kapanmasın"), t("Telefon pil tasarrufu için bağlantıyı kesmesin (ekran kapanınca ya da başka uygulamadayken)"),
            { if (pm.isIgnoringBatteryOptimizations(packageName)) t("Verildi ✓") else t("Verilmedi") }) {
            @android.annotation.SuppressLint("BatteryLife")
            val i = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:$packageName"))
            runCatching { startActivity(i) }
        })
    }

    private fun vehicle() {
        val v = vehicle
        note(t("Göstergelerin ölçeği, vites tahmini ve uyarılar bu bilgilere göre ayarlanır."))
        header(t("Motor"))
        add(ui.item(t("Yakıt tipi"), t("Dizel uyarıları (DPF) ve yakıt hesabı buna göre"), { if (v.fuel == Fuel.DIESEL) t("Dizel") else t("Benzin") }) {
            choose(t("Yakıt tipi"), listOf(t("Dizel"), t("Benzin")), v.fuel.ordinal) {
                val f = Fuel.entries[it]
                vehicle = v.copy(fuel = f, redline = if (f == Fuel.DIESEL) 4500 else 6500)
            }
        })
        add(ui.toggle(t("Turbo"), t("Turbo basıncı göstergesi ve turbo uyarısı"), { v.turbo }) { vehicle = v.copy(turbo = it) })
        add(ui.item(t("Kırmızı bölge"), t("Devir göstergesinin ölçeği ve uyarı rengi buna göre"), { "${v.redline} rpm" }) {
            val opts = (4000..7500 step 500).toList()
            choose(t("Kırmızı bölge (rpm)"), opts.map { "$it rpm" }, opts.indexOf(v.redline)) { vehicle = v.copy(redline = opts[it]) }
        })
        header(t("Şanzıman"))
        add(ui.item(t("Şanzıman"), t("Manuelde vites, devir/hız oranından tahmin edilir"), { if (v.manual) t("Manuel") else t("Otomatik") }) {
            choose(t("Şanzıman"), listOf(t("Manuel"), t("Otomatik")), v.gearbox.ordinal) { vehicle = v.copy(gearbox = Gearbox.entries[it]) }
        })
        if (v.manual) {
            add(ui.item(t("İleri vites sayısı"), null, { "${v.gears}" }) {
                choose(t("İleri vites sayısı"), listOf("4", "5", "6"), v.gears - 4) { vehicle = v.copy(gears = it + 4) }
            })
            add(ui.item(t("Vites öğrenmesini sıfırla"), t("Vites yanlış gösteriliyorsa: birkaç dakikalık sürüşte yeniden öğrenilir"), null) {
                confirm(t("Vites öğrenmesi sıfırlansın mı?"), t("Öğrenilen vites oranları silinir."), t("Sıfırla")) {
                    dash.edit().remove("gears2").putBoolean("gears_reset", true).apply()
                }
            })
        }
        header(t("Gösterge"))
        add(ui.item(t("Hız göstergesi üst sınırı"), null, { "${Units.speedDialMax(v.speedMax)} ${Units.speedUnit}" }) {
            val opts = Units.speedDialOptions()
            choose(L.t("Hız göstergesi (%s)", Units.speedUnit), opts.map { "${Units.speedDialMax(it)} ${Units.speedUnit}" },
                opts.indexOf(v.speedMax)) { vehicle = v.copy(speedMax = opts[it]) }
        })
        add(ui.item(t("Ekran yazısı"), t("Görsel temalarda üst ortada (ör. araç modeli). Boş bırakılırsa yazı olmaz"), { v.badge.ifBlank { "yok" } }) {
            val input = EditText(this).noFullscreen().apply {
                hint = t("ör. S40"); setText(v.badge); filters = arrayOf(android.text.InputFilter.LengthFilter(Vehicle.MAX_BADGE))
            }
            val box = android.widget.FrameLayout(this).apply { setPadding(ui.dp(20), 0, ui.dp(20), 0); addView(input) }
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(t("Ekran yazısı")).setView(box)
                .setPositiveButton(t("Kaydet")) { _, _ -> vehicle = v.copy(badge = input.text.toString().trim()); show(section) }
                .setNegativeButton(t("Vazgeç"), null)
                .show()
        })
        add(ui.toggle(t("Hız düzeltmesi (GPS)"), t("Araç göstergeleri genelde %3–7 fazla gösterir; GPS'e göre düzeltir"),
            { dash.getBoolean("speed_corr", false) }) { dash.edit().putBoolean("speed_corr", it).apply() })
    }

    private fun display() {
        header(t("Dil"))
        add(ui.item(t("Uygulamanın dili"), t("Değişince uygulama yeniden başlar"),
            { if (L.choice == L.SYSTEM) t("Telefonun dili (%s)", L.name) else L.name }) { chooseLanguage() })
        add(ui.item(t("Kendi dil paketin"), t("Şablonu kaydet, çevir, sonra buradan ekle. Ayrıntı şablonun içinde yazar"),
            { L.userPackName(this) ?: t("Yok") }) { languagePackMenu() })
        units()
        header(t("Görünüm"))
        val themes = ImageTheme.all()
        val cur = ImageTheme.byId(dash.getString("theme", DashboardActivity.DEFAULT_THEME))
        add(ui.item(t("Tema"), t("Göstergelerin görünümü"), { cur.name }) {
            chooseTheme(themes, cur) { dash.edit().putString("theme", it.id).apply() }
        })
        val colors = ThemeColors.load(dash, cur.id)
        add(ui.item(t("Renkler"), t("Bu temada gösterge rakamları, kart yazısı, kart zemini, vurgu ve uyarı hapı renkleri"), { if (colors.isDefault) t("Otomatik") else t("Özel") }) {
            colorsDialog(cur)
        })
        add(ui.item(t("Kendi temanı ekle"), t("Şablonu bir yapay zekâya (Gemini, ChatGPT…) kendi tarzında çizdir, çıkan resmi ekle"), null) { themeHelp() })
        if (ImageTheme.user.isNotEmpty()) add(ui.item(t("Kendi temalarım"), t("Eklediğin temaları sil (tema seçicide üstüne uzun basmak da siler)"),
            { t("%d tema", ImageTheme.user.size) }) { manageThemes() })
        add(ui.toggle(t("Güneşte Gündüz temasına geç"), t("Ortam ışığı çok artınca okunurluk için açık temaya, kararınca seçili temaya döner"),
            { dash.getBoolean("auto_day", true) }) { dash.edit().putBoolean("auto_day", it).apply() })
        add(ui.item(t("Alt şeritteki değerler"), t("Ana ekranda yol çiziminin iki yanındaki 4 değer. Dokununca ana ekrana döner ve seçim penceresi açılır (şeride uzun basmak da açar)"), null) {
            dash.edit().putBoolean("open_strip", true).apply(); finish()
        })
        header(t("Deneme"))
        add(ui.toggle(t("Demo modu"), t("Araç olmadan sahte sürüş verisiyle uygulamayı dene"),
            { dash.getBoolean("demo", false) }) { dash.edit().putBoolean("demo", it).apply() })
    }

    /** Birimler dilden bağımsız; içeride her şey metrik kalır, yalnız gösterim değişir (Units) */
    private fun units() {
        header(t("Birimler"))
        fun save() = Units.save(this)
        add(ui.item(t("Hız ve mesafe"), null, { if (Units.miles) "mph · mi" else "km/h · km" }) {
            choose(t("Hız ve mesafe"), listOf("km/h · km", "mph · mi"), if (Units.miles) 1 else 0) { Units.miles = it == 1; save() }
        })
        add(ui.item(t("Sıcaklık"), null, { Units.tempUnit }) {
            choose(t("Sıcaklık"), listOf("°C", "°F"), if (Units.fahrenheit) 1 else 0) { Units.fahrenheit = it == 1; save() }
        })
        val fuels = Units.Consumption.values().toList()
        fun fuelName(c: Units.Consumption) = when (c) {
            Units.Consumption.L100 -> "L/100 km"
            Units.Consumption.MPG_US -> t("mpg (ABD galonu)")
            Units.Consumption.MPG_UK -> t("mpg (İngiliz galonu)")
            Units.Consumption.KML -> "km/L"
        }
        add(ui.item(t("Yakıt tüketimi"), null, { fuelName(Units.consumption) }) {
            choose(t("Yakıt tüketimi"), fuels.map(::fuelName), fuels.indexOf(Units.consumption)) { Units.consumption = fuels[it]; save() }
        })
        add(ui.item(t("Basınç"), t("Turbo, emme ve yakıt basıncı"), { if (Units.psi) "psi" else "kPa · bar" }) {
            choose(t("Basınç"), listOf("kPa · bar", "psi"), if (Units.psi) 1 else 0) { Units.psi = it == 1; save() }
        })
    }

    private fun naviMedia() {
        header(t("Navigasyon"))
        YandexNav.unavailable(this)?.let { note(it) }
        add(ui.toggle(t("Sesli yönlendirme"), t("Dönüşleri sesli söyler; konuşurken müzik kısılır. Telefonda uygulamanın dilinde ses paketi gerekir"),
            { dash.getBoolean("nav_voice", true) }) { dash.edit().putBoolean("nav_voice", it).apply() })
        header(t("Hızlı hedefler"))
        for ((k, label) in listOf("home" to t("Ev"), "work" to t("İş"))) {
            add(ui.item(t("%s adresi", label), t("Ana ekrandaki %s düğmesi buraya rota başlatır", label),
                { dash.getString("place_${k}_name", null) ?: dash.getString("place_$k", null) ?: t("Girilmedi") }) {
                PlaceSearchActivity.open(this, k)
            })
        }
        note(t("Rota, dönüşler ve adres arama Yandex'ten gelir; ayrı bir navigasyon uygulaması gerekmez."))
        add(ui.item(t("Kendi Yandex anahtarım (isteğe bağlı)"), t("Uygulamanın anahtarı ücretsiz ve günlük kullanıcı sınırlı. " +
            "Navigasyon kapanırsa developer.tech.yandex.com'dan ücretsiz bir MapKit anahtarı alıp buraya yapıştırabilirsin"),
            { if (YandexNav.userKey(this).isEmpty()) t("Yok") else t("Girildi ✓") }) { askYandexKey() })
        header(t("Medya"))
        note(t("Müzik kontrolü izin istemez. İzin vermeden şarkı adı ve kapak yalnız buna açık uygulamalardan gelir (ör. YouTube); " +
            "Spotify'da Ayarlar > \"Device Broadcast Status\" açılırsa Spotify'dan da. YouTube Music ve çoğu uygulama için aşağıdaki bildirim erişimi gerekir."))
        add(ui.item(t("Şarkı adı her uygulamada (isteğe bağlı)"), t("Bildirim erişimi verirsen bütün uygulamaların çalanı görünür. " +
            "Uygulama hiçbir bildirimi almaz (Android 13+ izin sayfasında bütün bildirim türleri kapalı görünür), yalnız çalan müziği görür; istemezsen verme"),
            { if (NaviListener.hasAccess(this)) t("Verildi ✓") else t("Verilmedi") }) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
    }

    private fun askYandexKey() {
        val input = EditText(this).noFullscreen().apply {
            hint = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"; setSingleLine()
            setText(YandexNav.userKey(this@SettingsActivity))
        }
        val save = { k: String ->
            YandexNav.setUserKey(this, k)
            if (YandexNav.needsRestart(this)) restartApp() else show(section)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(t("Kendi Yandex anahtarım"))
            .setMessage(t("developer.tech.yandex.com › MapKit Mobile SDK'dan aldığın anahtarı yapıştır. Anahtar yalnız bu telefonda saklanır. " +
                "Boş bırakırsan uygulamanın anahtarı kullanılır."))
            .setView(boxed(input))
            .setPositiveButton(t("Kaydet")) { _, _ ->
                val k = input.text.toString().trim()
                if (k.isNotEmpty() && !YandexNav.validKey(k)) toast(t("Bu bir Yandex anahtarına benzemiyor")) else save(k)
            }
            .setNeutralButton(t("Kaldır")) { _, _ -> save("") }
            .setNegativeButton(t("Vazgeç"), null).show()
    }

    /** MapKit anahtarı ve dil süreç başına bir kez kurulur: değişince uygulama yeniden başlar */
    private fun restartApp(msg: String = t("Yeni anahtar için uygulama yeniden başlıyor")) {
        toast(msg)
        val i = packageManager.getLaunchIntentForPackage(packageName) ?: return
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        Runtime.getRuntime().exit(0)
    }

    private val ins get() = DashboardActivity.insights

    // ---------- Kendi teman ----------

    private fun themeHelp() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(t("Kendi temanı ekle"))
            .setMessage(t("1. \"Şablonu gönder\" ile şablon resmi ve istek metnini Gemini ya da ChatGPT gibi bir uygulamaya gönder. " +
                "Metindeki [TARZINI YAZ] yerine istediğini yaz (ör. \"ahşap ve pirinç\", \"neon şehir gecesi\").\n" +
                "2. Çıkan resmi telefona kaydet.\n" +
                "3. \"Resim seç\" ile ekle. Önizlemede kesikli çizgiler halkaların üstüne oturuyorsa tema düzgün çalışır.\n\n" +
                "İstek metni panoya da kopyalanır; uygulama metni almazsa yapıştır."))
            .setPositiveButton(t("Resim seç")) { _, _ -> pickThemeImage() }
            .setNeutralButton(t("Şablonu gönder")) { _, _ -> shareTemplate() }
            .setNegativeButton(t("Kapat"), null)
            .show()
    }

    /** Tema başına renkler: her satırda "Oto" + palet; kart saydamlığı kaydırıcıyla. Seçim hemen kaydedilir */
    private fun colorsDialog(t: ImageTheme) {
        var c = ThemeColors.load(dash, t.id)
        val col = ui.column().apply { setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(4)) }
        fun row(label: String, get: () -> Int?, set: (Int?) -> Unit) {
            col.addView(ui.caps(label, Pal.DIM).apply { setPadding(0, ui.dp(12), 0, ui.dp(6)) })
            val strip = ui.row()
            val cells = ArrayList<Pair<android.view.View, Int?>>()
            fun mark() = cells.forEach { (v, k) ->
                (v.background as android.graphics.drawable.GradientDrawable).setStroke(ui.dp(if (k == get()) 3 else 1),
                    if (k == get()) Pal.ICE else 0x55FFFFFF)
            }
            for (k in listOf<Int?>(null) + ThemeColors.PALETTE.toList()) {
                val v = android.widget.TextView(this).apply {
                    text = if (k == null) t("Oto") else ""; textSize = 11f; setTextColor(Pal.TEXT); gravity = android.view.Gravity.CENTER
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(k ?: 0x00000000)
                    }
                    setOnClickListener { set(k); c.save(dash, t.id); mark() }
                }
                cells += v to k
                strip.addView(v, LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)).apply { marginEnd = ui.dp(8) })
            }
            mark()
            col.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(strip) })
        }
        row(t("Kart yazısı"), { c.text }) { c = c.copy(text = it) }
        row(t("Kart zemini"), { c.card }) { c = c.copy(card = it) }
        col.addView(ui.caps(t("Kart saydamlığı"), Pal.DIM).apply { setPadding(0, ui.dp(12), 0, ui.dp(2)) })
        val alphaNote = ui.text("", 13f, Pal.DIM)
        fun noteText() { alphaNote.text = c.cardAlpha?.let { t("%%%d opak", it * 100 / 255) } ?: t("Otomatik (temanın ayarı)") }
        noteText()
        col.addView(alphaNote)
        col.addView(android.widget.SeekBar(this).apply {
            max = 255; progress = c.cardAlpha ?: (t.cardAlpha ?: if (t.light) 200 else 195)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar, v: Int, user: Boolean) {
                    if (user) { c = c.copy(cardAlpha = v.coerceAtLeast(40)); noteText() }
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar) { c.save(dash, t.id) }
            })
        })
        row(t("Gösterge rakamları (hız, vites, devir, kadran ölçeği, alt şerit)"), { c.dial }) { c = c.copy(dial = it) }
        row(t("Vurgu (seçili sekme, simgeler, çubuklar)"), { c.accent }) { c = c.copy(accent = it) }
        row(t("Uyarı hapı zemini"), { c.pill }) { c = c.copy(pill = it) }
        col.addView(ui.text(L.t("Değişiklikler ana ekrana dönünce görünür. Renkler yalnız \"%s\" teması için.", t.name), 13f, Pal.DIM)
            .apply { setPadding(0, ui.dp(14), 0, ui.dp(4)) })
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(L.t("Renkler · %s", t.name))
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton(t("Tamam")) { _, _ -> show(section) }
            .setNeutralButton(t("Varsayılana dön")) { _, _ -> ThemeColors().save(dash, t.id); show(section) }
            .setOnDismissListener { show(section) }
            .show()
    }

    /** Kendi temalarının listesi: dokununca silme onayı */
    private fun manageThemes() {
        val list = ImageTheme.user
        if (list.isEmpty()) return
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(t("Kendi temalarım · silmek için dokun"))
            .setItems(list.map { it.name }.toTypedArray()) { _, i -> deleteTheme(list[i]) }
            .setNegativeButton(t("Kapat"), null)
            .show()
    }

    private fun deleteTheme(t: ImageTheme) =
        confirm(L.t("\"%s\" silinsin mi?", t.name), t("Kendi eklediğin tema telefondan silinir."), t("Sil")) {
            if (dash.getString("theme", null) == t.id) dash.edit().putString("theme", DashboardActivity.DEFAULT_THEME).apply()
            UserThemes.delete(this, t)
        }

    // ---------- Dil ----------

    private fun chooseLanguage() {
        val user = L.userPackName(this)
        val opts = listOf(L.SYSTEM to t("Telefonun dili"), "tr" to "Türkçe", "en" to "English") + listOfNotNull(user?.let { L.USER to it })
        choose(t("Uygulamanın dili"), opts.map { it.second }, opts.indexOfFirst { it.first == L.choice }) { i ->
            if (opts[i].first != L.choice) setLanguage(opts[i].first)
        }
    }

    private fun setLanguage(choice: String) {
        L.save(this, choice)
        restartApp(t("Dil için uygulama yeniden başlıyor"))
    }

    private fun languagePackMenu() {
        val user = L.userPackName(this)
        val opts = listOfNotNull(t("Çeviri şablonunu kaydet"), t("Dil paketi ekle"), user?.let { t("Dil paketini kaldır") })
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(t("Kendi dil paketin"))
            .setItems(opts.toTypedArray()) { _, w ->
                when (w) {
                    0 -> launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json").putExtra(Intent.EXTRA_TITLE, "obd-dashboard-lang.json"), REQ_LANG_EXPORT)
                    1 -> launch(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_LANG_IMPORT)
                    2 -> confirm(t("Dil paketi kaldırılsın mı?"), t("Uygulama paketsiz dile döner."), t("Kaldır")) {
                        L.userFile(this).delete()
                        if (L.choice == L.USER) setLanguage(L.SYSTEM)
                    }
                }
            }
            .setNegativeButton(t("Vazgeç"), null).show()
    }

    private fun launch(i: Intent, req: Int) {
        try { @Suppress("DEPRECATION") startActivityForResult(i, req) } catch (_: Exception) { toast(t("Dosya seçici açılamadı")) }
    }

    /** Şablon: İngilizce paket + İngilizce arıza kodu tablosu; çevirmen İngilizceleri kendi diliyle değiştirir */
    private fun exportLanguageTemplate(uri: android.net.Uri) {
        try {
            val o = org.json.JSONObject(assets.open("lang/en.json").bufferedReader().use { it.readText() })
            o.put("_meta", org.json.JSONObject().put("code", "xx").put("name", "").put("author", "")
                .put("help", "Translate every value (keep the keys). code: ISO 639-1 language code, e.g. de, fr, az. " +
                    "Keep %s, %d, %.1f, %1\$s placeholders. Values left empty are shown in English. " +
                    "Keys are the Turkish originals. 'dtc' (fault code descriptions) is optional."))
            val dtc = org.json.JSONObject()
            DtcCatalog.parse(assets.open("dtc_en.tsv").bufferedReader().use { it.readLines() }).forEach { (k, v) -> dtc.put(k, v) }
            o.put("dtc", dtc)
            contentResolver.openOutputStream(uri)!!.use { it.write(o.toString(1).toByteArray()) }
            toast(t("Şablon kaydedildi"))
        } catch (e: Exception) {
            toast(t("Şablon kaydedilemedi: %s", e.message ?: ""))
        }
    }

    private fun importLanguagePack(uri: android.net.Uri) {
        val text = try { contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) } } catch (_: Exception) { null }
        val p = text?.let { L.parse(it) }
        if (p == null || p.strings.isEmpty()) { toast(t("Dil paketi okunamadı (JSON biçimi bozuk ya da çeviri yok)")); return }
        L.userFile(this).apply { parentFile?.mkdirs() }.writeText(text)
        confirm(p.name, t("%d metin çevrilmiş. Bu dile geçilsin mi?", p.strings.size), t("Geç")) { setLanguage(L.USER) }
    }

    private fun shareTemplate() {
        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .setPrimaryClip(android.content.ClipData.newPlainText(t("Tema isteği"), UserThemes.PROMPT))
        val uri = ShareProvider.uriFor(this, UserThemes.templateFile(this))
        val send = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, UserThemes.PROMPT).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = android.content.ClipData.newRawUri("", uri)
        try { startActivity(Intent.createChooser(send, t("Şablonu gönder"))) } catch (_: Exception) { toast(t("Paylaşacak uygulama yok")) }
    }

    private fun pickThemeImage() {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), REQ_THEME)
        } catch (_: Exception) { toast(t("Resim seçici açılamadı")) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(req, res, data)
        val uri = data?.data
        if (res != RESULT_OK || uri == null) return
        if (req == REQ_LANG_EXPORT) { exportLanguageTemplate(uri); return }
        if (req == REQ_LANG_IMPORT) { importLanguagePack(uri); return }
        if (req != REQ_THEME) return
        // Seçici dikey açılınca bu ekran dönüşte yeniden kurulur: sonuç ekrandan bağımsız tutulur ([pendingTheme])
        val wait = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setMessage(t("Tema hazırlanıyor…")).setCancelable(false).show()
        val app = applicationContext
        Thread({
            val r = runCatching { UserThemes.prepare(app, uri).let { it to UserThemes.preview(it) } }
            pendingTheme = r
            runOnUiThread {
                runCatching { if (!isDestroyed) wait.dismiss() }
                (resumedNow ?: this).showPendingTheme()   // dönüşte yeniden kurulduysa yenisi gösterir
            }
        }, "tema").start()
    }

    /** Hazırlanan tema varsa önizlemeyi göster (bu ekran ya da yeniden kurulan yenisi) */
    private fun showPendingTheme() {
        if (isFinishing || isDestroyed) return
        val r = pendingTheme ?: return
        pendingTheme = null
        r.onSuccess { (d, pv) -> confirmTheme(d, pv) }.onFailure { toast(t("Resim açılamadı: %s", it.message ?: t("bilinmeyen hata"))) }
    }

    private fun confirmTheme(d: UserThemes.Draft, preview: android.graphics.Bitmap) {
        val name = EditText(this).noFullscreen().apply { hint = t("Tema adı"); setSingleLine(); setText(t("Benim temam")) }
        val col = ui.column().apply {
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0)
            addView(android.widget.ImageView(this@SettingsActivity).apply { setImageBitmap(preview); adjustViewBounds = true })
            addView(ui.text(t("Kesikli çizgiler halkaların iç ve dış kenarına oturmalı. Kaymışsa yapay zekâdan halkaları şablondaki yerde " +
                "tutmasını yeniden iste."), 13f, Pal.DIM).apply { setPadding(0, ui.dp(8), 0, ui.dp(8)) })
            addView(name)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(t("Önizleme"))
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton(t("Kaydet ve kullan")) { _, _ ->
                val t = UserThemes.save(this, d, name.text.toString().trim().ifEmpty { t("Benim temam") })
                dash.edit().putString("theme", t.id).apply()
                toast(t("Tema eklendi")); show(section)
            }
            .setNegativeButton(t("Vazgeç"), null)
            .show()
    }

    /** Yatay ekranda klavye tam ekran olup pencereyi (ve Kaydet'i) örtmesin */
    private fun EditText.noFullscreen() = apply {
        imeOptions = imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
    }

    /** Penceredeki yazı kutusu kenara yapışmasın */
    private fun boxed(v: android.view.View) = android.widget.FrameLayout(this).apply { setPadding(ui.dp(20), 0, ui.dp(20), 0); addView(v) }

    private fun fuel() {
        note(t("Çoğu araç yakıt tüketimini OBD'den vermez. Uygulama benzinlide hava debisinden (MAF), dizelde motor yükünden " +
            "tahmin eder. Birkaç depo dolumu girersen tahmin aracına göre düzelir."))
        header(t("Kalibrasyon"))
        add(ui.item(t("Depo doldurdum"), t("Depoyu tam doldurunca pompadaki litreyi gir"), {
            if (ins.fuel.calibrations == 0) t("Kalibre edilmedi") else t("%d dolum", ins.fuel.calibrations)
        }) { askFill() })
        add(ui.item(t("Son dolumdan beri"), null, { Units.distText(ins.fuel.km, 0) + " · ~%.1f L".format(ins.fuel.estLiters) }, onClick = null))
        add(ui.item(t("Yakıt tahminini sıfırla"), t("Kalibrasyonu fabrika değerine döndürür"), null) {
            confirm(t("Yakıt tahmini sıfırlansın mı?"), t("Depo dolumlarıyla yapılan düzeltme silinir."), t("Sıfırla")) {
                ins.fuel.k = FuelModel.DEFAULT_K; ins.fuel.calibrations = 0; ins.fuel.estLiters = 0.0; ins.fuel.km = 0.0; saveIns()
            }
        })
        note(t("Ana ekranda yakıtı görmek için alt şeride uzun basıp \"Tüketim\" ya da \"Ort. tüketim\" seç."))
    }

    private fun askFill() {
        val input = EditText(this).noFullscreen().apply {
            hint = t("Litre (ör. 42,5)")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(t("Depo doldurdum"))
            .setMessage(t("Depoyu tam doldurduktan sonra pompadaki litreyi gir. İlk tam dolum yalnız başlangıç noktasıdır."))
            .setView(boxed(input))
            .setPositiveButton(t("Kaydet")) { _, _ ->
                val l = input.text.toString().replace(',', '.').toDoubleOrNull()
                toast(if (l != null && ins.fuel.fillUp(l)) t("Yakıt tahmini düzeltildi") else t("Başlangıç kaydedildi"))
                saveIns(); show(section)
            }
            .setNegativeButton(t("Vazgeç"), null).show()
    }

    private fun saveIns() = dash.edit().putString("insights", ins.save()).apply()

    private fun alerts() {
        note(t("Uyarılar ana ekranın üst ortasında kısa bir şerit olarak görünür. Değerler araç beyninden okunur ama " +
            "yorumlar tahmindir; aracın kendi uyarı ışıkları her zaman önceliklidir."))
        val v = vehicle
        fun alertRow(kind: AlertKind, title: String, sum: String) =
            add(ui.toggle(title, sum, { dash.getBoolean("alert_${kind.name}", true) }) { dash.edit().putBoolean("alert_${kind.name}", it).apply() })
        if (v.fuel == Fuel.DIESEL) alertRow(AlertKind.DPF, t("DPF temizlenmesi"), t("Partikül filtresi temizlenirken motoru kapatmaman için uyarır"))
        if (v.turbo) alertRow(AlertKind.TURBO_LOW, t("Turbo basıncı düşük"), t("Tam gazda turbo basıncı alışılmışın altına düşerse (hortum kaçağı vb.)"))
        alertRow(AlertKind.CHARGE_LOW, t("Şarj düşük"), t("Motor çalışırken akü voltajı 13,2 V altında kalırsa (şarj dinamosu)"))
        alertRow(AlertKind.CHARGE_HIGH, t("Şarj voltajı yüksek"), t("14,9 V üstü (voltaj regülatörü)"))
        alertRow(AlertKind.BATTERY_WEAK, t("Akü zayıf"), t("Kontak açık, motor kapalıyken 12,2 V altı"))
        alertRow(AlertKind.HOT, t("Motor sıcak"), t("Motor suyu %1\$s üstünde sarı, %2\$s üstünde kırmızı uyarı", Units.tempText(Insights.HOT_C), Units.tempText(Insights.HOT_CRIT_C)))
        alertRow(AlertKind.COLD, t("Motor soğuk"), t("Motor ısınmadan yüksek devire çıkınca uyarır"))
        alertRow(AlertKind.MIL, t("Arıza lambası"), t("Motor arıza lambası yanınca ana ekranda gösterir; dokununca arıza kodları açılır"))
        add(ui.toggle(t("Yakıt kesik / boşta notu"), t("Devir göstergesinde küçük not (dizelde motor freni = sıfır yakıt)"),
            { dash.getBoolean("note_fuelcut", true) }) { dash.edit().putBoolean("note_fuelcut", it).apply() })
    }

    private fun logs() {
        val dir = java.io.File(getExternalFilesDir(null), "logs")
        header(t("Sürüş kaydı"))
        add(ui.toggle(t("Her sürüşü kaydet"), t("Geçmiş sekmesi ve yolculuk istatistikleri için. Kayıtlar yalnız bu telefonda, 30 gün saklanır"),
            { Conn.prefs(this).getBoolean(BridgeService.PREF_DRIVE_LOG, true) }) {
            Conn.prefs(this).edit().putBoolean(BridgeService.PREF_DRIVE_LOG, it).apply()
        })
        // Sorun bildir Hakkında'da; bütün kayıtları paylaşmak geliştirici bölümünde
        add(ui.item(t("Tüm kayıtları sil"), null, null, danger = true) {
            confirm(t("Tüm sürüş kayıtları silinsin mi?"), t("Geçmiş sekmesi boşalır. Geri alınamaz."), t("Sil")) {
                dir.listFiles()?.forEach { it.delete() }
            }
        })
        header(t("Öğrenilenler"))
        add(ui.item(t("Performans rekorlarını sıfırla"), t("Hızlanma süreleri ve en yüksek güç"), null) {
            confirm(t("Rekorlar sıfırlansın mı?"), "", t("Sıfırla")) {
                ins.perf.best0100 = null; ins.perf.best60100 = null; ins.perf.best060mph = null; ins.perf.best4060mph = null
                ins.perf.peakKw = 0.0; saveIns()
            }
        })
        add(ui.item(t("Motor öğrenmelerini sıfırla"), t("Rölanti, turbo ve hız göstergesi tabanları (motor tamiri / parça değişiminden sonra)"), null) {
            confirm(t("Motor öğrenmeleri sıfırlansın mı?"), t("Uyarılar birkaç sürüş boyunca yeniden öğrenir."), t("Sıfırla")) {
                val keep = ins.fuel.k to ins.fuel.calibrations
                val fresh = Insights()
                fresh.fuel.k = keep.first; fresh.fuel.calibrations = keep.second
                fresh.perf.best0100 = ins.perf.best0100; fresh.perf.best60100 = ins.perf.best60100; fresh.perf.peakKw = ins.perf.peakKw
                fresh.perf.best060mph = ins.perf.best060mph; fresh.perf.best4060mph = ins.perf.best4060mph
                ins.load(fresh.save())
                // load() yalnız dolu alanları yazar: tabanları elle sıfırla
                ins.health.idleN = 0; ins.health.idleRpm = 0.0; ins.health.idleLoad = 0.0
                ins.health.boostN.fill(0); ins.health.boostBase.fill(0.0); ins.health.speedoError = null; ins.health.speedoN = 0
                saveIns()
            }
        })
    }

    /** Not iste, paketi arka planda hazırla (log okuma ~1 sn), paylaşım menüsünü aç. */
    private fun reportProblem() {
        val input = EditText(this).noFullscreen().apply { hint = t("Ne oldu? (ör. bağlanmıyor, devir yanlış gösteriliyor)"); minLines = 2 }
        val box = android.widget.FrameLayout(this).apply { setPadding(ui.dp(20), 0, ui.dp(20), 0); addView(input) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(t("Sorun bildir"))
            .setMessage(if (ProblemReport.SUPPORT_EMAIL.isNotEmpty())
                t("Kısaca ne olduğunu yaz. Sonra e-posta uygulamanı seç; alıcı (%s) hazır gelir.", ProblemReport.SUPPORT_EMAIL)
                else t("Kısaca ne olduğunu yaz. Sonra gönderme yolunu (e-posta, WhatsApp, Telegram…) sen seçersin."))
            .setView(box)
            .setPositiveButton(t("Hazırla")) { _, _ ->
                val note = input.text.toString()
                toast(t("Hazırlanıyor…"))
                Thread {
                    val zip = try { ProblemReport.build(this, note) } catch (e: Exception) { null }
                    runOnUiThread {
                        if (zip == null) { toast(t("Paket hazırlanamadı")); return@runOnUiThread }
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/zip")
                            .putExtra(Intent.EXTRA_STREAM, ShareProvider.uriFor(this, zip))
                            .putExtra(Intent.EXTRA_SUBJECT, t("OBD Dashboard sorun bildirimi"))
                            .apply { if (ProblemReport.SUPPORT_EMAIL.isNotEmpty()) putExtra(Intent.EXTRA_EMAIL, arrayOf(ProblemReport.SUPPORT_EMAIL)) }
                            .putExtra(Intent.EXTRA_TEXT, note.ifBlank { t("OBD Dashboard sorun bildirimi") })
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), t("Sorun bildirimini gönder")))
                    }
                }.start()
            }
            .setNegativeButton(t("Vazgeç"), null)
            .show()
    }

    private fun shareLogs(dir: java.io.File) {
        val files = dir.listFiles { f -> f.name.endsWith(".csv") }?.sortedBy { it.name } ?: emptyList()
        if (files.isEmpty()) { toast(t("Kayıt yok")); return }
        val zip = java.io.File(cacheDir, "share/surus_kayitlari.zip").apply { parentFile?.mkdirs() }
        java.util.zip.ZipOutputStream(zip.outputStream()).use { z ->
            for (f in files) { z.putNextEntry(java.util.zip.ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
        }
        val uri = ShareProvider.uriFor(this, zip)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), t("Kayıtları paylaş")))
    }

    private var devTaps = 0

    private fun about() {
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        header(t("Uygulama"))
        add(ui.item("OBD Dashboard", t("ELM327 adaptörüyle (Bluetooth ya da Wi-Fi) araç göstergesi, navigasyon ve medya ekranı"), null, onClick = null))
        add(ui.item(t("Sürüm"), null, { ver }) {
            if (dash.getBoolean("dev", false)) return@item
            if (++devTaps >= 7) { dash.edit().putBoolean("dev", true).apply(); toast(t("Gelişmiş ayarlar açıldı")); buildNav() }
            else if (devTaps >= 4) toast("${7 - devTaps} kez daha")
        })
        add(ui.item(t("Güncellemeleri denetle"), t("Yeni sürüm yayınlanınca haber verir (GitHub)"), {
            dash.getString("upd_latest", null)?.let { if (Updates.newer(it, ver ?: "0")) "Yeni: $it" else t("Güncel") } ?: ""
        }) {
            toast(t("Denetleniyor…"))
            Thread {
                val r = Updates.fetch()
                runOnUiThread {
                    when {
                        r == null -> toast(t("Denetlenemedi (internet?)"))
                        Updates.newer(r.version, ver ?: "0") -> { dash.edit().putString("upd_latest", r.version).apply(); Updates.offer(this, r) }
                        else -> { dash.edit().putString("upd_latest", r.version).apply(); toast(t("Güncel sürümü kullanıyorsun")) }
                    }
                    rows.forEach { it.refresh() }
                }
            }.start()
        })
        add(ui.toggle(t("Otomatik denetle"), t("Günde en çok bir kez, uygulama açılırken"), { dash.getBoolean("upd_auto", true) }) {
            dash.edit().putBoolean("upd_auto", it).apply()
        })
        add(ui.item(t("Kaynak kod ve sürümler"), "github.com/${Updates.REPO} · GPL-3.0", null) { Updates.open(this, "https://github.com/${Updates.REPO}") })
        add(ui.item(t("Sorun bildir"), t("Bağlantı özeti, uygulama logu ve son 3 sürüş kaydını tek .zip olarak gönder. Şasi no ve adaptör adresi gizlenir"), null) { reportProblem() })
        header(t("Teşekkürler"))
        add(ui.item(t("Harita ve yol tarifi: Yandex Haritalar"), t("Kullanım koşulları (yandex.com/legal/maps_termsofuse)"), null) {
            try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://yandex.com/legal/maps_termsofuse/"))) } catch (_: Exception) {}
        })
        add(ui.item(t("Harita: OpenFreeMap · MapLibre"), t("Harita verisi © OpenStreetMap katkıcıları (ODbL) · © OpenMapTiles · OpenFreeMap · MapLibre (BSD)"), null) {
            try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.openstreetmap.org/copyright"))) } catch (_: Exception) {}
        })
        note(t("Hava durumu: Open-Meteo (open-meteo.com, CC BY 4.0) · Hava simgeleri: Meteocons, Bas Milius (MIT lisansı)"))
        header(t("Önemli"))
        note(t("• Sürüş sırasında uygulamayla uğraşma; ayarları araç dururken yap.\n" +
            "• Gösterilen değerler araç beyninden okunur, yakıt ve uyarılar tahmindir. Aracın kendi göstergeleri ve uyarı ışıkları esastır.\n" +
            "• Uygulama araca yalnız okuma istekleri gönderir. Arıza kodu silme gibi yazma işlemleri yalnız senin onayınla yapılır.\n" +
            "• Uygulama olduğu gibi sunulur; kullanımdan doğacak sonuçlardan geliştirici sorumlu değildir."))
        header(t("Gizlilik"))
        note(t("Sürüş kayıtları ve bildirimler telefondan çıkmaz. Hava durumu için yaklaşık konum (yaklaşık 100\u00A0m) " +
            "Open-Meteo'ya gönderilir. Adres arama ve navigasyon Yandex'ten gelir: navigasyon sürerken konumun, " +
            "aradığın adres ve hedefin Yandex'e gider. Navigasyon dışında harita OpenFreeMap'ten (OpenStreetMap) gelir. Reklam ve takip yok. " +
            "Sorun bildirimi yalnız senin seçtiğin uygulamayla, senin seçtiğin kişiye gider. " +
            "Telefonun Android yedeği açıksa ayarlar (Ev/İş adresleri dahil) Google yedeğine gidebilir. " +
            "Güncelleme denetimi günde bir kez GitHub'a sorar (kapatılabilir)."))
        add(ui.item(t("Kurulum sihirbazını yeniden başlat"), null, null) {
            startActivity(Intent(this, SetupActivity::class.java)); finish()
        })
    }

    private fun advanced() {
        note(t("Geliştirici araçları: araç beyni keşfi ve ham veri. Sıradan kullanım için gerekmez."))
        add(ui.item(t("Teşhis ve geliştirici araçları"), t("Araç testi, gizli değer taraması, blok kaydı, ham TCP köprüsü, canlı ham değerler"), null) {
            startActivity(Intent(this, com.nebi.obdbridge.MainActivity::class.java))
        })
        val dir = java.io.File(getExternalFilesDir(null), "logs")
        add(ui.item(t("Bütün sürüş kayıtlarını paylaş"), t("Ham kayıtlar tek .zip olarak (Sorun bildir yalnız son 3 sürüşü gönderir)"),
            { t("%d kayıt", dir.listFiles { f -> f.name.endsWith(".csv") }?.size ?: 0) }) { shareLogs(dir) })
        add(ui.item(t("Gelişmiş ayarları gizle"), null, null) {
            dash.edit().putBoolean("dev", false).apply(); devTaps = 0; show(0)
        })
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

    @Deprecated("Activity API")
    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        show(section)
        if (code == 1) picker()   // Bluetooth izni sonrası seçici kendiliğinden yeniden açılsın
    }

    private fun picker() {
        val before = BridgeService.spec(this)
        AdapterPicker(this, { requestPermissions(it, 1) }) {
            if (BridgeService.running && BridgeService.spec(this) != before) Conn.stop(this)
            show(section)
        }.show()
    }

    companion object {
        private const val REQ_THEME = 51
        private const val REQ_LANG_IMPORT = 52
        private const val REQ_LANG_EXPORT = 53
        /** Ön plandaki Ayarlar ekranı (tema hazırlığı bitince önizleme buna gösterilir) */
        private var resumedNow: SettingsActivity? = null
        @Volatile private var pendingTheme: Result<Pair<UserThemes.Draft, android.graphics.Bitmap>>? = null
    }
}
