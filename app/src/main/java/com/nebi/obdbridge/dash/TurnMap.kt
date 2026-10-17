package com.nebi.obdbridge.dash

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import com.nebi.obdbridge.BuildConfig
import com.nebi.obdbridge.dash.RouteTracker.LatLon
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory

/**
 * Dönüşe yaklaşırken devir göstergesinin yerinde beliren yuvarlak harita ([TurnWatch] karar verir).
 * Gidiş yönü yukarıda, araç dairenin altına yakın; yakınlık dönüşe kalan mesafeye göre.
 * Harita: Yandex MapKit (anahtar varsa; rota çizgisi de onunla) ya da MapLibre + OpenFreeMap (anahtarsız yedek).
 * Yaşam döngüsü DashboardActivity'den iletilir.
 */
@SuppressLint("ViewConstructor")  // yalnız koddan oluşturuluyor
class TurnMapView(ctx: Context, light: Boolean) : FrameLayout(ctx) {
    private val dp = ctx.resources.displayMetrics.density
    private val backend: MapBackend = if (BuildConfig.YANDEX_MAPKIT_KEY.isNotEmpty()) YandexBackend(ctx, light) else LibreBackend(ctx, light)
    private val puck = Puck(ctx, backend.credit)
    private var lat = Double.NaN; private var lon = Double.NaN
    private var bearing = 0.0
    private var distM = 300.0
    private var lastCam = 0L
    /** Harita gerçekten görünür mü (görünmezken de [prepare] ile döşemeler iner) */
    var shown = false; private set

    init {
        addView(backend.view, LayoutParams(-1, -1))
        addView(puck, LayoutParams(-1, -1))
        // Dairesel kırpma (harita TextureView'da)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setOval(0, 0, v.width, v.height) }
        }
        clipToOutline = true
        alpha = 0f
        visibility = INVISIBLE
        backend.onReady = { camera(animate = false) }
    }

    /** Konum; [bearingDeg] süzülmüş yön (HeadingFilter), null ise son yön kalır */
    fun onLocation(la: Double, lo: Double, bearingDeg: Double?) {
        lat = la; lon = lo
        if (bearingDeg != null) bearing = bearingDeg
        if (visibility == VISIBLE) camera(animate = true)
    }

    /** [prepare]: görünmeden yüklensin; [show]: belirsin; [distance] dönüşe kalan (m) */
    fun update(showWanted: Boolean, prepare: Boolean, distance: Double?) {
        distance?.let { distM = it }
        // Konum yokken harita dünya görünümünde kalır → devir saati kalsın
        val show = showWanted && !lat.isNaN()
        if (prepare && visibility != VISIBLE) { visibility = VISIBLE; camera(animate = false) }
        if (show != shown) {
            shown = show
            animate().cancel()
            animate().alpha(if (show) 1f else 0f).setDuration(350).start()
            if (show) camera(animate = false)
        }
        if (!prepare && !show && alpha == 0f && visibility == VISIBLE) visibility = INVISIBLE
        // Mesafe azaldıkça yakınlaş (konum güncellemesi seyrekse de)
        if (show && System.currentTimeMillis() - lastCam > 1500) camera(animate = true)
    }

    /** Rota çizgisi (null: kaldır) */
    fun setRoute(points: List<LatLon>?) = backend.setRoute(points)

    private fun camera(animate: Boolean) {
        if (lat.isNaN() || width == 0) return
        lastCam = System.currentTimeMillis()
        // Araç dairenin %72'sinde; önünde görünen yol ≈ 0,72 çap
        backend.camera(lat, lon, bearing, TurnWatch.zoomFor(distM, lat, width / dp * 0.72), animate, width, height)
    }

    /** Ekran koordinatı haritanın dairesinde mi (görünmezken de) */
    fun hit(x: Float, y: Float): Boolean {
        if (width == 0) return false
        val loc = IntArray(2); getLocationOnScreen(loc)
        val dx = x - (loc[0] + width / 2f); val dy = y - (loc[1] + height / 2f)
        return dx * dx + dy * dy <= (width / 2f) * (width / 2f)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        invalidateOutline()
        camera(animate = false)
    }

    fun onStart() = backend.onStart()
    fun onResume() = backend.onResume()
    fun onPause() = backend.onPause()
    fun onStop() = backend.onStop()
    fun onDestroy() = backend.onDestroy()
    fun onLowMemory() = backend.onLowMemory()

    /** Araç oku (sabit, yukarı bakar), çerçeve ve harita kaynağı notu */
    private class Puck(ctx: Context, private val credit: String?) : View(ctx) {
        private val dp = ctx.resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2F8CFF.toInt() }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x66FFFFFF }
        private val note = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = 0x99FFFFFF.toInt() }
        private val path = Path()

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val cx = w / 2; val cy = height * 0.72f
            val s = w / 300f
            path.reset()
            path.moveTo(cx, cy - 16 * s); path.lineTo(cx + 11 * s, cy + 12 * s)
            path.lineTo(cx, cy + 6 * s); path.lineTo(cx - 11 * s, cy + 12 * s); path.close()
            edge.strokeWidth = 3 * s
            c.drawPath(path, edge); c.drawPath(path, fill)
            ring.strokeWidth = 2 * dp
            c.drawCircle(cx, height / 2f, w / 2 - dp, ring)
            credit?.let { note.textSize = 7.5f * dp; c.drawText(it, cx, height - 10 * dp, note) }
        }
    }

}

