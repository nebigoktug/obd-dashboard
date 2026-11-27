package com.nebi.obdbridge.car

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Choreographer
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.nebi.obdbridge.dash.DashImageDials
import com.nebi.obdbridge.dash.DashImageGeom
import com.nebi.obdbridge.dash.DashImageLayout
import com.nebi.obdbridge.dash.DashModel
import com.nebi.obdbridge.dash.DashWidget
import com.nebi.obdbridge.dash.DashboardActivity
import com.nebi.obdbridge.dash.DemoSource
import com.nebi.obdbridge.dash.GearEstimator
import com.nebi.obdbridge.dash.ImageTheme
import com.nebi.obdbridge.dash.Insights
import com.nebi.obdbridge.dash.load
import com.nebi.obdbridge.dash.LiveSource
import com.nebi.obdbridge.dash.MediaCard
import com.nebi.obdbridge.dash.MediaHub
import com.nebi.obdbridge.dash.Nav
import com.nebi.obdbridge.dash.NaviCard
import com.nebi.obdbridge.dash.NaviState
import com.nebi.obdbridge.dash.SignalCatalog
import com.nebi.obdbridge.dash.ThemeColors
import com.nebi.obdbridge.dash.Trip
import com.nebi.obdbridge.dash.UserThemes
import com.nebi.obdbridge.dash.ValueStrip
import com.nebi.obdbridge.dash.Weather
import com.nebi.obdbridge.dash.WeatherView
import com.nebi.obdbridge.dash.WidgetSpec
import com.nebi.obdbridge.dash.Glass
import com.nebi.obdbridge.t
import org.json.JSONObject

/**
 * Araç ekranında telefondaki Sürüş ekranının aynısı (İş 3 adım 2a, 2026-11-27): AA yüzeyi sanal ekran olarak açılır
 * ([DashCarScreen]), bu Presentation onun üstünde telefonla aynı View'ları gösterir: temalı kadranlar, alt şerit,
 * navigasyon ve medya kartı, hava. Sekmeler, ayarlar ve diyaloglar telefonda kalır.
 * Veri telefondaki köprü servisinden (DashModel + LiveSource); tema, renk, şerit seçimi telefondaki ayardan.
 */
class CarDashPresentation(outer: Context, display: Display) : Presentation(outer, display) {
    private val prefs = context.getSharedPreferences("dash", Context.MODE_PRIVATE)
    private val model = DashModel(
        if (prefs.getBoolean("demo", false)) DemoSource() else LiveSource { null },
        if (prefs.getBoolean("demo", false)) GearEstimator.fromRatios(DemoSource.RATIOS.drop(1))
        else GearEstimator(com.nebi.obdbridge.dash.Vehicle.current.gearPrior()).apply { load(prefs.getString("gears2", null)) })
    private val widgets = ArrayList<DashWidget>()
    private val naviCards = ArrayList<NaviCard>()
    private val mediaCards = ArrayList<MediaCard>()
    private val pills = ArrayList<com.nebi.obdbridge.dash.StatusPill>()
    private val dialsList = ArrayList<DashImageDials>()
    private val ins = Insights()
    private val alertSources by lazy { com.nebi.obdbridge.dash.AlertSources(context.applicationContext, prefs) }
    private val weather by lazy { Weather(context.applicationContext) }
    private val media by lazy { MediaHub(context.applicationContext).apply { onChange = { mediaCards.forEach { it.postInvalidate() } } } }
    private var keys: List<String> = emptyList()
    private lateinit var holder: FrameLayout
    private var running = false
    /** AA'nın kendi düğmelerinin kapatmadığı alan (yüzey pikselinde) */
    var visible: android.graphics.Rect? = null
        set(v) { field = v; if (::holder.isInitialized) applyInsets() }

    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(t: Long) {
            if (!running) return
            render()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UserThemes.load(context)
        // Kendi kopyası: yalnız okunur (telefondaki kayda yazmaz), uyarılar ve yakıt tahmini için her karede güncellenir
        ins.load(prefs.getString("insights", null))
        model.insights = ins
        model.trip = Trip()
        holder = FrameLayout(context)
        setContentView(holder)
        build()
    }

