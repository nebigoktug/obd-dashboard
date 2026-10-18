package com.nebi.obdbridge.dash

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Renk paleti. Gece: koyu panel, turkuaz vurgu (referans görsel). Gündüz: açık, yüksek kontrast. */
data class DashTheme(
    val bg: Int, val panel: Int, val track: Int, val text: Int, val dim: Int,
    val accent: Int, val cold: Int, val warn: Int, val crit: Int,
    /** İnce kutucuk/üst çubuk stili: büyük harf aralıklı etiketler, ince rakamlar, çizgi simgeler */
    val refined: Boolean = false,
    /** Görsel teması: referans tasarım resim katmanı olarak (yalnız yatay) */
    val image: Boolean = false,
) {
    fun levelColor(l: Level) = when (l) {
        Level.NORMAL -> accent; Level.COLD -> cold; Level.WARN -> warn; Level.CRIT -> crit
    }

    companion object {
        val NIGHT = DashTheme(
            bg = Color.BLACK, panel = 0xFF25282F.toInt(), track = 0xFF3A3E47.toInt(), text = 0xFFE8EAED.toInt(),
            dim = 0xFF8A8F99.toInt(), accent = 0xFF3EDCB4.toInt(), cold = 0xFF5AA9FF.toInt(),
            warn = 0xFFFFB547.toInt(), crit = 0xFFFF5A5A.toInt(),
        )
        val DAY = DashTheme(
            bg = 0xFFE6E8EB.toInt(), panel = 0xFFFDFDFD.toInt(), track = 0xFFD5D8DD.toInt(), text = 0xFF15171B.toInt(),
            dim = 0xFF5F6570.toInt(), accent = 0xFF0C9B78.toInt(), cold = 0xFF1F6FD1.toInt(),
            warn = 0xFFC77700.toInt(), crit = 0xFFD62828.toInt(),
        )
        /** Görsel tema (referans resim): antrasit zemin, buz mavisi vurgu, kehribar kırmızı bölge. */
        val IMAGE = DashTheme(
            bg = 0xFF15181C.toInt(), panel = 0xFF1A1E23.toInt(), track = 0xFF2E343B.toInt(), text = 0xFFFFFFFF.toInt(),
            dim = 0xFF9AA2A9.toInt(), accent = 0xFF7FB5DD.toInt(), cold = 0xFF5C8BB5.toInt(),
            warn = 0xFFE08B36.toInt(), crit = 0xFFE0603A.toInt(), refined = true, image = true,
        )
    }
}


/** Ne gösterileceği: kullanıcı uzun basarak değiştirebilir, ayarlarda saklanır. */
data class WidgetSpec(
    val type: String,                 // "ring" | "tile"
    var signal: String,
    var center: String? = null,       // halkanın ortasında başka sinyal (ör. devir halkasında vites)
    var bar: String? = null,          // halkanın altında küçük çubuk
)

/** Bütün göstergelerin ortak tabanı: kare verisini alır, kendini çizer. */
abstract class DashWidget(ctx: Context, val spec: WidgetSpec) : View(ctx) {
    var theme: DashTheme = DashTheme.NIGHT
    var frame: Map<String, Display> = emptyMap()
    var shift = 0
    protected val dp = ctx.resources.displayMetrics.density

    /** Bu göstergenin ihtiyaç duyduğu sinyaller (model yalnız bunları hesaplar). */
    open fun keys(): List<String> = listOfNotNull(spec.signal, spec.center, spec.bar)

    private val paints = HashMap<String, Paint>()

