package com.nebi.obdbridge.dash

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
            Section("Bağlantı", ::connection),
            Section("Araç", ::vehicle),
            Section("Ekran", ::display),
            Section("Navigasyon ve medya", ::naviMedia),
            Section("Yakıt", ::fuel),
            Section("Uyarılar", ::alerts),
            Section("Kayıtlar", ::logs),
            Section("Hakkında", ::about),
            Section("Gelişmiş", ::advanced) { dash.getBoolean("dev", false) },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        VehicleStore.load(this)
        section = intent.getIntExtra("section", 0)
        val root = ui.row().apply { setBackgroundColor(Pal.BG); gravity = android.view.Gravity.TOP }
        nav = ui.column(16)
        val navScroll = ScrollView(this).apply { addView(nav); isVerticalScrollBarEnabled = false }
        root.addView(navScroll, LinearLayout.LayoutParams(0, -1, 0.32f))
        pane = ui.column(16)
        scroll = ScrollView(this).apply { addView(pane) }
        root.addView(scroll, LinearLayout.LayoutParams(0, -1, 0.68f))
        setContentView(root)
        buildNav()
        show(section)
    }

    override fun onResume() {
        super.onResume()
        hideBars()
        show(section)   // izin ekranından dönünce durumlar güncellensin
        pane.post(ticker)
    }

    override fun onPause() {
        pane.removeCallbacks(ticker)
        super.onPause()
    }

    private fun buildNav() {
        nav.removeAllViews()
        nav.addView(ui.text("‹  Ayarlar", 22f, light = true).apply {
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
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun confirm(title: String, msg: String, yes: String, act: () -> Unit) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title).setMessage(msg)
            .setPositiveButton(yes) { _, _ -> act(); show(section) }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private var vehicle: Vehicle
        get() = Vehicle.current
        set(v) { VehicleStore.save(this, v); show(section) }

    // ---------- bölümler ----------

    private fun connection() {
        header("Durum")
        add(ui.item("Bağlantı", "Adaptör → araç beyni bağlantısının şu anki durumu", { Conn.status(this) }, onClick = null))
        add(ui.item(if (BridgeService.running) "Bağlantıyı kes" else "Bağlan",
            if (BridgeService.running) "Adaptörle bağlantıyı kapatır" else "Kontak açıkken bas; motor bulununca göstergeler canlanır",
            null) {
            if (BridgeService.running) Conn.stop(this)
            else if (!Conn.start(this)) toast("Önce adaptör seç")
            pane.postDelayed({ show(section) }, 400)
        })
        header("Adaptör")
        add(ui.item("OBD adaptörü", "Telefonla eşleştirilmiş ELM327 Bluetooth adaptörü", { Conn.name(this, Conn.address(this)) ?: "Seçilmedi" }) {
            val devs = Conn.devices(this)
            if (!Conn.hasBt(this)) { requestPermissions(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), 1); return@item }
            if (devs.isEmpty()) { toast("Eşleşmiş cihaz yok. Önce Bluetooth ayarlarından adaptörü eşleştir."); Conn.openBtSettings(this); return@item }
            choose("Adaptör seç", devs.map { "${Conn.label(it)}  (${it.address})" }, devs.indexOfFirst { it.address == Conn.address(this) }) {
                if (BridgeService.running) Conn.stop(this)
                Conn.setAddress(this, devs[it].address)
            }
        })
        add(ui.item("Adaptör nasıl eşleştirilir?", "Listede adaptör yoksa", null) {
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Adaptörü eşleştirme")
                .setMessage("1. Adaptörü aracın OBD soketine tak (genelde direksiyonun sol altında).\n" +
                    "2. Kontağı aç.\n3. Telefonun Bluetooth ayarlarında adaptörü bul (OBDII, OBD, ELM327, V-LINK gibi).\n" +
                    "4. PIN sorarsa 1234 ya da 0000 dene.\n5. Bu ekrana dönüp adaptörü seç.\n\n" +
                    "Yalnız Bluetooth Classic (SPP) ELM327 adaptörler desteklenir. Wi-Fi ve yalnız BLE modelleri çalışmaz.")
                .setPositiveButton("Bluetooth ayarlarını aç") { _, _ -> Conn.openBtSettings(this) }
                .setNegativeButton("Tamam", null).show()
        })
        add(ui.toggle("Açılışta otomatik bağlan", "Uygulama açılınca seçili adaptöre kendiliğinden bağlanır",
            { dash.getBoolean("autoconnect", false) }) { dash.edit().putBoolean("autoconnect", it).apply() })
    }

    private fun vehicle() {
        val v = vehicle
        note("Göstergelerin ölçeği, vites tahmini ve uyarılar bu bilgilere göre ayarlanır.")
        header("Motor")
        add(ui.item("Yakıt tipi", "Dizel uyarıları (DPF) ve yakıt hesabı buna göre", { if (v.fuel == Fuel.DIESEL) "Dizel" else "Benzin" }) {
            choose("Yakıt tipi", listOf("Dizel", "Benzin"), v.fuel.ordinal) {
                val f = Fuel.entries[it]
                vehicle = v.copy(fuel = f, redline = if (f == Fuel.DIESEL) 4500 else 6500)
            }
        })
        add(ui.toggle("Turbo", "Turbo basıncı göstergesi ve turbo uyarısı", { v.turbo }) { vehicle = v.copy(turbo = it) })
        add(ui.item("Kırmızı bölge", "Devir göstergesinin ölçeği ve uyarı rengi buna göre", { "${v.redline} rpm" }) {
            val opts = (4000..7500 step 500).toList()
            choose("Kırmızı bölge (rpm)", opts.map { "$it rpm" }, opts.indexOf(v.redline)) { vehicle = v.copy(redline = opts[it]) }
        })
        header("Şanzıman")
        add(ui.item("Şanzıman", "Manuelde vites, devir/hız oranından tahmin edilir", { if (v.manual) "Manuel" else "Otomatik" }) {
            choose("Şanzıman", listOf("Manuel", "Otomatik"), v.gearbox.ordinal) { vehicle = v.copy(gearbox = Gearbox.entries[it]) }
        })
        if (v.manual) {
            add(ui.item("İleri vites sayısı", null, { "${v.gears}" }) {
                choose("İleri vites sayısı", listOf("4", "5", "6"), v.gears - 4) { vehicle = v.copy(gears = it + 4) }
            })
            add(ui.item("Vites öğrenmesini sıfırla", "Vites yanlış gösteriliyorsa: birkaç dakikalık sürüşte yeniden öğrenilir", null) {
                confirm("Vites öğrenmesi sıfırlansın mı?", "Öğrenilen vites oranları silinir.", "Sıfırla") {
                    dash.edit().remove("gears2").putBoolean("gears_reset", true).apply()
                }
            })
        }
        header("Gösterge")
        add(ui.item("Hız göstergesi üst sınırı", null, { "${v.speedMax} km/h" }) {
            val opts = listOf(200, 220, 240, 260)
            choose("Hız göstergesi (km/h)", opts.map { "$it km/h" }, opts.indexOf(v.speedMax)) { vehicle = v.copy(speedMax = opts[it]) }
        })
        add(ui.toggle("Volvo logosu", "Ekranın üst ortasındaki VOLVO yazısı", { v.logo }) { vehicle = v.copy(logo = it) })
        add(ui.toggle("Hız düzeltmesi (GPS)", "Araç göstergeleri genelde %3–7 fazla gösterir; GPS'e göre düzeltir",
            { dash.getBoolean("speed_corr", false) }) { dash.edit().putBoolean("speed_corr", it).apply() })
    }

    private fun display() {
        header("Görünüm")
        val themes = ImageTheme.ALL
        val cur = ImageTheme.byId(dash.getString("theme", DashboardActivity.DEFAULT_THEME))
        add(ui.item("Tema", "Göstergelerin görünümü", { cur.name }) {
            choose("Tema", themes.map { it.name }, themes.indexOf(cur)) { dash.edit().putString("theme", themes[it].id).apply() }
        })
        add(ui.toggle("Güneşte Gündüz temasına geç", "Ortam ışığı çok artınca okunurluk için açık temaya, kararınca seçili temaya döner",
            { dash.getBoolean("auto_day", true) }) { dash.edit().putBoolean("auto_day", it).apply() })
        add(ui.item("Alt şeritteki değerler", "Ana ekranda yol çiziminin iki yanındaki 4 değer. Ana ekranda şeride uzun basarak da değişir", null) {
            dash.edit().putBoolean("open_strip", true).apply(); finish()
        })
        header("Deneme")
        add(ui.toggle("Demo modu", "Araç olmadan sahte sürüş verisiyle uygulamayı dene",
            { dash.getBoolean("demo", false) }) { dash.edit().putBoolean("demo", it).apply() })
    }

    private fun naviMedia() {
        header("İzin")
        add(ui.item("Bildirim erişimi", "Navigasyonun dönüş oku ve çalan müziğin kontrolü için gerekli. Bildirimler telefondan dışarı gönderilmez",
            { if (NaviListener.hasAccess(this)) "Verildi ✓" else "Verilmedi" }) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        header("Hızlı hedefler")
        for ((k, label) in listOf("home" to "Ev", "work" to "İş")) {
            add(ui.item("$label konumu", "Ana ekrandaki $label düğmesi bu adrese Yandex Navigator'da rota açar",
                { dash.getString("place_$k", null) ?: "Girilmedi" }) { askPlace(k, label) })
        }
        note("Desteklenen navigasyon: Yandex Navigator (dönüş oku ve rota). Google Haritalar'ın dönüş oku da okunur.")
    }

    private fun askPlace(k: String, label: String) {
        val input = EditText(this).apply { hint = "41.0123, 28.9876 ya da harita bağlantısı"; setText(dash.getString("place_$k", "")) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("$label konumu")
            .setMessage("Haritada yere uzun basıp koordinatı ya da paylaş bağlantısını kopyalayıp buraya yapıştır.")
            .setView(input)
            .setPositiveButton("Kaydet") { _, _ ->
                val v = NaviCard.parsePlace(input.text.toString())
                when {
                    v != null -> dash.edit().putString("place_$k", v).apply()
                    input.text.isBlank() -> dash.edit().remove("place_$k").apply()
                    else -> toast("Koordinat bulunamadı")
                }
                show(section)
            }
            .setNegativeButton("Vazgeç", null).show()
    }

    private val ins get() = DashboardActivity.insights

    private fun fuel() {
        note("Çoğu araç yakıt tüketimini OBD'den vermez. Uygulama benzinlide hava debisinden (MAF), dizelde motor yükünden " +
            "tahmin eder. Birkaç depo dolumu girersen tahmin aracına göre düzelir.")
        header("Kalibrasyon")
        add(ui.item("Depo doldurdum", "Depoyu tam doldurunca pompadaki litreyi gir", {
            if (ins.fuel.calibrations == 0) "Kalibre edilmedi" else "${ins.fuel.calibrations} dolum"
        }) { askFill() })
        add(ui.item("Son dolumdan beri", null, { "%.0f km · ~%.1f L".format(ins.fuel.km, ins.fuel.estLiters) }, onClick = null))
        add(ui.item("Yakıt tahminini sıfırla", "Kalibrasyonu fabrika değerine döndürür", null) {
            confirm("Yakıt tahmini sıfırlansın mı?", "Depo dolumlarıyla yapılan düzeltme silinir.", "Sıfırla") {
                ins.fuel.k = FuelModel.DEFAULT_K; ins.fuel.calibrations = 0; ins.fuel.estLiters = 0.0; ins.fuel.km = 0.0; saveIns()
            }
        })
        note("Ana ekranda yakıtı görmek için alt şeride uzun basıp \"Tüketim\" ya da \"Ort. tüketim\" seç.")
    }

    private fun askFill() {
        val input = EditText(this).apply {
            hint = "Litre (ör. 42,5)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Depo doldurdum")
            .setMessage("Depoyu tam doldurduktan sonra pompadaki litreyi gir. İlk tam dolum yalnız başlangıç noktasıdır.")
            .setView(input)
            .setPositiveButton("Kaydet") { _, _ ->
                val l = input.text.toString().replace(',', '.').toDoubleOrNull()
                toast(if (l != null && ins.fuel.fillUp(l)) "Yakıt tahmini düzeltildi" else "Başlangıç kaydedildi")
                saveIns(); show(section)
            }
            .setNegativeButton("Vazgeç", null).show()
    }

    private fun saveIns() = dash.edit().putString("insights", ins.save()).apply()

    private fun alerts() {
        note("Uyarılar ana ekranın üst ortasında kısa bir şerit olarak görünür. Değerler araç beyninden okunur ama " +
            "yorumlar tahmindir; aracın kendi uyarı ışıkları her zaman önceliklidir.")
        val v = vehicle
        fun t(kind: AlertKind, title: String, sum: String) =
            add(ui.toggle(title, sum, { dash.getBoolean("alert_${kind.name}", true) }) { dash.edit().putBoolean("alert_${kind.name}", it).apply() })
        if (v.fuel == Fuel.DIESEL) t(AlertKind.DPF, "DPF temizlenmesi", "Partikül filtresi temizlenirken motoru kapatmaman için uyarır")
        if (v.turbo) t(AlertKind.TURBO_LOW, "Turbo basıncı düşük", "Tam gazda turbo basıncı alışılmışın altına düşerse (hortum kaçağı vb.)")
        t(AlertKind.CHARGE_LOW, "Şarj düşük", "Motor çalışırken akü voltajı 13,2 V altında kalırsa (şarj dinamosu)")
        t(AlertKind.CHARGE_HIGH, "Şarj voltajı yüksek", "14,9 V üstü (voltaj regülatörü)")
        t(AlertKind.BATTERY_WEAK, "Akü zayıf", "Kontak açık, motor kapalıyken 12,2 V altı")
        t(AlertKind.COLD, "Motor soğuk", "Motor ısınmadan yüksek devire çıkınca uyarır")
        add(ui.toggle("Yakıt kesik / boşta notu", "Devir göstergesinde küçük not (dizelde motor freni = sıfır yakıt)",
            { dash.getBoolean("note_fuelcut", true) }) { dash.edit().putBoolean("note_fuelcut", it).apply() })
    }

    private fun logs() {
        val dir = java.io.File(getExternalFilesDir(null), "logs")
        header("Sürüş kaydı")
        add(ui.toggle("Her sürüşü kaydet", "Geçmiş sekmesi ve yolculuk istatistikleri için. Kayıtlar yalnız bu telefonda, 30 gün saklanır",
            { Conn.prefs(this).getBoolean(BridgeService.PREF_DRIVE_LOG, true) }) {
            Conn.prefs(this).edit().putBoolean(BridgeService.PREF_DRIVE_LOG, it).apply()
        })
        add(ui.item("Kayıtları paylaş", "Tüm sürüş kayıtlarını tek bir .zip olarak gönder (ör. hata bildirmek için)",
            { "${dir.listFiles { f -> f.name.endsWith(".csv") }?.size ?: 0} kayıt" }) { shareLogs(dir) })
        add(ui.item("Tüm kayıtları sil", null, null, danger = true) {
            confirm("Tüm sürüş kayıtları silinsin mi?", "Geçmiş sekmesi boşalır. Geri alınamaz.", "Sil") {
                dir.listFiles()?.forEach { it.delete() }
            }
        })
        header("Öğrenilenler")
        add(ui.item("Performans rekorlarını sıfırla", "0–100, 60–100 ve en yüksek güç", null) {
            confirm("Rekorlar sıfırlansın mı?", "", "Sıfırla") { ins.perf.best0100 = null; ins.perf.best60100 = null; ins.perf.peakKw = 0.0; saveIns() }
        })
        add(ui.item("Motor öğrenmelerini sıfırla", "Rölanti, turbo ve hız göstergesi tabanları (motor tamiri / parça değişiminden sonra)", null) {
            confirm("Motor öğrenmeleri sıfırlansın mı?", "Uyarılar birkaç sürüş boyunca yeniden öğrenir.", "Sıfırla") {
                val keep = ins.fuel.k to ins.fuel.calibrations
                val fresh = Insights()
                fresh.fuel.k = keep.first; fresh.fuel.calibrations = keep.second
                fresh.perf.best0100 = ins.perf.best0100; fresh.perf.best60100 = ins.perf.best60100; fresh.perf.peakKw = ins.perf.peakKw
                ins.load(fresh.save())
                // load() yalnız dolu alanları yazar: tabanları elle sıfırla
                ins.health.idleN = 0; ins.health.idleRpm = 0.0; ins.health.idleLoad = 0.0
                ins.health.boostN.fill(0); ins.health.boostBase.fill(0.0); ins.health.speedoError = null; ins.health.speedoN = 0
                saveIns()
            }
        })
    }

    private fun shareLogs(dir: java.io.File) {
        val files = dir.listFiles { f -> f.name.endsWith(".csv") }?.sortedBy { it.name } ?: emptyList()
        if (files.isEmpty()) { toast("Kayıt yok"); return }
        val zip = java.io.File(cacheDir, "share/surus_kayitlari.zip").apply { parentFile?.mkdirs() }
        java.util.zip.ZipOutputStream(zip.outputStream()).use { z ->
            for (f in files) { z.putNextEntry(java.util.zip.ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
        }
        val uri = ShareProvider.uriFor(this, zip)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Kayıtları paylaş"))
    }

    private var devTaps = 0

    private fun about() {
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        header("Uygulama")
        add(ui.item("OBD Dashboard", "ELM327 Bluetooth adaptörüyle araç göstergesi, navigasyon ve medya ekranı", null, onClick = null))
        add(ui.item("Sürüm", null, { ver }) {
            if (dash.getBoolean("dev", false)) return@item
            if (++devTaps >= 7) { dash.edit().putBoolean("dev", true).apply(); toast("Gelişmiş ayarlar açıldı"); buildNav() }
            else if (devTaps >= 4) toast("${7 - devTaps} kez daha")
        })
        header("Önemli")
        note("• Sürüş sırasında uygulamayla uğraşmayın; ayarları araç dururken yapın.\n" +
            "• Gösterilen değerler araç beyninden okunur, yakıt ve uyarılar tahmindir. Aracın kendi göstergeleri ve uyarı ışıkları esastır.\n" +
            "• Uygulama araca yalnız okuma istekleri gönderir. Arıza kodu silme gibi yazma işlemleri yalnız sizin onayınızla yapılır.\n" +
            "• Uygulama olduğu gibi sunulur; kullanımdan doğacak sonuçlardan geliştirici sorumlu değildir.")
        header("Gizlilik")
        note("Sürüş kayıtları, konumlar ve bildirimler telefondan çıkmaz. Yalnız hava durumu için yaklaşık konum " +
            "(yaklaşık 100 m) Open-Meteo'ya gönderilir. Reklam ve takip yok.")
        add(ui.item("Kurulum sihirbazını yeniden başlat", null, null) {
            startActivity(Intent(this, SetupActivity::class.java)); finish()
        })
    }

    private fun advanced() {
        note("Geliştirici araçları: araç beyni keşfi ve ham veri. Sıradan kullanım için gerekmez.")
        add(ui.item("Teşhis ve geliştirici araçları", "Araç testi, gizli değer taraması, blok kaydı, ham TCP köprüsü, canlı ham değerler", null) {
            startActivity(Intent(this, com.nebi.obdbridge.MainActivity::class.java))
        })
        add(ui.item("Gelişmiş ayarları gizle", null, null) {
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
    }
}