/** Kamera eğimi (°): sürücü bakışı gibi önü uzak, yakını büyük; 0 = tepeden */
private const val TILT = 45f

/** Harita motoru: kamera, rota çizgisi, yaşam döngüsü. [zoom] MapLibre ölçeğinde (512'lik döşeme). */
private abstract class MapBackend {
    abstract val view: View
    /** Haritanın altına yazılacak kaynak notu (Yandex kendi logosunu çizer) */
    open val credit: String? = null
    var onReady: () -> Unit = {}
    abstract fun camera(lat: Double, lon: Double, bearing: Double, zoom: Double, animate: Boolean, w: Int, h: Int)
    open fun setRoute(points: List<LatLon>?) {}
    open fun onStart() {}
    open fun onResume() {}
    open fun onPause() {}
    open fun onStop() {}
    open fun onDestroy() {}
    open fun onLowMemory() {}
}

/** Yandex MapKit: Navigator'la aynı harita ve yol tarifi. Ücretsiz sürüm şartları SettingsActivity'de (bağlantı). */
private class YandexBackend(ctx: Context, light: Boolean) : MapBackend() {
    private val mv: com.yandex.mapkit.mapview.MapView
    override val view: View get() = mv
    private var line: com.yandex.mapkit.map.PolylineMapObject? = null

    init {
        YandexNav.init(ctx)
        mv = android.view.LayoutInflater.from(ctx).inflate(com.nebi.obdbridge.R.layout.yandex_map, FrameLayout(ctx), false) as com.yandex.mapkit.mapview.MapView
        val m = mv.mapWindow.map
        m.isNightModeEnabled = !light
        m.isZoomGesturesEnabled = false; m.isScrollGesturesEnabled = false
        m.isRotateGesturesEnabled = false; m.isTiltGesturesEnabled = false
        // Logo şartı: görünür kalmalı → dairenin altında ortada
        m.logo.setAlignment(com.yandex.mapkit.logo.Alignment(com.yandex.mapkit.logo.HorizontalAlignment.CENTER,
            com.yandex.mapkit.logo.VerticalAlignment.BOTTOM))
        mv.post { onReady() }
    }

    override fun camera(lat: Double, lon: Double, bearing: Double, zoom: Double, animate: Boolean, w: Int, h: Int) {
        val win = mv.mapWindow
        // MapKit kendi yüzey boyutunu yerleşimden sonra öğrenir; o boyutun dışındaki odak noktası
        // yerel kodda çöker → odağı haritanın bildiği boyuta göre ver, boyut yoksa sonraki kareye bırak.
        val ww = win.width(); val wh = win.height()
        if (ww > 0 && wh > 0) runCatching { win.focusPoint = com.yandex.mapkit.ScreenPoint(ww / 2f, wh * 0.72f) }
        // Yandex 256'lık döşeme ölçeği: MapLibre yakınlığı + 1
        val pos = com.yandex.mapkit.map.CameraPosition(com.yandex.mapkit.geometry.Point(lat, lon), (zoom + 1).toFloat(), bearing.toFloat(), TILT)
        if (animate) win.map.move(pos, com.yandex.mapkit.Animation(com.yandex.mapkit.Animation.Type.SMOOTH, 0.9f), null)
        else win.map.move(pos)
    }

    override fun setRoute(points: List<LatLon>?) {
        val objs = mv.mapWindow.map.mapObjects
        if (points == null || points.size < 2) { line?.let { objs.remove(it) }; line = null; return }
        val poly = com.yandex.mapkit.geometry.Polyline(points.map { com.yandex.mapkit.geometry.Point(it.lat, it.lon) })
        val l = line ?: objs.addPolyline().also { line = it }
        l.geometry = poly
        l.setStrokeColor(0xFF3D8BFF.toInt()); l.strokeWidth = 6f
        l.outlineColor = 0xFF0B2A5C.toInt(); l.outlineWidth = 1.5f
    }

    override fun onStart() { com.yandex.mapkit.MapKitFactory.getInstance().onStart(); mv.onStart() }
    override fun onStop() { mv.onStop(); com.yandex.mapkit.MapKitFactory.getInstance().onStop() }
    override fun onDestroy() {}

}

