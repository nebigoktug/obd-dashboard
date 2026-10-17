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
    private val naviCards = ArrayList<NaviCard>()
    private val mediaCards = ArrayList<MediaCard>()
    private val weather by lazy { Weather(this) }
    private val pills = ArrayList<StatusPill>()
    private val dialsList = ArrayList<DashImageDials>()
    private var tripStart = 0L
    @Volatile private var gpsSpeed: com.nebi.obdbridge.obd.Sample? = null
    /** Dönüşe yaklaşırken devir göstergesinin yerinde harita (TurnWatch karar verir) */
    private val turnWatch = TurnWatch()
    private val turnMaps = ArrayList<TurnMapView>()
    private var resumed = false
    private val gpsListener = android.location.LocationListener { loc ->
        // Dururken GPS 1-2 km/h gürültü verir: 3 km/h altı sıfır
        if (loc.hasSpeed()) gpsSpeed = com.nebi.obdbridge.obd.Sample("speed", (loc.speed * 3.6).let { if (it < 3) 0.0 else it }, System.currentTimeMillis())
        lastGpsFix = System.currentTimeMillis()
        if (turnDemoT0 == 0L) feedTurnMaps(loc.latitude, loc.longitude, if (loc.hasBearing()) loc.bearing else null, if (loc.hasSpeed()) loc.speed * 3.6 else null,
            accM = if (loc.hasAccuracy()) loc.accuracy else null)
    }
    private var lastGpsFix = 0L
    /** İçeride/garajda GPS bulamazsa harita en azından yaklaşık konumda açılsın (hız için kullanılmaz) */
    private val netListener = android.location.LocationListener { loc ->
        if (turnDemoT0 == 0L && System.currentTimeMillis() - lastGpsFix > 15_000) feedTurnMaps(loc.latitude, loc.longitude, null, null, coarse = true)
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
        model.motion = motion
        model.trip = trip
        applySpeedFactor()
        loadLayout()
        Nav.restore(this)
        voice = NavVoice(this)
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

    override fun onStart() { super.onStart(); turnMaps.forEach { it.onStart() } }
    override fun onStop() { turnMaps.forEach { it.onStop() }; super.onStop() }
    override fun onDestroy() { turnMaps.forEach { it.onDestroy() }; voice?.shutdown(); voice = null; super.onDestroy() }
    override fun onLowMemory() { super.onLowMemory(); turnMaps.forEach { it.onLowMemory() } }

    override fun onResume() {
        super.onResume()
        resumed = true
        turnMaps.forEach { it.onResume() }
        // Son kullanılanlardan açılış aynı intent'i yeniden verir → demo yeniden başlamasın
        if (com.nebi.obdbridge.BuildConfig.DEBUG && intent.getBooleanExtra("turn_demo", false)
            && intent.flags and android.content.Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) {
            intent.removeExtra("turn_demo"); turnDemoT0 = System.currentTimeMillis()
            // Rota denemesi: dönüşten sonra 150° yönünde ~1,5 km ileride bir hedef
            Nav.start(this, 41.0175 + 555 * 0.5 / 111_195.0 - 1500 * 0.866 / 111_195.0,
                28.9390 + (555 * 0.866 + 1500 * 0.5) / (111_195.0 * kotlin.math.cos(Math.toRadians(41.0175))), "Demo")
        }
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
        if (prefs.getBoolean("autoconnect", true) && !BridgeService.running && !prefs.getBoolean("demo", false)) Conn.start(this)
        Updates.checkDaily(this)
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
        resumed = false
        turnMaps.forEach { it.onPause() }
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

    private val gpsPacer = GpsPacer()
    private var gpsInterval = 0L

    private fun startGps(interval: Long = GpsPacer.FAST) {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        try {
            lm.removeUpdates(gpsListener)
            lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, interval, 0f, gpsListener, mainLooper)
            if (gpsInterval == 0L) {
                // İlk açılış: son bilinen konumla başla, GPS gelene kadar ağ konumu
                if (turnDemoT0 == 0L) lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                    .maxByOrNull { it.time }?.let { feedTurnMaps(it.latitude, it.longitude, null, null, coarse = true) }
                if (lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER))
                    lm.requestLocationUpdates(android.location.LocationManager.NETWORK_PROVIDER, 10_000L, 0f, netListener, mainLooper)
            }
            gpsInterval = interval
        } catch (_: Exception) {}
    }

    /** Evde masada açık kalınca pil yemesin: hareketsizken GPS seyrek (GpsPacer) */
    private fun paceGps() {
        if (gpsInterval == 0L) return
        val live = BridgeService.engineStatus?.state == com.nebi.obdbridge.obd.EngineState.LIVE
        val want = gpsPacer.intervalMs(live || Nav.active, model.source is DemoSource, gpsSpeed?.value)
        if (want != gpsInterval) startGps(want)
    }

    private fun stopGps() {
        gpsInterval = 0L
        (getSystemService(LOCATION_SERVICE) as android.location.LocationManager).run { removeUpdates(gpsListener); removeUpdates(netListener) }
        gpsSpeed = null
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        startGps()
    }

    // ---------- Yerleşim ----------

    private fun build() {
        root.removeAllViews()
        turnMaps.forEach { if (resumed) it.onPause(); it.onStop(); it.onDestroy() }
        turnMaps.clear()
        widgets.clear(); topBars.clear(); naviCards.clear(); mediaCards.clear(); pills.clear(); dialsList.clear()
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val imageMode = tab == 0
        val pad = if (imageMode) 0 else dp(if (land) 14 else 10)
        panel = PanelLayout(this).apply { bone = land; theme = this@DashboardActivity.theme; shape = !imageMode }
        root.addView(panel, FrameLayout.LayoutParams(-1, -1).apply { setMargins(pad, pad, pad, pad) })

        val content = when {
            tab == 1 -> detailView(land)
            tab == 2 -> historyView()
            else -> imageLayout()
        }
        panel.addView(content, FrameLayout.LayoutParams(-1, -1))
        keys = widgets.flatMap { it.keys() }.distinct()
        applyTheme(theme)
        turnMaps.forEach { it.onStart(); if (resumed) it.onResume(); it.setRoute(Nav.tracker.route) }
    }

    /** Devir göstergesi ile harita arasında dikey kaydırmayla geçiş (harita görünmezken de yeri ayrılmış) */
    private val swipe by lazy {
        android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dy = e2.rawY - e1.rawY; val dx = e2.rawX - e1.rawX
                if (kotlin.math.abs(dy) < dp(40) || kotlin.math.abs(dy) < 1.5f * kotlin.math.abs(dx)) return false
                if (turnMaps.none { it.hit(e1.rawX, e1.rawY) }) return false
                turnWatch.toggle(); updateTurnMap()
                return true
            }
        })
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (tab == 0) swipe.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun turnMap(light: Boolean) = TurnMapView(this, light).also { turnMaps += it }

    /** [coarse]: ağ/son bilinen konum — haritayı konumlar; rota yalnız henüz yoksa (yüzlerce m sapabilir) */
    private fun feedTurnMaps(lat: Double, lon: Double, bearing: Float?, kmh: Double?, coarse: Boolean = false, accM: Float? = null) {
        val p = RouteTracker.LatLon(lat, lon)
        if (coarse) {
            for (m in turnMaps) m.onLocation(lat, lon, null)
            if (Nav.lastFix == null) Nav.lastFix = p
            if (Nav.active && Nav.guidance == null) navStep(p, null, null)
            return
        }
        Nav.lastFix = p
        navStep(p, bearing, kmh)
        val h = heading.update(p, accM, Nav.guidance?.routeBearing)
        for (m in turnMaps) m.onLocation(lat, lon, h)
    }
    private val heading = HeadingFilter()

    /** Navigasyon adımı: izdüşüm, varış, rota isteği (ilk / yoldan çıkınca), sesli uyarı */
    private fun navStep(p: RouteTracker.LatLon, bearing: Float?, kmh: Double?) {
        val tr = Nav.tracker
        val dest = tr.dest
        Nav.guidance?.onFix(p)
        val ask = tr.onFix(p.lat, p.lon)
        if (dest != null && tr.dest == null) {
            // Hedefe varıldı (ya da süre doldu)
            if (Nav.guidance != null) speak("Hedefe ulaştınız")
            Nav.stop(this)
        } else if (ask && dest != null) {
            if (tr.route != null) speak("Rota yeniden hesaplanıyor")
            tr.onRequested()
            YandexNav.requestRoute(this, p, dest, bearing?.toDouble()?.takeIf { (kmh ?: 0.0) > 6 }) { r ->
                if (Nav.tracker.dest != dest) return@requestRoute   // bu arada hedef değişti/bitti
                tr.onRoute(r?.points)
                if (r != null) { Nav.guidance = Guidance(r).also { g -> Nav.lastFix?.let(g::onFix) }; Nav.voice.reset() }
                turnMaps.forEach { it.setRoute(tr.route) }
                shownRoute = tr.route
            }
        }
        Nav.guidance?.let { g -> Nav.voice.update(g.nextIndex, g.next, g.nextDistM, kmh)?.let(::speak) }
        if (tr.dest == null && shownRoute != null) turnMaps.forEach { it.setRoute(null) }
        shownRoute = tr.route
    }

    private var voice: NavVoice? = null
    private fun speak(text: String) { if (prefs.getBoolean("nav_voice", true)) voice?.say(text) }

    /** Navigasyon kartına dokununca: bitir / yeni hedef */
    private fun navMenu() {
        val st = Nav.state()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(Nav.destName.ifEmpty { "Navigasyon" })
            .setMessage(st?.eta?.let { "Varış $it" } ?: "Rota hesaplanıyor…")
            .setPositiveButton("Rotayı bitir") { _, _ -> Nav.stop(this); turnMaps.forEach { it.setRoute(null) }; shownRoute = null }
            .setNeutralButton("Yeni hedef") { _, _ -> PlaceSearchActivity.open(this, PlaceSearchActivity.MODE_NAV) }
            .setNegativeButton("Vazgeç", null)
            .show()
    }
    private var shownRoute: List<RouteTracker.LatLon>? = null

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
        lay.place(NaviCard(this, prefs) { navMenu() }.also { naviCards += it }, DashImageGeom.NAV_CARD)
        lay.place(MediaCard(this, media).also { mediaCards += it }, DashImageGeom.MEDIA_CARD)
        val strip = ValueStrip(this, tiles.take(4))
        widgets += strip
        strip.setOnLongClickListener { chooseStripSlot(); true }
        lay.place(strip, DashImageGeom.STRIP)
        lay.place(StatusPill(this).also { pill ->
            pill.visibility = View.INVISIBLE; pills += pill
            pill.setOnClickListener { onPillTap(pill.alerts) }
        }, DashImageGeom.PILL)
        val (rx, ry) = DashImageGeom.RPM_C.let { it[0] to it[1] }
        val rr = imgTheme.bandOut
        lay.place(turnMap(imgTheme.light), android.graphics.RectF(rx - rr, ry - rr, rx + rr, ry + rr))
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

    /** Detay sekmesi: kartlar, arıza şeridi ve bütün sinyaller (sürüşte değil). */
    private fun detailView(land: Boolean): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(8)) }
        col.addView(topBar(), LinearLayout.LayoutParams(-1, dp(36)))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val cards = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((t, rows) in listOf("YOLCULUK" to ::tripRows, "PERFORMANS" to ::perfRows, "SAĞLIK" to ::healthRows)) {
            cards.addView(InfoCard(this, t, rows), LinearLayout.LayoutParams(0, dp(250), 1f).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
        }
        list.addView(cards)
        list.addView(DtcBar(this, ::dtcSummary).apply { setOnClickListener { startActivity(android.content.Intent(this@DashboardActivity, DtcActivity::class.java)) } },
            LinearLayout.LayoutParams(-1, dp(56)).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
        val perRow = if (land) 4 else 2
        SignalCatalog.ALL.chunked(perRow).forEach { chunk ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { def ->
                val tile = ValueTile(this, WidgetSpec("tile", def.key), showStale = true)
                widgets += tile
                r.addView(tile, LinearLayout.LayoutParams(0, dp(78), 1f).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
            }
            repeat(perRow - chunk.size) { r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            list.addView(r)
        }
        col.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        return col
    }

    private fun topBar() = TopBar(this, listOf("Sürüş", "Detay", "Geçmiş", "Ayarlar")) { onTab(it) }.also {
        it.selected = tab
        // Sinyal çubuklarına dokun: bağlan / bağlantıyı kes
        it.onStatusTap = {
            when {
                prefs.getBoolean("demo", false) -> toast("Demo modu açık (Ayarlar > Ekran)")
                BridgeService.running -> connectionDialog()
                Conn.start(this) -> toast("Bağlanıyor…")
                else -> startActivity(android.content.Intent(this, SettingsActivity::class.java))
            }
        }
        topBars += it
    }

    /** Bağlıyken çubuklara dokunmak hemen kesmesin (sürerken yanlış dokunuş): önce durumu göster */
    private fun connectionDialog() {
        val src = model.source
        val msg = Conn.status(this) + if (src.online) " · saniyede %.1f değer".format(src.rpmHz).replace('.', ',') else ""
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Bağlantı")
            .setMessage(msg)
            .setPositiveButton("Kapat", null)
            .setNegativeButton("Bağlantıyı kes") { _, _ -> Conn.stop(this); toast("Bağlantı kesildi") }
            .show()
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
        // Navigasyon: sıradaki manevra (rota beklenirken "hesaplanıyor")
        val ns = Nav.state() ?: if (Nav.active) NaviState("…", "Rota hesaplanıyor", null) else null
        for (n in naviCards) n.state = ns
        updateTurnMap()
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

    private fun updateTurnMap() {
        if (turnMaps.isEmpty()) return
        val now = System.currentTimeMillis()
        val navi = if (turnDemoT0 != 0L) turnDemo(now)
            else Nav.guidance?.nextDistM?.let { Guidance.formatDist(it) }
        val kmh = model.raw("speed")?.value ?: gpsSpeed?.value
        turnWatch.update(navi, kmh)
        for (m in turnMaps) m.update(turnWatch.visible, turnWatch.prepare, turnWatch.distanceM)
    }

    /**
     * Masada deneme (yalnız debug): "am start ... --ez turn_demo true". Düz bir caddede 50 km/h ile dönüşe yaklaşma,
     * dönüşte 90° sağa, sonra sıradaki manevra 2 km. Gerçek konum yerine sahte konum beslenir. 70 sn sürer.
     */
    private var turnDemoT0 = 0L
    private fun turnDemo(now: Long): String? {
        val t = (now - turnDemoT0) / 1000.0
        if (t > 70) { turnDemoT0 = 0L; Nav.stop(this); return null }
        val v = 50 / 3.6
        // Başlangıç: İstanbul, Vatan Caddesi doğrultusu (genel bir yer; kişisel konum değil)
        val lat0 = 41.0175; val lon0 = 28.9390
        val mLat = 111_195.0; val mLon = 111_195.0 * kotlin.math.cos(Math.toRadians(lat0))
        val turnAt = 40.0                        // 40. sn'de dönüş noktası (≈ 555 m)
        val bearing: Float; val lat: Double; val lon: Double
        val dist: Double
        if (t < turnAt) {
            val d = v * t; bearing = 60f
            lat = lat0 + d * kotlin.math.cos(Math.toRadians(60.0)) / mLat; lon = lon0 + d * kotlin.math.sin(Math.toRadians(60.0)) / mLon
            dist = v * (turnAt - t)
        } else {
            val dTurn = v * turnAt; val d = v * (t - turnAt); bearing = 150f
            val tl = lat0 + dTurn * kotlin.math.cos(Math.toRadians(60.0)) / mLat; val tn = lon0 + dTurn * kotlin.math.sin(Math.toRadians(60.0)) / mLon
            lat = tl + d * kotlin.math.cos(Math.toRadians(150.0)) / mLat; lon = tn + d * kotlin.math.sin(Math.toRadians(150.0)) / mLon
            dist = 2000 - d
        }
        feedTurnMaps(lat, lon, bearing, 50.0)
        return if (dist >= 1000) "%.1f km".format(dist / 1000).replace('.', ',') else "${(dist / 10).toInt() * 10} m"
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
        // Hava basıncı: hava servisi ya da motor durmuşken MAP
        if (src is DemoSource) motion.demo((now - demoT0) / 1000.0) else motion.baro.onCar(v("map"), v("rpm"), v("speed"))
        weather.pressureHpa?.let { motion.baro.onWeather(it) }
        paceGps()
        put("baro", model.raw("baro")?.value)
        val all = (shown + listOfNotNull(milAlert(), connAlert())).sortedBy { it.priority }
        for (p in pills) p.alerts = all
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
        Glass.use(t, resources)
        applyTheme(t.dash())
        if (changed) build()   // kadran resimleri temaya bağlı
    }

    private fun applyTheme(t: DashTheme) {
        theme = t
        root.background = android.graphics.drawable.ColorDrawable(if (imgTheme.light) 0xFFE6E8EB.toInt() else 0xFF0E1013.toInt())
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

    /** Arıza lambası yanıyorsa (son 2 dk içinde okunmuş 0101) */
    private fun milAlert(): Alert? {
        if (!prefs.getBoolean("alert_MIL", true)) return null
        val mil = model.raw("mil")?.takeIf { System.currentTimeMillis() - it.ts < 120_000 }?.value ?: return null
        if (mil == 0.0) return null
        val n = model.raw("dtc_count")?.value?.toInt()?.takeIf { it > 0 }
        return Alert(AlertKind.MIL, "Arıza lambası yanıyor" + (n?.let { " · $it kod" } ?: ""), Level.WARN, 0)
    }

    private var connText: String? = null
    private var connAt = 0L

    /** Araçtan veri gelmiyorsa nedenini hapta yaz (en düşük öncelik); saniyede bir hesaplanır */
    private fun connAlert(): Alert? {
        val now = System.currentTimeMillis()
        if (now - connAt > 1000) {
            connAt = now
            connText = if (model.source.online || prefs.getBoolean("demo", false)) null
            else when (val st = Conn.status(this)) {
                "Canlı" -> null
                "Bağlı değil" -> "Adaptör bağlı değil · bağlanmak için dokun"
                "Adaptör seçilmedi" -> "Adaptör seçilmedi · seçmek için dokun"
                else -> st
            }
        }
        return connText?.let { Alert(AlertKind.CONN, it, Level.NORMAL, 9) }
    }

    /** Hapa dokununca: tek uyarıysa doğrudan işi yap, çoksa hepsini listele */
    private fun onPillTap(alerts: List<Alert>) {
        fun act(a: Alert) = when (a.kind) {
            AlertKind.MIL -> startActivity(android.content.Intent(this, DtcActivity::class.java))
            AlertKind.CONN -> connectOrSettings()
            else -> {}
        }
        if (alerts.size == 1 && alerts[0].kind in setOf(AlertKind.MIL, AlertKind.CONN)) { act(alerts[0]); return }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Uyarılar")
            .setItems(alerts.map { it.text }.toTypedArray()) { _, i -> act(alerts[i]) }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun connectOrSettings() {
        if (!BridgeService.running && Conn.start(this)) toast("Bağlanıyor…")
        else startActivity(android.content.Intent(this, SettingsActivity::class.java))
    }

    /** Detay'daki arıza şeridi: canlı 0101 (lamba, sayı) ve son okuma; dokununca DtcActivity. (metin, uyarı mı) */
    private fun dtcSummary(): Pair<String, Boolean> {
        val mil = model.raw("mil")?.value
        val n = model.raw("dtc_count")?.value
        val last = DtcActivity.last
        val parts = listOfNotNull(
            when (mil) { null -> null; 0.0 -> "lamba sönük"; else -> "LAMBA YANIYOR" },
            n?.let { "%.0f kayıtlı kod".format(it) },
            last?.let { "son okuma " + java.text.SimpleDateFormat("HH:mm", java.util.Locale("tr")).format(java.util.Date(it.ts)) },
        )
        return (parts.ifEmpty { listOf("okumak için dokun") }.joinToString(" · ")) to (mil != null && mil != 0.0)
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
            // Eski kayıtta daha az kutucuk varsa eksikleri varsayılanla tamamla; kaldırılan sinyaller (yön, rakım,
            // G-ölçer: 2026-10-13) yerine o sıranın varsayılanı
            val def = tiles
            tiles = (saved + def.drop(saved.size)).mapIndexed { i, t -> if (SignalCatalog[t.signal] == null) def.getOrElse(i) { WidgetSpec("tile", "load") } else t }.toMutableList()
            if (SignalCatalog[left.signal] == null) left = WidgetSpec("ring", "speed")
            if (SignalCatalog[right.signal] == null) right = WidgetSpec("ring", "rpm", center = "gear", bar = "coolant")
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
        /** Hava basıncı (hava servisi / motor durmuşken MAP) */
        val motion = Motion()
        private val demoT0 = System.currentTimeMillis()
        /** Araç ekranı referans görselle açılır (HEADUNIT.md) */
        const val DEFAULT_THEME = "image"
    }
}