    private fun build() {
        holder.removeAllViews(); widgets.clear(); naviCards.clear(); mediaCards.clear(); pills.clear(); dialsList.clear()
        val chosen = ImageTheme.byId(prefs.getString("theme", DashboardActivity.DEFAULT_THEME))
        val colors = ThemeColors.load(prefs, chosen.id)
        val th = colors.accent?.let { chosen.copy(accent = it) } ?: chosen
        Glass.use(th, context.resources, colors)
        holder.setBackgroundColor(if (th.light) 0xFFE6E8EB.toInt() else 0xFF0E1013.toInt())
        val (right, tiles) = layoutSpecs()
        val dials = DashImageDials(context, right, th)
        widgets += dials; dialsList += dials
        val lay = DashImageLayout(context, dials)
        lay.place(WeatherView(context, weather), DashImageGeom.WEATHER)
        lay.place(NaviCard(context, prefs) { }.also { naviCards += it }, DashImageGeom.NAV_CARD)
        lay.place(MediaCard(context, media).also { mediaCards += it }, DashImageGeom.MEDIA_CARD)
        val strip = ValueStrip(context, tiles.take(4))
        widgets += strip
        lay.place(strip, DashImageGeom.STRIP)
        // Uyarı hapı (motor sıcak, akü/şarj, arıza lambası, bağlantı): telefondakiyle aynı yer ve görünüm
        lay.place(com.nebi.obdbridge.dash.StatusPill(context).also { it.visibility = View.INVISIBLE; pills += it }, DashImageGeom.PILL)
        holder.addView(lay, FrameLayout.LayoutParams(-1, -1))
        applyInsets()
        val dash = th.dash()
        widgets.forEach { it.theme = dash }
        keys = widgets.flatMap { it.keys() }.distinct()
    }

    /** Telefondaki yerleşim kaydı (Sürüş ekranı: sağ kadran ve alt şerit); bozuksa varsayılan */
    private fun layoutSpecs(): Pair<WidgetSpec, List<WidgetSpec>> {
        var right = WidgetSpec("ring", "rpm", center = "gear", bar = "coolant")
        var tiles = listOf(WidgetSpec("tile", "boost"), WidgetSpec("tile", "load"), WidgetSpec("tile", "battery"), WidgetSpec("tile", "coolant"))
        try {
            prefs.getString("layout", null)?.let { s ->
                val o = JSONObject(s)
                fun spec(j: JSONObject) = WidgetSpec(j.getString("type"), j.getString("signal"),
                    j.optString("center").ifEmpty { null }, j.optString("bar").ifEmpty { null })
                spec(o.getJSONObject("right")).takeIf { SignalCatalog[it.signal] != null }?.let { right = it }
                val a = o.getJSONArray("tiles")
                val saved = List(a.length()) { spec(a.getJSONObject(it)) }.filter { SignalCatalog[it.signal] != null }
                if (saved.size >= 4) tiles = saved
            }
        } catch (_: Exception) { }
        return right to tiles
    }

    private fun applyInsets() {
        val r = visible ?: return
        val child = holder.getChildAt(0) ?: return
        val w = holder.width; val h = holder.height
        if (w == 0) { holder.post { applyInsets() }; return }
        child.layoutParams = FrameLayout.LayoutParams(-1, -1).apply {
            setMargins(r.left.coerceAtLeast(0), r.top.coerceAtLeast(0), (w - r.right).coerceAtLeast(0), (h - r.bottom).coerceAtLeast(0))
        }
    }

    private fun render() {
        val frame = model.frame(keys)
        for (w in widgets) { w.frame = frame; w.shift = model.shift; w.invalidate() }
        val ns = Nav.state() ?: if (Nav.active) NaviState("…", t("Rota hesaplanıyor"), null) else null
        for (n in naviCards) if (n.state != ns) { n.state = ns; n.invalidate() }
        model.trip?.update(model.raw("speed"))
        updateAlerts()
        weather.refreshIfStale()
    }

    private fun updateAlerts() {
        val src = model.source
        val now = System.currentTimeMillis()
        fun v(k: String) = model.raw(k)?.takeIf { now - it.ts < 5000 }?.value
        ins.update(com.nebi.obdbridge.dash.Inputs(now, v("rpm"), v("speed"), v("load"), v("map"), v("coolant"),
            v("battery"), v("rail"), model.raw("gear")?.value?.toInt(), null,
            src.name == "Canlı" || src is DemoSource, v("maf"), v("fuel_rate")))
        val all = alertSources.all(model, ins)
        for (p in pills) p.alerts = all
        val note = if (!prefs.getBoolean("note_fuelcut", true)) null
            else when { ins.fuelCut -> t("yakıt kesik"); ins.coasting && com.nebi.obdbridge.dash.Vehicle.current.manual -> t("boşta"); else -> null }
        for (d in dialsList) d.note = note
    }

    override fun onStart() {
        super.onStart()
        media.start()
        running = true
        Choreographer.getInstance().postFrameCallback(frameCb)
    }

    override fun onStop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCb)
        media.stop()
        super.onStop()
    }

    /** Araç ekranındaki dokunuş (yüzey pikselinde): View'lara normal dokunuş olarak verilir (medya düğmeleri vb.) */
    fun tap(x: Float, y: Float) {
        val root: View = window?.decorView ?: return
        val now = android.os.SystemClock.uptimeMillis()
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val e = MotionEvent.obtain(now, now + if (action == MotionEvent.ACTION_UP) 60 else 0, action, x, y, 0)
            root.dispatchTouchEvent(e); e.recycle()
        }
    }
}
