package com.nebi.obdbridge.dash

import android.app.Activity
import android.app.AlertDialog
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.Choreographer
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.nebi.obdbridge.BridgeService
import com.nebi.obdbridge.obd.EngineState
import org.json.JSONArray
import org.json.JSONObject

/**
 * Canlı araç verisi: köprü servisindeki OBD motorunun son değerleri.
 * OBD yokken hız GPS'ten gelir (HEADUNIT.md kararı); diğer değerler boş kalır.
 */
class LiveSource(private val gps: () -> com.nebi.obdbridge.obd.Sample?) : SignalSource {
    private val obd get() = BridgeService.engineStatus?.state == EngineState.LIVE
    override val name get() = when { obd -> "Canlı"; gpsFresh() != null -> "GPS"; else -> "Bağlı değil" }
    override fun sample(key: String) = when {
        obd -> BridgeService.latest[key]
        key == "speed" -> gpsFresh()
        else -> null
    }
    override val online get() = obd || gpsFresh() != null
    override val rpmHz get() = BridgeService.engineStatus?.rpmHz ?: 0.0
    private fun gpsFresh() = gps()?.takeIf { System.currentTimeMillis() - it.ts < 4000 }
}

/**
 * Dashboard ekranı. Yerleşim modülerdir: halkalar ve kutucuklar [WidgetSpec] ile tanımlanır,
 * herhangi bir göstergeye uzun basınca gösterdiği değer değiştirilebilir (ayarlarda saklanır).
 */
class DashboardActivity : Activity(), SensorEventListener {

    private val prefs by lazy { getSharedPreferences("dash", MODE_PRIVATE) }
    private lateinit var model: DashModel
    // Arabada öğrenilen vites oranları kalıcı; demo kendi geçici hafızasını kullanır (karışmasın)
    private var liveGears = GearEstimator()
    private var vehicleJson = ""
    // Demo'nun vites oranları bilindiği için hafıza dolu başlar (öğrenme beklenmesin)
    private val demoGears = GearEstimator.fromRatios(DemoSource.RATIOS.drop(1))
    private val widgets = ArrayList<DashWidget>()
    private val topBars = ArrayList<TopBar>()
    private val brands = ArrayList<BrandLabel>()
    private val roads = ArrayList<RoadView>()
    private val navis = ArrayList<NaviView>()
    private val naviCards = ArrayList<NaviCard>()
    private val mediaCards = ArrayList<MediaCard>()
    private val weather by lazy { Weather(this) }
    private val pills = ArrayList<StatusPill>()
    private val dialsList = ArrayList<DashImageDials>()
    private var tripStart = 0L
    @Volatile private var gpsSpeed: com.nebi.obdbridge.obd.Sample? = null
    private val gpsListener = android.location.LocationListener { loc ->
        // Dururken GPS 1-2 km/h gürültü verir: 3 km/h altı sıfır
        if (loc.hasSpeed()) gpsSpeed = com.nebi.obdbridge.obd.Sample("speed", (loc.speed * 3.6).let { if (it < 3) 0.0 else it }, System.currentTimeMillis())
    }
    private val media by lazy { MediaHub(this).apply { onChange = { mediaCards.forEach { it.postInvalidate() } } } }
    private lateinit var panel: PanelLayout
    private lateinit var root: FrameLayout
    private var tab = 0
    private var theme = DashTheme.IMAGE
    private var imgTheme = ImageTheme.ALL[0]
    private var lastThemeSwitch = 0L
    /** Işık sensörü gündüz temasına geçirdiyse true (seçili tema değişmez) */
    private var sunMode = false
    private var keys: List<String> = emptyList()