    /** Kare başına yeni nesne oluşturmamak için rol adına göre önbellekli yazı fırçası. */
    protected fun paint(role: String, color: Int, size: Float = 0f, light: Boolean = false, bold: Boolean = false, thin: Boolean = false): Paint =
        paints.getOrPut(role) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                val family = when { thin -> "sans-serif-thin"; light -> "sans-serif-light"; else -> "sans-serif" }
                typeface = Typeface.create(family, if (bold) Typeface.BOLD else Typeface.NORMAL)
                fontFeatureSettings = "tnum"   // rakamlar sabit genişlik: değer değişince yazı oynamasın
            }
        }.apply { this.color = color; textSize = size; alpha = 255; textAlign = Paint.Align.LEFT }

    protected fun alphaFor(d: Display?) = if (d == null || d.stale) 110 else 255

    private val iconP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val iconPath = Path()
    private val iconOval = RectF()

    /** İnce çizgili, içi boş simge (akü, termometre); [y] yazı taban çizgisi, [s] simge yüksekliği. */
    protected fun drawIcon(c: Canvas, key: String, x: Float, y: Float, s: Float, color: Int) {
        iconP.color = color; iconP.strokeWidth = 1.5f * dp
        val top = y - s
        when (key) {
            "battery" -> {
                val w = s * 1.5f; val h = s * 0.8f; val t = y - h
                c.drawRoundRect(x, t, x + w, y, s * 0.12f, s * 0.12f, iconP)
                c.drawLine(x + w * 0.25f, t - s * 0.12f, x + w * 0.25f, t, iconP)
                c.drawLine(x + w * 0.75f, t - s * 0.12f, x + w * 0.75f, t, iconP)
                c.drawLine(x + w * 0.18f, t + h / 2, x + w * 0.36f, t + h / 2, iconP)   // −
                c.drawLine(x + w * 0.64f, t + h / 2, x + w * 0.82f, t + h / 2, iconP)   // +
                c.drawLine(x + w * 0.73f, t + h * 0.32f, x + w * 0.73f, t + h * 0.68f, iconP)
            }
            "coolant", "iat", "oil" -> {
                // Termometre: içi boş kapsül gövde + yuvarlak hazne + yan çentikler (yalnız çizgi)
                val cx = x + s * 0.5f
                val sw = s * 0.11f
                iconPath.reset()
                iconPath.moveTo(cx - sw, y - s * 0.34f)
                iconPath.lineTo(cx - sw, top + sw)
                iconOval.set(cx - sw, top, cx + sw, top + 2 * sw)
                iconPath.arcTo(iconOval, 180f, 180f)
                iconPath.lineTo(cx + sw, y - s * 0.34f)
                c.drawPath(iconPath, iconP)
                c.drawCircle(cx, y - s * 0.2f, s * 0.17f, iconP)
                for (k in 0..2) c.drawLine(cx + sw + s * 0.08f, top + s * (0.18f + 0.14f * k), cx + sw + s * 0.24f, top + s * (0.18f + 0.14f * k), iconP)
                if (key == "coolant") for (row in 0..1) {                          // dalgalar
                    iconPath.reset()
                    val wy = y + s * (0.05f + 0.16f * row)
                    iconPath.moveTo(x - s * 0.2f, wy)
                    for (k in 0..3) iconPath.rQuadTo(s * 0.15f, -s * 0.1f * (if (k % 2 == 0) 1 else -1), s * 0.3f, 0f)
                    c.drawPath(iconPath, iconP)
                }
            }
        }
    }
}

private val TR = Locale("tr")