/** MapLibre + OpenFreeMap (anahtarsız yedek; rota yok) */
private class LibreBackend(ctx: Context, light: Boolean) : MapBackend() {
    private val map: org.maplibre.android.maps.MapView
    override val view: View get() = map
    override val credit = "© OpenStreetMap"
    private var mlMap: MapLibreMap? = null
    private val dp = ctx.resources.displayMetrics.density

    init {
        MapLibre.getInstance(ctx)
        map = org.maplibre.android.maps.MapView(ctx, MapLibreMapOptions.createFromAttributes(ctx).textureMode(true)
            .attributionEnabled(false).logoEnabled(false).compassEnabled(false)
            .rotateGesturesEnabled(false).scrollGesturesEnabled(false).tiltGesturesEnabled(false)
            .zoomGesturesEnabled(false).doubleTapGesturesEnabled(false))
        map.getMapAsync { m ->
            mlMap = m
            m.setStyle(Style.Builder().fromUri(if (light) STYLE_LIGHT else STYLE_DARK)) { st -> if (!light) brighten(st) }
            onReady()
        }
    }

    override fun camera(lat: Double, lon: Double, bearing: Double, zoom: Double, animate: Boolean, w: Int, h: Int) {
        val m = mlMap ?: return
        // Araç %72'de: üst dolgu P ile görüş merkezi (h+P)/2'ye iner
        val pos = org.maplibre.android.camera.CameraPosition.Builder().target(LatLng(lat, lon)).bearing(bearing).tilt(TILT.toDouble())
            .zoom(zoom).padding(0.0, h * 0.44, 0.0, 0.0).build()
        if (animate) m.easeCamera(CameraUpdateFactory.newCameraPosition(pos), 900)
        else m.moveCamera(CameraUpdateFactory.newCameraPosition(pos))
    }

    /**
     * OpenFreeMap "dark" araçta fazla koyu (kullanıcı, 2026-10-13): zemin biraz açılır, yollar ve adlar belirginleşir.
     * Katman adları stilde yoksa (stil değişirse) sessizce atlanır.
     */
    private fun brighten(st: Style) {
        fun fill(id: String, c: String) = st.getLayer(id)?.setProperties(PropertyFactory.fillColor(c))
        fun line(id: String, c: String) = st.getLayer(id)?.setProperties(PropertyFactory.lineColor(c))
        fun text(id: String, c: String) = st.getLayer(id)?.setProperties(PropertyFactory.textColor(c), PropertyFactory.textHaloColor("#14181e"), PropertyFactory.textHaloWidth(1.6f))
        // Zemin orta-koyu ve düz (yapılar zeminle neredeyse aynı), yollar açık: yol / yol değil farkı belirgin
        st.getLayer("background")?.setProperties(PropertyFactory.backgroundColor("#2b313b"))
        fill("landuse_residential", "#2d333d"); fill("building", "#343b46")
        fill("landuse_park", "#2a3d32"); fill("landcover_wood", "#2a3d32")
        fill("water", "#24456a"); line("waterway", "#24456a")
        line("highway_path", "#555d6b")
        line("highway_minor", "#a3acbb")
        line("highway_major_casing", "#1b1f25"); line("highway_major_inner", "#d4dae4"); line("highway_major_subtle", "#a3acbb")
        line("highway_motorway_casing", "#1b1f25"); line("highway_motorway_inner", "#eef1f6"); line("highway_motorway_subtle", "#b9c1ce")
        line("railway", "#555b66"); line("railway_minor", "#555b66"); line("railway_transit", "#555b66")
        // Ara sokaklar ince kalınca kayboluyor: biraz kalınlaştır
        st.getLayer("highway_minor")?.setProperties(PropertyFactory.lineWidth(
            org.maplibre.android.style.expressions.Expression.interpolate(org.maplibre.android.style.expressions.Expression.exponential(1.5f),
                org.maplibre.android.style.expressions.Expression.zoom(),
                org.maplibre.android.style.expressions.Expression.stop(13, 1.2f), org.maplibre.android.style.expressions.Expression.stop(16, 4.5f),
                org.maplibre.android.style.expressions.Expression.stop(18, 12f))))
        text("highway_name_other", "#ffffff"); text("highway_name_motorway", "#ffffff")
        for (id in listOf("place_other", "place_suburb", "place_village", "place_town", "place_city")) text(id, "#c3cad6")
    }

    override fun onStart() = map.onStart()
    override fun onResume() = map.onResume()
    override fun onPause() = map.onPause()
    override fun onStop() = map.onStop()
    override fun onDestroy() = map.onDestroy()
    override fun onLowMemory() = map.onLowMemory()

    companion object {
        const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"
        const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/positron"
    }
}