    // Yerleşim: sol halka, sağ halka, orta kutucuklar
    private lateinit var left: WidgetSpec
    private lateinit var right: WidgetSpec
    private lateinit var tiles: MutableList<WidgetSpec>

    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(t: Long) {
            render()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Kamera çentiği alanına da uzan (yoksa kenarda siyah şerit kalıyor)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        // İlk açılış: yeni kullanıcı sihirbaza; eski kurulum (adaptör seçilmiş) sihirbazı atlar, profil S40 1.6D
        if (!prefs.getBoolean("setup_done", false)) {
            if (!Conn.configured(this) || Conn.address(this) == null && !Conn.isWifi(this)) { startActivity(android.content.Intent(this, SetupActivity::class.java)); finish(); return }
            prefs.edit().putBoolean("setup_done", true).apply()
        }
        applyVehicle()
        model = DashModel(pickSource(), liveGears)
        model.insights = insights.apply { load(prefs.getString("insights", null)) }
        model.trip = trip
        applySpeedFactor()
        loadLayout()
        root = FrameLayout(this)
        setContentView(root)
        window.setBackgroundDrawable(null)  // zemin rengini kökten çiziyoruz
        build()
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED
            && !prefs.getBoolean("asked_location", false)) {
            prefs.edit().putBoolean("asked_location", true).apply()   // reddedilirse bir daha sorma; GPS hızı yok
            requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        }
    }

    /** Araç profili (Ayarlar > Araç) değiştiyse ölçekleri ve vites tahminini yeniden kur. */
    private fun applyVehicle(): Boolean {
        val v = VehicleStore.load(this)
        val j = v.toJson()
        if (j == vehicleJson) return false
        val gearsChanged = vehicleJson.isNotEmpty() && Vehicle.fromJson(vehicleJson)?.gears != v.gears
        vehicleJson = j
        if (gearsChanged) prefs.edit().remove("gears2").apply()
        liveGears = GearEstimator(v.gearPrior()).apply { load(prefs.getString("gears2", null)) }
        return true
    }

    override fun onNewIntent(i: android.content.Intent) {
        super.onNewIntent(i)
        setIntent(i)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        // Geliştirme: "am start ... --es theme <id>" ile tema önizlemesi (yalnız debug derlemede)
        if (com.nebi.obdbridge.BuildConfig.DEBUG) intent.getStringExtra("theme")?.let {
            prefs.edit().putString("theme", it).apply(); intent.removeExtra("theme")
        }
        if (applyVehicle()) { model.gears = liveGears; build() }
        if (prefs.getBoolean("gears_reset", false)) { liveGears.reset(); prefs.edit().putBoolean("gears_reset", false).apply() }
        model.insights = insights.apply { load(prefs.getString("insights", null)) }
        applySpeedFactor()
        if (prefs.getBoolean("open_strip", false)) { prefs.edit().remove("open_strip").apply(); root.post { chooseStripSlot() } }
        if (prefs.getBoolean("autoconnect", false) && !BridgeService.running && !prefs.getBoolean("demo", false)) Conn.start(this)
        setSource(pickSource())
        com.nebi.obdbridge.NaviListener.rebind(this)
        media.start()
        startGps()
        (getSystemService(SENSOR_SERVICE) as SensorManager).let { sm ->
            sm.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        }
        applyImageTheme(effectiveTheme())
        Choreographer.getInstance().postFrameCallback(frameCb)
    }

    override fun onPause() {
        Choreographer.getInstance().removeFrameCallback(frameCb)
        media.stop()
        stopGps()
        (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(this)
        saveGears()
        prefs.edit().putString("insights", insights.save()).apply()
        super.onPause()
    }

    private fun setSource(s: SignalSource) {
        model.source = s
        model.gears = if (s is DemoSource) demoGears else liveGears
    }

    /** Kullanıcı demo seçmediyse canlı (OBD ya da GPS). */
    private fun pickSource(): SignalSource =
        if (prefs.getBoolean("demo", false)) DemoSource() else LiveSource { gpsSpeed }

    private fun startGps() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        try { lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 500L, 0f, gpsListener, mainLooper) }
        catch (_: Exception) {}
    }

    private fun stopGps() {
        (getSystemService(LOCATION_SERVICE) as android.location.LocationManager).removeUpdates(gpsListener)
        gpsSpeed = null
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        startGps()
    }

    // ---------- Yerleşim ----------

    private fun build() {
        root.removeAllViews()
        widgets.clear(); topBars.clear(); brands.clear(); roads.clear(); navis.clear(); naviCards.clear(); mediaCards.clear(); pills.clear(); dialsList.clear()
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val imageMode = theme.image && land && tab == 0
        val pad = if (imageMode) 0 else dp(if (land) 14 else 10)
        panel = PanelLayout(this).apply { bone = land; theme = this@DashboardActivity.theme; shape = !imageMode }
        root.addView(panel, FrameLayout.LayoutParams(-1, -1).apply { setMargins(pad, pad, pad, pad) })

        val content = when {
            tab == 1 -> detailView(land)
            tab == 2 -> historyView()
            imageMode -> imageLayout()
            land -> landscape()
            else -> portrait()
        }
        panel.addView(content, FrameLayout.LayoutParams(-1, -1))
        keys = widgets.flatMap { it.keys() }.distinct()
        applyTheme(theme)
    }

    private fun landscape(): View {
        // Referanstaki gibi: halkalar iki yanda, üst çubuk panelin üst ortasında, kutucuklar ortada
        val frame = FrameLayout(this)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(18), dp(14), dp(18), dp(10)) }
        row.addView(ring(left), LinearLayout.LayoutParams(0, -1, 1f))
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(44), dp(10), dp(8)) }
        if (theme.premium) mid.addView(brand(), LinearLayout.LayoutParams(-1, dp(20)))
        mid.addView(tileGrid(2, tiles.take(4)), LinearLayout.LayoutParams(-1, 0, 1f))
        mid.addView(navi(), LinearLayout.LayoutParams(-1, dp(56)).apply { setMargins(dp(8), dp(4), dp(8), 0) })
        if (theme.premium) mid.addView(road(), LinearLayout.LayoutParams(-1, dp(64)))
        row.addView(mid, LinearLayout.LayoutParams(0, -1, 0.95f))
        row.addView(ring(right), LinearLayout.LayoutParams(0, -1, 1f))
        frame.addView(row, FrameLayout.LayoutParams(-1, -1))
        val w = resources.displayMetrics.widthPixels
        frame.addView(topBar(), FrameLayout.LayoutParams((w * 0.46).toInt(), dp(40), android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL)
            .apply { topMargin = dp(6) })
        return frame
    }

    /**
     * Görsel teması = araç ekranı (HEADUNIT.md): referans resim, iki yanda halkalar; ortada nav ve medya kartları,
     * altta dört değerlik şerit. Üst çubuk resimdeki yerinde.
     */
    private fun imageLayout(): View {
        val dials = DashImageDials(this, right, imgTheme)
        widgets += dials
        dialsList += dials
        val lay = DashImageLayout(this, dials)
        lay.place(topBar().also { it.showTempClock = false }, DashImageGeom.TOPBAR)
        lay.place(WeatherView(this, weather), DashImageGeom.WEATHER)
        lay.place(ClockView(this), DashImageGeom.CLOCK)
        lay.place(NaviCard(this, prefs) { askPlace(it) }.also { naviCards += it }, DashImageGeom.NAV_CARD)
        lay.place(MediaCard(this, media).also { mediaCards += it }, DashImageGeom.MEDIA_CARD)
        val strip = ValueStrip(this, tiles.take(4))
        widgets += strip
        strip.setOnLongClickListener { chooseStripSlot(); true }
        lay.place(strip, DashImageGeom.STRIP)
        lay.place(StatusPill(this).also { it.visibility = View.INVISIBLE; pills += it }, DashImageGeom.PILL)
        return lay
    }

    /** Şeritteki dört yuvadan birini seçip değerini değiştir. */
    private fun chooseStripSlot() {
        val names = tiles.take(4).mapIndexed { i, sp -> "${i + 1}. ${SignalCatalog[sp.signal]?.label ?: sp.signal}" }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Hangi değer değişsin?")
            .setItems(names.toTypedArray()) { _, i -> chooseSignal(tiles[i]) { build(); saveLayout() } }
            .show()
    }

    /** Ev/İş konumu: "enlem, boylam" ya da harita bağlantısı yapıştırılır. */
    private fun askPlace(key: String) {
        val input = android.widget.EditText(this).apply {
            hint = "41.0123, 28.9876 ya da harita bağlantısı"
            setText(prefs.getString("place_$key", ""))
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (key == "home") "Ev konumu" else "İş konumu")
            .setMessage("Haritada yere uzun basıp koordinatı kopyalayabilirsin.")
            .setView(input)
            .setPositiveButton("Kaydet") { _, _ ->
                val v = NaviCard.parsePlace(input.text.toString())
                if (v != null) prefs.edit().putString("place_$key", v).apply()
                else if (input.text.isBlank()) prefs.edit().remove("place_$key").apply()
                else android.widget.Toast.makeText(this, "Koordinat bulunamadı", android.widget.Toast.LENGTH_SHORT).show()
                naviCards.forEach { it.invalidate() }
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun portrait(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(12)) }
        col.addView(topBar(), LinearLayout.LayoutParams(-1, dp(40)))
        if (theme.premium) col.addView(brand(), LinearLayout.LayoutParams(-1, dp(22)))
        val rings = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rings.addView(ring(left), LinearLayout.LayoutParams(0, -1, 1f))
        rings.addView(ring(right), LinearLayout.LayoutParams(0, -1, 1f))
        val ringH = (resources.displayMetrics.widthPixels - dp(44)) / 2
        col.addView(rings, LinearLayout.LayoutParams(-1, ringH))
        col.addView(navi(), LinearLayout.LayoutParams(-1, dp(64)).apply { setMargins(dp(8), dp(8), dp(8), 0) })
        col.addView(tileGrid(2, tiles), LinearLayout.LayoutParams(-1, 0, 1f))
        if (theme.premium) col.addView(road(), LinearLayout.LayoutParams(-1, dp(90)))
        return col
    }

    /** Detay sekmesi: bütün sinyaller, yaşlarıyla (teşhis için; sürüşte değil). */
    private fun detailView(land: Boolean): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(8)) }
        col.addView(topBar(), LinearLayout.LayoutParams(-1, dp(36)))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val cards = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((t, rows) in listOf("YOLCULUK" to ::tripRows, "PERFORMANS" to ::perfRows, "SAĞLIK" to ::healthRows, "ARIZA KODLARI ›" to ::dtcRows)) {
            val card = InfoCard(this, t, rows)
            if (rows == ::dtcRows) card.setOnClickListener { startActivity(android.content.Intent(this, DtcActivity::class.java)) }
            cards.addView(card, LinearLayout.LayoutParams(0, dp(250), 1f).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
        }
        list.addView(cards)
        val perRow = if (land) 4 else 2
        SignalCatalog.ALL.chunked(perRow).forEach { chunk ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { def ->
                val tile = ValueTile(this, WidgetSpec("tile", def.key), showRate = true)
                widgets += tile
                r.addView(tile, LinearLayout.LayoutParams(0, dp(78), 1f).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
            }
            repeat(perRow - chunk.size) { r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            list.addView(r)
        }
        col.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        return col
    }

    private fun ring(spec: WidgetSpec): View = RingGauge(this, spec).also { g ->
        widgets += g
        g.setOnLongClickListener { chooseSignal(spec) { build(); saveLayout() }; true }
    }

    private fun tileGrid(cols: Int, specs: List<WidgetSpec>): View {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
        specs.chunked(cols).forEach { chunk ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { spec ->
                val t = ValueTile(this, spec)
                widgets += t
                t.setOnLongClickListener { chooseSignal(spec) { build(); saveLayout() }; true }
                r.addView(t, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(8), dp(4), dp(8), dp(4)) })
            }
            grid.addView(r, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        return grid
    }

    private fun brand() = BrandLabel(this, prefs.getString("brand", "PREMIUM")!!.uppercase()).also { brands += it }
    private fun road() = RoadView(this).also { roads += it }
    private fun navi() = NaviView(this).also { it.theme = theme; navis += it }

    private fun topBar() = TopBar(this, listOf("Sürüş", "Detay", "Geçmiş", "Ayarlar")) { onTab(it) }.also {
        it.selected = tab
        // Sinyal çubuklarına dokun: bağlan / bağlantıyı kes
        it.onStatusTap = {
            when {
                prefs.getBoolean("demo", false) -> toast("Demo modu açık (Ayarlar > Ekran)")
                BridgeService.running -> { Conn.stop(this); toast("Bağlantı kesildi") }
                Conn.start(this) -> toast("Bağlanıyor…")
                else -> startActivity(android.content.Intent(this, SettingsActivity::class.java))
            }
        }
        topBars += it
    }

    private fun onTab(i: Int) {
        if (i == 3) {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
            topBars.forEach { it.selected = tab; it.invalidate() }
            return
        }
        if (i != tab) { tab = i; build() }
    }

    // ---------- Kare döngüsü ----------

    private fun render() {
        val frame = model.frame(keys)
        for (w in widgets) {
            w.frame = frame
            w.shift = model.shift
            w.invalidate()
        }
        // Navigasyon: son 30 sn içinde güncellenen manevra varsa göster
        val ns = com.nebi.obdbridge.NaviListener.state?.takeIf { System.currentTimeMillis() - it.ts < 30_000 }
        for (n in navis) {
            if (n.state !== ns) { n.state = ns; n.invalidate() }
            n.visibility = if (ns != null) View.VISIBLE else View.INVISIBLE
        }
        for (n in naviCards) n.state = ns
        trip.update(model.raw("speed"))
        if (trip.startTs != tripStart) { tripStart = trip.startTs; insights.stats.reset() }
        updateInsights()
        weather.refreshIfStale()
        val temp = weather.tempC
        for (t in topBars) {
            t.tempText = if (temp != null) "Hava ${Math.round(temp)}°C" else "Hava —"
            t.sourceName = model.source.name
            t.online = model.source.online
            t.hz = model.source.rpmHz
            t.invalidate()
        }
    }

    private fun updateInsights() {
        val src = model.source
        val obd = src.name == "Canlı" || src is DemoSource   // demoda da uyarılar/yakıt görünsün (tasarım için)
        fun v(k: String) = (if (k == "speed") src.sample(k) else model.raw(k))?.takeIf { System.currentTimeMillis() - it.ts < 5000 }?.value
        insights.update(Inputs(System.currentTimeMillis(), v("rpm"), v("speed"), v("load"), v("map"), v("coolant"),
            v("battery"), v("rail"), model.raw("gear")?.value?.toInt(),
            gpsSpeed?.takeIf { System.currentTimeMillis() - it.ts < 3000 }?.value, obd, v("maf"), v("fuel_rate")))
        val shown = insights.alerts.filter { prefs.getBoolean("alert_${it.kind.name}", true) }
        // Sürüş kaydı için ekranın gösterdikleri (servis 2 Hz'de kaydeder)
        val now = System.currentTimeMillis()
        val d = BridgeService.derived
        fun put(k: String, v: Double?) { if (v == null) d.remove(k) else d[k] = com.nebi.obdbridge.obd.Sample(k, v, now) }
        put("gear", model.raw("gear")?.value)
        put("gps_speed", gpsSpeed?.takeIf { now - it.ts < 3000 }?.value)
        put("fuel_lph", insights.fuelLph)
        put("fuel_cut", if (insights.fuelCut) 1.0 else 0.0)
        put("coast", if (insights.coasting) 1.0 else 0.0)
        put("alerts", insights.alerts.fold(0) { m, a -> m or (1 shl a.kind.ordinal) }.toDouble())
        for (p in pills) p.alerts = shown
        val note = if (!prefs.getBoolean("note_fuelcut", true)) null
            else when { insights.fuelCut -> "yakıt kesik"; insights.coasting && Vehicle.current.manual -> "boşta"; else -> null }
        for (d in dialsList) d.note = note
    }

    private fun applySpeedFactor() {
        val e = insights.health.speedoError
        model.speedFactor = if (prefs.getBoolean("speed_corr", false) && e != null && insights.health.speedoN >= 200) 1 + e else 1.0
    }

    // ---------- Tema ----------

    /** Seçili tema; ışık sensörü gündüzü gördüyse (ve ayar açıksa) Gündüz teması */
    private fun effectiveTheme(): ImageTheme {
        val chosen = ImageTheme.byId(prefs.getString("theme", DEFAULT_THEME))
        return if (sunMode && prefs.getBoolean("auto_day", true)) ImageTheme.DAY else chosen
    }

    private fun applyImageTheme(t: ImageTheme) {
        val changed = t != imgTheme
        imgTheme = t
        Glass.use(t)
        applyTheme(t.dash())
        if (changed) build()   // kadran resimleri temaya bağlı
    }

    private fun applyTheme(t: DashTheme) {
        // premium'da marka yazısı ve yol süsü, görsel temada resim var: yerleşim değişir
        val relayout = t.premium != theme.premium || t.image != theme.image
        theme = t
        if (relayout && ::panel.isInitialized) { build(); return }
        // Premium zemin: derin antrasit dairesel degrade (tam siyah değil)
        // Ekranın ortasından köşelere doğru hafifçe koyulaşan geniş dairesel degrade
        root.background = if (t.image) android.graphics.drawable.ColorDrawable(if (imgTheme.light) 0xFFE6E8EB.toInt() else 0xFF0E1013.toInt())
        else if (t.premium) android.graphics.drawable.GradientDrawable().apply {
            gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(0xFF262B31.toInt(), t.bg, 0xFF0D0F12.toInt())
            gradientRadius = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 0.62f
        } else android.graphics.drawable.ColorDrawable(t.bg)
        brands.forEach { it.theme = t; it.invalidate() }
        roads.forEach { it.theme = t; it.invalidate() }
        navis.forEach { it.theme = t; it.invalidate() }
        if (::panel.isInitialized) panel.theme = t
        widgets.forEach { it.theme = t }
        topBars.forEach { it.theme = t }
    }

    /** Gündüz otomatiği: aydınlıkta Gündüz temasına, karanlıkta seçili temaya; titremesin diye eşik aralığı ve 20 sn kilit. */
    override fun onSensorChanged(e: SensorEvent) {
        if (!prefs.getBoolean("auto_day", true)) return
        val lux = e.values[0]
        val now = System.currentTimeMillis()
        if (now - lastThemeSwitch < 20_000) return
        val sun = when {
            lux > 1500 -> true      // doğrudan gün ışığı (araç içinde, öğlen)
            lux < 200 -> false
            else -> return
        }
        if (sun != sunMode) { sunMode = sun; lastThemeSwitch = now; applyImageTheme(effectiveTheme()) }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    // ---------- Ayarlar ----------

    // ---------- Geçmiş ----------

    /** Solda haftalık karşılaştırma, sağda yolculuk listesi (son 30 gün). Özetler arka planda hesaplanır. */
    private fun historyView(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(8)) }
        col.addView(topBar(), LinearLayout.LayoutParams(-1, dp(36)))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        var weeks: Pair<WeekTotals, WeekTotals>? = null
        var count = -1
        row.addView(InfoCard(this, "BU HAFTA · GEÇEN HAFTA") {
            val (a, b) = weeks ?: return@InfoCard listOf((if (count < 0) "Yükleniyor…" else "Kayıt yok") to "")
            fun two(x: String, y: String) = "$x · $y"
            fun l(v: Double?) = v?.let { "%.1f".format(it) } ?: "—"
            listOf(
                "Yolculuk" to two("${a.trips}", "${b.trips}"),
                "Mesafe" to two("%.0f km".format(a.km), "%.0f km".format(b.km)),
                "Süre" to two(mins(a.ms), mins(b.ms)),
                "Yakıt (tahmini)" to two("%.1f L".format(a.liters), "%.1f L".format(b.liters)),
                "Ort. L/100" to two(l(a.l100), l(b.l100)),
                "Puan" to two(a.score?.toString() ?: "—", b.score?.toString() ?: "—"),
                "Toplam kayıt" to "$count yolculuk",
            )
        }, LinearLayout.LayoutParams(0, -1, 0.85f).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        row.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(0, -1, 1.6f))
        col.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))

        val dir = java.io.File(getExternalFilesDir(null), "logs")
        val gearsCopy = GearEstimator().apply { load(liveGears.save()) }   // özet hesabı canlı öğrenmeyi değiştirmesin
        val k = insights.fuel.k
        Thread({
            val trips = try { HistoryIndex(dir).load(gearsCopy, k) } catch (e: Exception) { emptyList() }
            runOnUiThread {
                weeks = HistoryIndex.weeks(trips, System.currentTimeMillis())
                count = trips.size
                if (trips.isEmpty()) list.addView(Ui(this).text("Henüz kayıtlı yolculuk yok. Adaptör bağlıyken yapılan her sürüş " +
                    "burada görünecek.", 15f, Pal.DIM).apply { setPadding(dp(12), dp(12), dp(12), dp(12)) })
                for (t in trips) {
                    list.addView(TripRow(this, t).apply { setOnClickListener { showTrip(t) } },
                        LinearLayout.LayoutParams(-1, dp(64)).apply { setMargins(dp(6), dp(6), dp(6), 0) })
                }
            }
        }, "history").start()
        return col
    }

    private fun showTrip(t: TripSummary) {
        val f = java.text.SimpleDateFormat("d MMMM EEEE HH:mm", java.util.Locale("tr"))
        fun p(ms: Long) = if (t.durationMs > 0) "%${ms * 100 / t.durationMs}" else "—"
        val lines = listOf(
            "Süre: ${mins(t.durationMs)}",
            "Mesafe: %.1f km".format(t.km),
            "Ortalama / maksimum: %.0f / %.0f km/h".format(t.avgKmh, t.maxKmh),
            "Tahmini yakıt: %.2f L".format(t.liters) + (t.l100?.let { " · %.1f L/100".format(it) } ?: ""),
            "Sürüş puanı: ${t.score}  (sert kalkış ${t.harshAccel}, sert fren ${t.harshBrake})",
            "Rölanti: ${p(t.idleMs)} · boşta süzülme: ${p(t.coastMs)}",
            "Yakıt kesik: %.1f km".format(t.cutKm),
            "Vites süresi: " + t.gearPct.withIndex().filter { it.value > 0 }.joinToString("  ") { "${it.index + 1}: %${it.value}" }
                .ifEmpty { "—" },
        )
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(f.format(java.util.Date(t.start)))
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton("Tamam", null)
            .show()
    }

    // ---------- Detay kartları ----------

    private fun mins(ms: Long) = (ms / 60_000).let { if (it >= 60) "${it / 60} sa ${it % 60} dk" else "$it dk" }
    private fun pct(part: Long, total: Long) = if (total <= 0) "—" else "%${part * 100 / total}"

    private fun tripRows(): List<Pair<String, String>> {
        val st = insights.stats
        val gears = (1..Vehicle.current.gears).filter { st.gearMs.getOrElse(it) { 0 } > 0 }.let { g ->
            val tot = g.sumOf { st.gearMs[it] }
            if (tot == 0L) "—" else g.joinToString(" ") { "$it:${st.gearMs[it] * 100 / tot}" }
        }
        val avg = if (trip.km >= 0.5) "%.1f L/100".format(st.liters / trip.km * 100) else "—"
        return listOf(
            "Süre" to mins(trip.durationMs),
            "Mesafe" to "%.1f km".format(trip.km),
            "Ort. / maks." to (if (trip.avgKmh > 0) "%.0f / %.0f km/h".format(trip.avgKmh, trip.maxKmh) else "—"),
            "Tahmini yakıt" to "%.2f L · %s".format(st.liters, avg),
            "Sürüş puanı" to "${st.score(trip)}  (sert ${st.harshAccel}↑ ${st.harshBrake}↓)",
            "Rölanti · boşta" to "${pct(st.idleMs, trip.durationMs)} · ${pct(st.coastMs, trip.durationMs)}",
            "Yakıt kesik" to "%.1f km".format(st.cutKm),
            "Vites (%)" to gears,
        )
    }

    private fun perfRows(): List<Pair<String, String>> {
        val p = insights.perf
        fun t(v: Double?) = v?.let { "%.1f sn".format(it) } ?: "—"
        return listOf(
            "Durum" to when (p.state) { "hazır" -> "Hazır (kalkışta başlar)"; "ölçülüyor" -> "Ölçülüyor…"; else -> "Durunca hazır" },
            "0–100 son" to t(p.last0100),
            "0–100 en iyi" to t(p.best0100),
            "60–100 son" to t(p.last60100),
            "60–100 en iyi" to t(p.best60100),
            "Güç (anlık)" to (p.kw?.let { "%.0f kW · %.0f hp".format(it, it * 1.341) } ?: "—"),
            "Güç (en yüksek)" to (if (p.peakKw > 0) "%.0f kW · %.0f hp".format(p.peakKw, p.peakKw * 1.341) else "—"),
        )
    }

    /** Detay'daki arıza kartı: canlı 0101 sayısı ve son okuma; dokununca DtcActivity */
    private fun dtcRows(): List<Pair<String, String>> {
        val mil = model.raw("mil")?.value
        val n = model.raw("dtc_count")?.value
        val last = DtcActivity.last
        return listOf(
            "Arıza lambası" to when (mil) { null -> "—"; 0.0 -> "sönük"; else -> "YANIYOR" },
            "Kayıtlı kod" to (n?.let { "%.0f".format(it) } ?: "—"),
            "Son okuma" to (last?.let { java.text.SimpleDateFormat("HH:mm", java.util.Locale("tr")).format(java.util.Date(it.ts)) +
                " · ${it.stored.size + it.pending.size} kod" } ?: "yok"),
            "" to "Okumak için dokun",
        )
    }

    private fun healthRows(): List<Pair<String, String>> {
        val h = insights.health
        val v = Vehicle.current
        val bat = model.raw("battery")?.value
        val turboLearned = h.boostN.count { it >= 100 }
        val rows = mutableListOf(
            "Şarj" to (bat?.let { "%.1f V · %s".format(it, when (h.charge) {
                Health.Charge.OK -> "normal"; Health.Charge.LOW -> "düşük"; Health.Charge.HIGH -> "yüksek"; Health.Charge.WEAK -> "akü zayıf" }) } ?: "—"),
            "Rölanti tabanı" to (if (h.idleN >= 300) "%.0f rpm · yük %%%.0f".format(h.idleRpm, h.idleLoad) else "öğreniliyor (${h.idleN * 100 / 300}%)"),
        )
        if (v.fuel == Fuel.DIESEL) rows += "DPF temizlenme" to "${if (h.dpfActive) "ŞU AN · " else ""}${h.dpfCount} kez"
        if (v.turbo) rows += "Turbo" to (if (h.turboLow) "normalden düşük" else if (turboLearned >= 3) "normal" else "öğreniliyor ($turboLearned/11)")
        rows += "Hız göstergesi" to (h.speedoError?.let { if (h.speedoN >= 200) "%+.1f%% (GPS'e göre)".format(it * 100) else "ölçülüyor" } ?: "GPS + OBD gerekli")
        rows += "Yakıt kaynağı" to insights.fuel.source(model.raw("maf") != null, model.raw("fuel_rate") != null, v.fuel)
        rows += "Yakıt kalibrasyonu" to (if (insights.fuel.calibrations == 0) "yok (Ayarlar > Yakıt)" else "${insights.fuel.calibrations} dolum")
        return rows
    }

    /** Uzun basınca: bu yuvada hangi değer gösterilsin. */
    private fun chooseSignal(spec: WidgetSpec, done: () -> Unit) {
        val defs = SignalCatalog.ALL.filter { it.key != "gear" || spec.type == "tile" }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Bu göstergede ne görünsün?")
            .setItems(defs.map { it.label + if (it.unit.isNotEmpty()) " (${it.unit})" else "" }.toTypedArray()) { _, i ->
                spec.signal = defs[i].key
                done()
            }
            .show()
    }

    // ---------- Kalıcı ayarlar ----------

    private fun defaultLayout() {
        left = WidgetSpec("ring", "speed")
        right = WidgetSpec("ring", "rpm", center = "gear", bar = "coolant")
        // Yatayda ilk 4'ü, dikeyde 6'sı görünür
        tiles = mutableListOf(WidgetSpec("tile", "boost"), WidgetSpec("tile", "load"),
            WidgetSpec("tile", "battery"), WidgetSpec("tile", "coolant"),
            WidgetSpec("tile", "iat"), WidgetSpec("tile", "rail"))
    }

    private fun loadLayout() {
        defaultLayout()
        val s = prefs.getString("layout", null) ?: return
        try {
            val o = JSONObject(s)
            fun spec(j: JSONObject) = WidgetSpec(j.getString("type"), j.getString("signal"),
                j.optString("center").ifEmpty { null }, j.optString("bar").ifEmpty { null })
            left = spec(o.getJSONObject("left"))
            right = spec(o.getJSONObject("right"))
            val a = o.getJSONArray("tiles")
            val saved = MutableList(a.length()) { spec(a.getJSONObject(it)) }
            // Eski kayıtta daha az kutucuk varsa eksikleri varsayılanla tamamla
            tiles = (saved + tiles.drop(saved.size)).toMutableList()
        } catch (e: Exception) {
            defaultLayout()  // bozuk kayıt: varsayılanla devam
        }
    }

    private fun saveLayout() {
        fun j(s: WidgetSpec) = JSONObject().put("type", s.type).put("signal", s.signal)
            .put("center", s.center ?: "").put("bar", s.bar ?: "")
        val o = JSONObject().put("left", j(left)).put("right", j(right))
            .put("tiles", JSONArray().apply { tiles.forEach { put(j(it)) } })
        prefs.edit().putString("layout", o.toString()).apply()
    }

    /** v2 histogram kaydı; v1'in "gears" kaydı sahte oranlar içerebildiği için okunmaz. */
    private fun saveGears() {
        prefs.edit().putString("gears2", liveGears.save()).remove("gears").apply()
    }

    // ---------- Yardımcılar ----------

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            // İçerik sistem çubuklarının ve çentiğin arkasına uzansın (yoksa kenarda pencere zemini şerit kalıyor)
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
        /** Süreç boyunca tek yolculuk (sekme/yön değişince sıfırlanmasın) */
        val trip = Trip()
        val insights = Insights()
        /** Araç ekranı referans görselle açılır (HEADUNIT.md) */
        const val DEFAULT_THEME = "image"
    }
}