/** Bilgi kutucuğu: etiket, büyük değer, birim; uyarıda renk ve kısa metin (renk körü sürücü için). */
@SuppressLint("ViewConstructor")  // yalnız koddan oluşturuluyor
class ValueTile(ctx: Context, spec: WidgetSpec, private val showStale: Boolean = false) : DashWidget(ctx, spec) {
    override fun onDraw(c: Canvas) {
        val d = frame[spec.signal] ?: return
        val h = height.toFloat()
        val label = paint("label", theme.dim, min(h * 0.2f, width * 0.11f) * (if (theme.refined) 0.85f else 1f))
        label.letterSpacing = if (theme.refined) 0.12f else 0f   // büyük harf, aralıklı etiket
        c.drawText(d.def.label.uppercase(TR), 0f, label.textSize, label)
        val color = if (d.level == Level.NORMAL) theme.text else theme.levelColor(d.level)
        val big = paint("value", color, min(h * 0.46f, width * 0.3f), light = true, thin = theme.refined).apply { alpha = alphaFor(d) }
        val y = label.textSize + big.textSize * 1.0f
        c.drawText(d.text, 0f, y, big)
        val unit = paint("unit", theme.dim, label.textSize)
        val tag = when (d.level) { Level.CRIT -> "  YÜKSEK"; Level.WARN -> "  DİKKAT"; Level.COLD -> "  SOĞUK"; else -> "" }
        val unitText = d.def.unit + tag
        c.drawText(unitText, big.measureText(d.text) + 6 * dp, y, unit)
        if (theme.refined) {
            val ix = big.measureText(d.text) + 6 * dp + unit.measureText(unitText) + 12 * dp
            drawIcon(c, d.def.key, ix, y, big.textSize * 0.62f, theme.dim)
        }
        // Yaş yalnız değer eskiyse (her kutuda "… ms önce" sürücüye bir şey söylemiyordu)
        if (showStale && d.stale && !d.missing) {
            val sub = paint("sub", theme.dim, label.textSize * 0.85f)
            c.drawText("eski veri", 0f, y + sub.textSize * 1.5f, sub)
        }
    }
}

/** Üst çubuk: sıcaklık, sekmeler, saat, bağlantı çubukları. */
@SuppressLint("ViewConstructor")  // yalnız koddan oluşturuluyor
class TopBar(ctx: Context, private val tabs: List<String>, private val onTab: (Int) -> Unit) : View(ctx) {
    var theme = DashTheme.NIGHT
    var selected = 0
    var tempText = ""
    /** Araç ekranında saat ve hava ayrı, büyük çiziliyor (ClockView/WeatherView): üst çubukta gösterme */
    var showTempClock = true
    var sourceName = ""
    /** Bağlantı çubuklarına dokunma (bağlan / kes) */
    var onStatusTap: (() -> Unit)? = null
    private var barsX0 = 0f
    var hz = 0.0
    var online = false
    private val hit = ArrayList<Pair<Float, Float>>()
    private val clock = SimpleDateFormat("HH:mm", TR)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { fontFeatureSettings = "tnum" }
    private val items = ArrayList<Pair<String, Int>>()
    private val under = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val now = Date()

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        items.clear()
        val off = if (showTempClock) 1 else 0   // ilk sekmenin öğe sırası
        // Resimli temada sekme yazısı altındaki zemine göre (Glass.ink); vektör temada tema rengi
        val ink = if (theme.image) Glass.ink(DashImageGeom.TOPBAR) else null
        if (ink != null) Glass.halo(p.apply { textSize = h * 0.4f }, ink) else p.clearShadowLayer()
        if (showTempClock) items += tempText to (ink?.text ?: theme.text)
        tabs.forEachIndexed { i, t -> items += t to (if (i == selected) ink?.text ?: theme.text else ink?.dim ?: theme.dim) }
        now.time = System.currentTimeMillis()
        if (showTempClock) items += clock.format(now) to theme.text
        // Çubukların yanında kaynak etiketi: demo uyarı renginde, OBD yokken GPS hızı
        val tag = when (sourceName) { "Demo" -> " DEMO"; "GPS" -> " GPS"; else -> null }

