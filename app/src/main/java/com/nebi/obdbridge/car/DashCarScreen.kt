package com.nebi.obdbridge.car

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.nebi.obdbridge.dash.DashModel
import com.nebi.obdbridge.dash.DemoSource
import com.nebi.obdbridge.dash.Display
import com.nebi.obdbridge.dash.LiveSource
import com.nebi.obdbridge.dash.Units
import com.nebi.obdbridge.dash.VehicleStore
import com.nebi.obdbridge.t

/**
 * Araç ekranı. Yüzey sanal ekran olarak açılır, üstünde telefondaki Sürüş ekranının aynısı gösterilir
 * ([CarDashPresentation]). Sanal ekran açılamazsa yedek: sade gösterge (hız, devir, motor sıcaklığı, akü) Canvas ile, 5 Hz.
 * Birimler telefondaki ayardan (Units); değerler gösterilen birimde gelir (DashModel).
 */
class DashCarScreen(ctx: CarContext) : Screen(ctx), SurfaceCallback {
    private val prefs = ctx.getSharedPreferences("dash", CarContext.MODE_PRIVATE)
    private val model = DashModel(if (prefs.getBoolean("demo", false)) DemoSource() else LiveSource { null })
    private val handler = Handler(Looper.getMainLooper())
    private var surface: SurfaceContainer? = null
    private var visible = Rect()
    private val keys = listOf("speed", "rpm", "coolant", "battery")
    private var frames = 0L

    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9AA2A9.toInt(); textAlign = Paint.Align.CENTER }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    private val tick = object : Runnable {
        override fun run() { if (presentation == null) { draw(); handler.postDelayed(this, 200) } }
    }
    private var virtual: android.hardware.display.VirtualDisplay? = null
    private var presentation: CarDashPresentation? = null

    init {
        VehicleStore.load(ctx)
        Units.load(ctx)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                ctx.getCarService(AppManager::class.java).setSurfaceCallback(this@DashCarScreen)
            }
            override fun onStart(owner: LifecycleOwner) { handler.post(tick) }
            override fun onStop(owner: LifecycleOwner) { handler.removeCallbacks(tick) }
            override fun onDestroy(owner: LifecycleOwner) { closeVirtual() }
        })
    }

    override fun onGetTemplate(): Template = NavigationTemplate.Builder()
        .setActionStrip(ActionStrip.Builder().addAction(
            Action.Builder().setTitle(t("Gösterge")).setOnClickListener { }.build()).build())
        .build()

    override fun onSurfaceAvailable(c: SurfaceContainer) {
        surface = c; visible = Rect(0, 0, c.width, c.height)
        if (!openVirtual(c)) draw()
    }
    override fun onSurfaceDestroyed(c: SurfaceContainer) { closeVirtual(); surface = null }
    override fun onVisibleAreaChanged(r: Rect) { visible = Rect(r); presentation?.visible = Rect(r); if (presentation == null) draw() }
    override fun onClick(x: Float, y: Float) { presentation?.tap(x, y) }

    /** Yüzeyi sanal ekran yapıp telefondaki göstergeyi üstünde aç; olmazsa false (Canvas yedeği) */
    private fun openVirtual(c: SurfaceContainer): Boolean {
        val s = c.surface ?: return false
        if (c.width <= 0 || c.height <= 0) return false
        return try {
            closeVirtual()
            val dm = carContext.getSystemService(android.hardware.display.DisplayManager::class.java)
            val vd = dm.createVirtualDisplay("OBD Dashboard", c.width, c.height, c.dpi, s,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
            virtual = vd
            presentation = CarDashPresentation(carContext, vd.display).also { it.visible = Rect(visible); it.show() }
            Log.i(TAG, "sanal ekran ${c.width}x${c.height} ${c.dpi} dpi")
            true
        } catch (e: Exception) {
            Log.w(TAG, "sanal ekran açılamadı, sade göstergeye dönülüyor", e)
            closeVirtual(); false
        }
    }

    private fun closeVirtual() {
        try { presentation?.dismiss() } catch (_: Exception) { }
        presentation = null
        virtual?.release(); virtual = null
    }
    override fun onStableAreaChanged(r: Rect) {}

    private fun draw() {
        val s = surface?.surface ?: return
        if (!s.isValid) return
        val c: Canvas = try { s.lockCanvas(null) } catch (e: Exception) { Log.w(TAG, "lockCanvas: $e"); return }
        try { paint(c, model.frame(keys)) } finally { s.unlockCanvasAndPost(c) }
        if (++frames % 50 == 1L) Log.i(TAG, "kare $frames, yüzey ${c.width}x${c.height}, görünür $visible, kaynak ${model.source.name}")
    }

    private fun paint(c: Canvas, f: Map<String, Display>) {
        c.drawColor(0xFF0B0E12.toInt())
        val r = if (visible.isEmpty) Rect(0, 0, c.width, c.height) else visible
        val h = r.height().toFloat(); val w = r.width().toFloat()
        // İki halka: hız ve devir; altında sıcaklık ve akü
        val rad = minOf(w / 4.6f, h * 0.36f)
        val cy = r.top + h * 0.44f
        dial(c, r.left + w * 0.27f, cy, rad, f["speed"])
        dial(c, r.left + w * 0.73f, cy, rad, f["rpm"])
        small.textSize = h * 0.07f; big.textSize = h * 0.11f
        val y = r.top + h * 0.95f
        line(c, r.left + w * 0.27f, y, f["coolant"])
        line(c, r.left + w * 0.73f, y, f["battery"])
    }

    private fun dial(c: Canvas, cx: Float, cy: Float, rad: Float, d: Display?) {
        ring.strokeWidth = rad * 0.12f
        ring.color = 0xFF2A3038.toInt()
        val box = android.graphics.RectF(cx - rad, cy - rad, cx + rad, cy + rad)
        c.drawArc(box, 135f, 270f, false, ring)
        if (d != null && !d.missing) {
            val frac = ((d.value - d.def.min) / (d.def.max - d.def.min)).coerceIn(0.0, 1.0).toFloat()
            ring.color = when (d.level) {
                com.nebi.obdbridge.dash.Level.CRIT -> 0xFFE5484D.toInt()
                com.nebi.obdbridge.dash.Level.WARN -> 0xFFF5A623.toInt()
                else -> 0xFF3DD68C.toInt()
            }
            c.drawArc(box, 135f, 270f * frac, false, ring)
        }
        big.textSize = rad * 0.62f
        c.drawText(d?.text ?: "—", cx, cy + big.textSize * 0.35f, big)
        small.textSize = rad * 0.2f
        c.drawText(d?.def?.unit ?: "", cx, cy + rad * 0.62f, small)
    }

    private fun line(c: Canvas, x: Float, y: Float, d: Display?) {
        val txt = if (d == null) "—" else "${d.def.label}  ${d.text} ${d.def.unit}"
        big.color = if (d != null && d.level >= com.nebi.obdbridge.dash.Level.WARN) 0xFFF5A623.toInt() else Color.WHITE
        c.drawText(txt, x, y, big)
        big.color = Color.WHITE
    }

    companion object { const val TAG = "DashCar" }
}
