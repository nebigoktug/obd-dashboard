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
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory

/**
 * Dönüşe yaklaşırken devir göstergesinin yerinde beliren yuvarlak harita ([TurnWatch] karar verir).
 * Gidiş yönü yukarıda, araç dairenin altına yakın; yakınlık dönüşe kalan mesafeye göre.
 * Harita: MapLibre + OpenFreeMap (anahtarsız). Yaşam döngüsü DashboardActivity'den iletilir.
 */
@SuppressLint("ViewConstructor")  // yalnız koddan oluşturuluyor
class TurnMapView(ctx: Context, light: Boolean) : FrameLayout(ctx) {
    private val dp = ctx.resources.displayMetrics.density
    private val map: MapView
    private var mlMap: MapLibreMap? = null
    private val puck = Puck(ctx)
    private var lat = Double.NaN; private var lon = Double.NaN
    private var bearing = 0.0
    private var distM = 300.0
    private var lastCam = 0L
    /** Harita gerçekten görünür mü (görünmezken de [prepare] ile döşemeler iner) */
    var shown = false; private set

    init {
        MapLibre.getInstance(ctx)
        // TextureView: dairesel kırpma (outline) SurfaceView'da çalışmaz
        map = MapView(ctx, MapLibreMapOptions.createFromAttributes(ctx).textureMode(true)
            .attributionEnabled(false).logoEnabled(false).compassEnabled(false)
            .rotateGesturesEnabled(false).scrollGesturesEnabled(false).tiltGesturesEnabled(false)
            .zoomGesturesEnabled(false).doubleTapGesturesEnabled(false))
        addView(map, LayoutParams(-1, -1))
        addView(puck, LayoutParams(-1, -1))
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setOval(0, 0, v.width, v.height) }
        }
        clipToOutline = true
        alpha = 0f
        visibility = INVISIBLE
        map.getMapAsync { m ->
            mlMap = m
            m.setStyle(Style.Builder().fromUri(if (light) STYLE_LIGHT else STYLE_DARK)) { st -> if (!light) brighten(st) }
            camera(animate = false)
        }
    }

    /** GPS konumu; yön yalnız hareket ederken (dururken GPS yönü rastgele) */
    fun onLocation(la: Double, lo: Double, bearingDeg: Float?, speedKmh: Double?) {
        lat = la; lon = lo
        if (bearingDeg != null && (speedKmh ?: 0.0) > 6) bearing = bearingDeg.toDouble()
        if (visibility == VISIBLE) camera(animate = true)
    }

    /** [prepare]: görünmeden yüklensin; [show]: belirsin; [distance] dönüşe kalan (m) */
    fun update(show: Boolean, prepare: Boolean, distance: Double?) {
        distance?.let { distM = it }
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

    private fun camera(animate: Boolean) {
        val m = mlMap ?: return
        if (lat.isNaN() || width == 0) return
        lastCam = System.currentTimeMillis()
        val d = width / dp
        // Araç dairenin %72'sinde: üst dolgu P ile görüş merkezi (d+P)/2'ye iner
        val topPad = d * 0.44
        val pos = CameraPosition.Builder().target(LatLng(lat, lon)).bearing(bearing).tilt(0.0)
            .zoom(TurnWatch.zoomFor(distM, lat, d * 0.72))
            .padding(0.0, topPad * dp, 0.0, 0.0).build()
        if (animate) m.easeCamera(CameraUpdateFactory.newCameraPosition(pos), 900)
        else m.moveCamera(CameraUpdateFactory.newCameraPosition(pos))
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

    fun onStart() = map.onStart()
    fun onResume() = map.onResume()
    fun onPause() = map.onPause()
    fun onStop() = map.onStop()
    fun onDestroy() = map.onDestroy()
    fun onLowMemory() = map.onLowMemory()

    /** Araç oku (sabit, yukarı bakar), çerçeve ve harita kaynağı notu */
    private class Puck(ctx: Context) : View(ctx) {
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
            note.textSize = 7.5f * dp
            c.drawText("© OpenStreetMap", cx, height - 10 * dp, note)
        }
    }

    companion object {
        const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"
        const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/positron"
    }
}

/** Halka göstergesi ve üstünde (ortalanmış kare) dönüş haritası: harita görünürken halka saklanır. */
@SuppressLint("ViewConstructor")
class RingSlot(ctx: Context, private val ring: View, private val map: TurnMapView) : FrameLayout(ctx) {
    init {
        addView(ring, LayoutParams(-1, -1))
        addView(map, LayoutParams(1, 1))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val w = r - l; val h = b - t
        val d = (minOf(w, h) * 0.94f).toInt()
        if (map.measuredWidth != d) map.measure(MeasureSpec.makeMeasureSpec(d, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(d, MeasureSpec.EXACTLY))
        val x = (w - d) / 2; val y = (h - d) / 2
        map.layout(x, y, x + d, y + d)
    }
}