        // Yazı boyu: önce tercih edilen, sığmazsa genişliğe göre küçült (dar alanda kesilmesin)
        fun total(sz: Float): Float {
            p.textSize = sz
            val texts = items.sumOf { p.measureText(it.first).toDouble() }.toFloat()
            val demoW = if (tag != null) { p.textSize = sz * 0.75f; p.measureText(tag) } else 0f
            return texts + sz * 1.6f * (items.size - 1) + sz * 2.6f + demoW
        }
        var sz = h * 0.4f
        val avail = width * 0.96f
        total(sz).let { if (it > avail) sz *= avail / it }
        val w = total(sz)
        p.textSize = sz
        val gap = sz * 1.6f
        val y = h / 2 + sz * 0.36f
        var x = (width - w) / 2
        hit.clear()
        for ((i, it) in items.withIndex()) {
            val tw = p.measureText(it.first)
            p.color = it.second
            // Resimli temada her sekmenin rengi kendi altındaki zemine göre (Grafiti: sarı boyanın üstündeki "Detay")
            if (ink != null && i in off until off + tabs.size) {
                val r = DashImageGeom.TOPBAR
                val x0 = (r.left + x / width * r.width()).toInt(); val x1 = (r.left + (x + tw) / width * r.width()).toInt()
                val own = Glass.ink(android.graphics.RectF(x0.toFloat(), r.top, x1.toFloat(), r.bottom))
                p.color = if (i - off == selected) own.text else own.dim
                Glass.halo(p, own)
            }
            c.drawText(it.first, x, y, p)
            if (i in off until off + tabs.size) hit += x - gap / 2 to x + tw + gap / 2
            if (theme.refined && i == selected + off) {   // aktif sekmenin altında ince parlayan çizgi
                val uy = y + sz * 0.55f
                for ((wk, a) in listOf(6f to 25, 3f to 60, 1f to 255)) {
                    under.color = theme.accent; under.alpha = a; under.strokeWidth = sz * 0.06f * wk
                    c.drawLine(x + tw * 0.1f, uy, x + tw * 0.9f, uy, under)
                }
            }
            x += tw + gap
        }
        barsX0 = x - gap / 2
        // Bağlantı çubukları: devir güncelleme hızına göre (referanstaki sinyal göstergesi)
        val bars = when { !online -> 0; hz >= 5 -> 4; hz >= 3 -> 3; hz >= 1 -> 2; else -> 1 }
        val bw = sz * 0.22f
        for (i in 0 until 4) {
            p.color = if (i < bars) theme.accent else theme.track
            val bh = sz * (0.35f + 0.22f * i)
            c.drawRect(x + i * bw * 1.7f, y - bh, x + i * bw * 1.7f + bw, y, p)
        }
        if (tag != null) {
            p.color = if (sourceName == "Demo") theme.warn else theme.dim; p.textSize = sz * 0.75f
            c.drawText(tag, x + 4 * bw * 1.7f, y, p)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP && barsX0 > 0 && e.x >= barsX0) { onStatusTap?.invoke(); return true }
        if (e.action == MotionEvent.ACTION_UP) {
            hit.indexOfFirst { e.x in it.first..it.second }.takeIf { it >= 0 }?.let { selected = it; onTab(it); invalidate() }
        }
        return true
    }
}

/** Referanstaki "kemik" biçimli panel: iki yuvarlak uç, ortası hafif içe kavisli. Dikeyde yuvarlak dikdörtgen. */
class PanelLayout(ctx: Context) : FrameLayout(ctx) {
    var theme = DashTheme.NIGHT
        set(v) { field = v; invalidate() }
    var bone = true
    var shape = true   // Görsel temada panel resmin içinde: çizme
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val oval = RectF()

    init { setWillNotDraw(false) }


    override fun onDraw(c: Canvas) {
        if (!shape) return
        val w = width.toFloat(); val h = height.toFloat()
        buildPath(w, h)
        p.color = theme.panel
        c.drawPath(path, p)
    }

    private fun buildPath(w: Float, h: Float) {
        path.reset()
        if (!bone || w < h * 1.6f) {
            val r = h.coerceAtMost(w) * 0.08f
            path.addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW)
            return
        }
        val r = h / 2
        val dip = h * 0.07f
        path.moveTo(r, 0f)
        path.cubicTo(w * 0.36f, 0f, w * 0.36f, dip, w / 2, dip)
        path.cubicTo(w * 0.64f, dip, w * 0.64f, 0f, w - r, 0f)
        oval.set(w - 2 * r, 0f, w, h)
        path.arcTo(oval, -90f, 180f)
        path.cubicTo(w * 0.64f, h, w * 0.64f, h - dip, w / 2, h - dip)
        path.cubicTo(w * 0.36f, h - dip, w * 0.36f, h, r, h)
        oval.set(0f, 0f, 2 * r, h)
        path.arcTo(oval, 90f, 180f)
        path.close()
    }
}
