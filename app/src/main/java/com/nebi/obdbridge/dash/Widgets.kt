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
    /** Premium İskandinav (vektör): ince yaylı parlamalı halkalar, marka yazısı, yol süsü. Seçim listesinde gizli. */
    val premium: Boolean = false,
    val bg2: Int = bg,
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
        /** Gizli: Volvo esintili vektör tema (kullanıcı görsel temayı tercih etti, 2026-10-09). */
        val PREMIUM = DashTheme(
            bg = 0xFF15181C.toInt(), panel = 0xFF1A1E23.toInt(), track = 0xFF2E343B.toInt(), text = 0xFFFFFFFF.toInt(),
            dim = 0xFF9AA2A9.toInt(), accent = 0xFF7FB5DD.toInt(), cold = 0xFF5C8BB5.toInt(),
            warn = 0xFFE08B36.toInt(), crit = 0xFFE0603A.toInt(), refined = true, premium = true, bg2 = 0xFF1F2329.toInt(),
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

/** Yuvarlak gösterge: dış yay değeri, ortada büyük rakam (ya da [WidgetSpec.center]), altta isteğe bağlı çubuk. */
@SuppressLint("ViewConstructor")  // yalnız koddan oluşturuluyor
class RingGauge(ctx: Context, spec: WidgetSpec) : DashWidget(ctx, spec) {
    private val arc = RectF()
    private val trackP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val valueP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val zoneP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val barP = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }

    // Premium çizim: fırçalar ve degradeler önbellekte (boyut/tema değişince yeniden kurulur)
    private val arcP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val discP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val dotP = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val ring = RectF()
    private var shaderKey = ""

    init {
        // Gölge katmanı (parlama) şekillerde donanımla Android 9+'da çalışır; öncesinde yazılım çizimi gerekir
        if (android.os.Build.VERSION.SDK_INT < 28) setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(c: Canvas) {
        val d = frame[spec.signal] ?: return
        if (theme.premium) { drawPremium(c, d); return }
        val def = d.def
        val size = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val stroke = size * 0.035f
        val r = size / 2 - stroke * 1.2f
        arc.set(cx - r, cy - r, cx + r, cy + r)
        val start = 135f
        val sweep = 270f

        trackP.color = theme.track; trackP.strokeWidth = stroke * 0.45f
        c.drawArc(arc, start, sweep, false, trackP)

        // Uyarı bölgeleri dış iz üzerinde ince renkli şerit
        zoneP.strokeWidth = stroke * 0.45f
        fun zone(from: Double, to: Double, color: Int) {
            val a = frac(from, def); val b = frac(to, def)
            if (b > a) { zoneP.color = color; c.drawArc(arc, start + sweep * a, sweep * (b - a), false, zoneP) }
        }
        def.warnAbove?.let { zone(it, def.critAbove ?: def.max, theme.warn) }
        def.critAbove?.let { zone(it, def.max, theme.crit) }

        val f = frac(d.value, def)
        valueP.color = theme.levelColor(d.level); valueP.strokeWidth = stroke; valueP.alpha = alphaFor(d)
        if (!d.missing && f > 0.002f) c.drawArc(arc, start, sweep * f, false, valueP)

        // Etiketler
        def.tickStep?.let { step ->
            val tp = paint("tick", theme.dim, size * 0.055f).apply { textAlign = Paint.Align.CENTER }
            var v = def.min
            while (v <= def.max + 1e-6) {
                val ang = Math.toRadians((start + sweep * frac(v, def)).toDouble())
                val tr = r - stroke * 2.6f
                val label = (v / def.tickLabelDiv).let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it) }
                c.drawText(label, cx + (tr * cos(ang)).toFloat(), cy + (tr * sin(ang)).toFloat() + tp.textSize / 3, tp)
                v += step
            }
        }

        // Orta: büyük değer ve birim (ya da başka sinyal, ör. vites)
        val centerKey = spec.center
        val cd = if (centerKey != null) frame[centerKey] else d
        val big = paint("big", theme.text, size * 0.26f, light = true).apply { textAlign = Paint.Align.CENTER; alpha = alphaFor(cd) }
        val text = when {
            cd == null || cd.missing -> if (centerKey == "gear") "N" else "—"
            else -> cd.text
        }
        c.drawText(text, cx, cy + big.textSize * 0.35f, big)
        val small = paint("small", theme.dim, size * 0.06f).apply { textAlign = Paint.Align.CENTER }
        val caption = if (centerKey != null) "${def.format(d.value)} ${def.unit}" else def.unit
        c.drawText(caption, cx, cy + big.textSize * 0.35f + small.textSize * 1.6f, small)

        // Vites önerisi oku
        if (centerKey == "gear" && shift != 0) {
            val ap = paint("arrow", theme.accent, size * 0.11f, bold = true).apply { textAlign = Paint.Align.CENTER }
            c.drawText(if (shift > 0) "▲" else "▼", cx + big.textSize * 0.62f, cy - big.textSize * 0.1f, ap)
        }

        // Alt çubuk (referanstaki yakıt çubuğunun yeri)
        spec.bar?.let { key ->
            val bd = frame[key] ?: return@let
            val bw = r * 0.75f
            val by = cy + r * 0.72f
            val tp = barP.apply { strokeWidth = stroke * 0.45f; color = theme.track }
            c.drawLine(cx - bw / 2, by, cx + bw / 2, by, tp)
            tp.color = theme.levelColor(bd.level); tp.alpha = alphaFor(bd)
            if (!bd.missing) c.drawLine(cx - bw / 2, by, cx - bw / 2 + bw * frac(bd.value, bd.def), by, tp)
            val lp = paint("barlabel", theme.dim, size * 0.05f).apply { textAlign = Paint.Align.CENTER }
            c.drawText("${bd.def.label} ${bd.text}${bd.def.unit}", cx, by - stroke * 0.9f, lp)
        }
    }

    private fun frac(v: Double, def: SignalDef) = ((v - def.min) / (def.max - def.min)).coerceIn(0.0, 1.0).toFloat()

    /**
     * Premium halka: içte RadialGradient disk (3B hacim), ince yuvarlak uçlu değer yayı + altında setShadowLayer ile
     * bulanık ikinci yay (parlama), devirde buz mavisinden kehribara SweepGradient, yayın içinde kısa çentikler.
     */
    private fun drawPremium(c: Canvas, d: Display) {
        val def = d.def
        val size = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val start = 135f
        val sweep = 270f
        val arcW = 5 * dp
        val r = size / 2 - 14 * dp
        ring.set(cx - r, cy - r, cx + r, cy + r)
        val warnF = def.warnAbove?.let { frac(it, def) }
        val isRpm = warnF != null

        val key = "$width/$height/${theme.accent}/${def.key}"
        if (key != shaderKey) {
            shaderKey = key
            val m = android.graphics.Matrix().apply { setRotate(start, cx, cy) }
            val icy = theme.accent
            val icyDim = Color.argb(150, Color.red(icy), Color.green(icy), Color.blue(icy))
            val shader = if (isRpm) {
                android.graphics.SweepGradient(cx, cy, intArrayOf(icy, icy, theme.warn, theme.warn),
                    floatArrayOf(0f, 0.18f, 0.66f, 1f))
            } else {
                android.graphics.SweepGradient(cx, cy, intArrayOf(icyDim, icy, 0xFFDDEBF6.toInt(), 0xFFDDEBF6.toInt()),
                    floatArrayOf(0f, 0.4f, 0.75f, 1f))
            }.apply { setLocalMatrix(m) }
            arcP.shader = shader
            glowP.shader = shader
            discP.shader = android.graphics.RadialGradient(cx, cy - r * 0.15f, r * 0.95f,
                intArrayOf(0xFF59636E.toInt(), 0xFF2B3138.toInt(), theme.bg), floatArrayOf(0f, 0.55f, 1f),
                android.graphics.Shader.TileMode.CLAMP)
        }

        val discR = r - 10 * dp
        c.drawCircle(cx, cy, discR, discP)
        lineP.color = 0xFFFFFFFF.toInt(); lineP.alpha = 22; lineP.strokeWidth = 1 * dp
        c.drawCircle(cx, cy, discR, lineP)
        lineP.alpha = 30; lineP.strokeWidth = 1.5f * dp
        c.drawArc(ring, start, sweep, false, lineP)

        val f = frac(d.value, def)
        if (!d.missing && f > 0.003f) {
            val a = if (d.stale) 110 else 255
            val glowColor = if (isRpm && f > 0.6f) theme.warn else theme.accent
            glowP.strokeWidth = arcW * 1.8f
            glowP.alpha = (a * 0.55f).toInt()
            glowP.setShadowLayer(10 * dp, 0f, 0f, Color.argb((a * 0.75f).toInt(), Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor)))
            c.drawArc(ring, start, sweep * f, false, glowP)
            arcP.strokeWidth = arcW
            arcP.alpha = a
            c.drawArc(ring, start, sweep * f, false, arcP)
            val ang = Math.toRadians((start + sweep * f).toDouble())
            dotP.color = 0xFFFFFFFF.toInt(); dotP.alpha = a; dotP.strokeWidth = arcW * 0.9f
            c.drawPoint(cx + r * cos(ang).toFloat(), cy + r * sin(ang).toFloat(), dotP)
        }

        val tickOut = discR - 6 * dp
        def.tickStep?.let { step ->
            val minor = step / 5
            var v = def.min
            var i = 0
            while (v <= def.max + 1e-6) {
                val fr = frac(v, def)
                val ang = Math.toRadians((start + sweep * fr).toDouble())
                val co = cos(ang).toFloat(); val si = sin(ang).toFloat()
                val red = warnF != null && fr >= warnF
                if (i % 5 == 0) {
                    lineP.color = if (red) theme.warn else 0xFFFFFFFF.toInt()
                    lineP.alpha = if (red) 200 else 120
                    lineP.strokeWidth = 1.5f * dp
                    val r1 = tickOut - 7 * dp
                    c.drawLine(cx + r1 * co, cy + r1 * si, cx + tickOut * co, cy + tickOut * si, lineP)
                } else {
                    dotP.color = if (red) theme.warn else 0xFFFFFFFF.toInt()
                    dotP.alpha = if (red) 150 else 60
                    dotP.strokeWidth = 2 * dp
                    c.drawPoint(cx + tickOut * co, cy + tickOut * si, dotP)
                }
                v += minor; i++
            }
            val tp = paint("ptick", 0xFFFFFFFF.toInt(), size * 0.048f, light = true).apply {
                textAlign = Paint.Align.CENTER; alpha = 170
            }
            val lr = tickOut - 18 * dp
            v = def.min
            while (v <= def.max + 1e-6) {
                val fr = frac(v, def)
                val ang = Math.toRadians((start + sweep * fr).toDouble())
                val label = (v / def.tickLabelDiv).let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it) }
                tp.color = if (warnF != null && fr >= warnF) theme.warn else 0xFFFFFFFF.toInt()
                tp.alpha = 170
                c.drawText(label, cx + (lr * cos(ang)).toFloat(), cy + (lr * sin(ang)).toFloat() + tp.textSize / 3, tp)
                v += step
            }
        }

        val centerKey = spec.center
        val cd = if (centerKey != null) frame[centerKey] else d
        val big = paint("pbig", theme.text, size * 0.25f, thin = true).apply { textAlign = Paint.Align.CENTER; alpha = alphaFor(cd) }
        val text = if (cd == null || cd.missing) (if (centerKey == "gear") "N" else "—") else cd.text
        c.drawText(text, cx, cy + big.textSize * 0.33f, big)
        val small = paint("psmall", theme.dim, size * 0.05f, light = true).apply {
            textAlign = Paint.Align.CENTER; letterSpacing = 0.08f
        }
        val caption = if (centerKey != null) "${def.format(d.value)} ${def.unit}" else def.unit
        c.drawText(caption, cx, cy + big.textSize * 0.33f + small.textSize * 1.9f, small)
        if (centerKey == "gear" && shift != 0) {
            val ap = paint("parrow", theme.accent, size * 0.07f, light = true).apply { textAlign = Paint.Align.CENTER }
            c.drawText(if (shift > 0) "▲" else "▼", cx + big.textSize * 0.55f, cy - big.textSize * 0.12f, ap)
        }

        spec.bar?.let { k ->
            val bd = frame[k] ?: return@let
            val bw = discR * 0.75f
            val by = cy + discR * 0.66f
            barP.strokeWidth = 2 * dp; barP.color = 0xFFFFFFFF.toInt(); barP.alpha = 35
            barP.clearShadowLayer()
            c.drawLine(cx - bw / 2, by, cx + bw / 2, by, barP)
            if (!bd.missing) {
                val col = if (bd.level == Level.NORMAL) 0xFFE6EEF5.toInt() else theme.levelColor(bd.level)
                barP.color = col; barP.alpha = alphaFor(bd); barP.strokeWidth = 3 * dp
                barP.setShadowLayer(6 * dp, 0f, 0f, Color.argb(160, Color.red(col), Color.green(col), Color.blue(col)))
                c.drawLine(cx - bw / 2, by, cx - bw / 2 + bw * frac(bd.value, bd.def), by, barP)
                barP.clearShadowLayer()
            }
            val lp = paint("pbarlabel", theme.dim, size * 0.042f, light = true).apply { textAlign = Paint.Align.CENTER; letterSpacing = 0.08f }
            c.drawText("${bd.def.label.uppercase(TR)} ${bd.text}${bd.def.unit}", cx, by - 7 * dp, lp)
        }
    }
}

private val TR = Locale("tr")

/** Bilgi kutucuğu: etiket, büyük değer, birim; uyarıda renk ve kısa metin (renk körü sürücü için). */
@SuppressLint("ViewConstructor")  // yalnız koddan oluşturuluyor
class ValueTile(ctx: Context, spec: WidgetSpec, private val showRate: Boolean = false) : DashWidget(ctx, spec) {
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
        if (showRate && !d.missing) {
            val sub = paint("sub", theme.dim, label.textSize * 0.85f)
            c.drawText(if (d.ageMs < 60_000) "${d.ageMs} ms önce" else "eski", 0f, y + sub.textSize * 1.5f, sub)
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
        if (showTempClock) items += tempText to theme.text
        tabs.forEachIndexed { i, t -> items += t to (if (i == selected) theme.text else theme.dim) }
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
            p.color = it.second
            c.drawText(it.first, x, y, p)
            val tw = p.measureText(it.first)
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

    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var gradKey = ""

    override fun onDraw(c: Canvas) {
        if (!shape) return
        val w = width.toFloat(); val h = height.toFloat()
        buildPath(w, h)
        if (theme.premium) {
            // Panel ortadan kenarlara koyulaşır + buz mavisi ince kenar parlaması
            val key = "$w/$h"
            if (key != gradKey) {
                gradKey = key
                p.shader = android.graphics.RadialGradient(w / 2, h * 0.42f, maxOf(w, h) * 0.6f,
                    intArrayOf(0xFF242930.toInt(), theme.panel, 0xFF121518.toInt()), floatArrayOf(0f, 0.6f, 1f),
                    android.graphics.Shader.TileMode.CLAMP)
            }
            c.drawPath(path, p)
            for ((wk, a) in listOf(6f to 8, 3f to 16, 1f to 55)) {
                edge.color = theme.accent; edge.alpha = a; edge.strokeWidth = wk * resources.displayMetrics.density
                c.drawPath(path, edge)
            }
        } else {
            p.shader = null; gradKey = ""
            p.color = theme.panel
            c.drawPath(path, p)
        }
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

/** Premium (gizli tema): panelin üst ortasında geniş aralıklı marka/tema yazısı. */
@SuppressLint("ViewConstructor")  // yalnız koddan oluşturuluyor
class BrandLabel(ctx: Context, private val text: String) : View(ctx) {
    var theme = DashTheme.PREMIUM
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; letterSpacing = 0.6f
        typeface = Typeface.create("serif", Typeface.NORMAL)
    }

    override fun onDraw(c: Canvas) {
        p.textSize = height * 0.62f
        val y = height * 0.72f
        p.style = Paint.Style.STROKE; p.strokeWidth = height * 0.08f; p.color = theme.accent; p.alpha = 40
        c.drawText(text, width / 2f, y, p)
        p.style = Paint.Style.FILL; p.color = theme.text; p.alpha = 235
        c.drawText(text, width / 2f, y, p)
    }
}

/** Premium (gizli tema) süsü: perspektifli şerit çizgileri arasında arkadan görünen sade araç. */
class RoadView(ctx: Context) : View(ctx) {
    var theme = DashTheme.PREMIUM
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2
        for ((dx, a) in listOf(0.18f to 140, 0.46f to 60)) {
            for (sgn in listOf(-1f, 1f)) {
                for ((wk, ga) in listOf(4f to 0.15f, 1f to 1f)) {
                    line.color = theme.accent; line.alpha = (a * ga).toInt(); line.strokeWidth = wk * 1.5f
                    c.drawLine(cx + sgn * w * dx * 0.35f, h * 0.05f, cx + sgn * w * dx, h, line)
                }
            }
        }
        val cw = h * 0.62f; val ch = h * 0.5f
        val left = cx - cw / 2; val top = h * 0.38f
        fill.color = theme.track
        c.drawRoundRect(left + cw * 0.06f, top + ch * 0.82f, left + cw * 0.24f, top + ch * 1.02f, 3f, 3f, fill)
        c.drawRoundRect(left + cw * 0.76f, top + ch * 0.82f, left + cw * 0.94f, top + ch * 1.02f, 3f, 3f, fill)
        fill.color = 0xFF5D646C.toInt()
        path.reset()
        path.moveTo(left + cw * 0.2f, top); path.lineTo(left + cw * 0.8f, top)
        path.lineTo(left + cw * 0.92f, top + ch * 0.38f); path.lineTo(left + cw * 0.08f, top + ch * 0.38f); path.close()
        c.drawPath(path, fill)
        fill.color = 0xFF8C949C.toInt()
        c.drawRoundRect(left, top + ch * 0.36f, left + cw, top + ch * 0.9f, cw * 0.08f, cw * 0.08f, fill)
        fill.color = 0xFF2A3036.toInt()
        c.drawRoundRect(left + cw * 0.24f, top + ch * 0.06f, left + cw * 0.76f, top + ch * 0.32f, 4f, 4f, fill)
        fill.color = 0xFFE23B32.toInt()
        c.drawRoundRect(left + cw * 0.02f, top + ch * 0.44f, left + cw * 0.3f, top + ch * 0.54f, 3f, 3f, fill)
        c.drawRoundRect(left + cw * 0.7f, top + ch * 0.44f, left + cw * 0.98f, top + ch * 0.54f, 3f, 3f, fill)
    }
}

/** Navigasyon: sıradaki dönüş oku (navigasyonun kendi çizimi, temaya boyanır), mesafe ve sokak. */
class NaviView(ctx: Context) : View(ctx) {
    var theme = DashTheme.NIGHT
    var state: com.nebi.obdbridge.NaviState? = null
    private val bmpP = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val distP = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
    private val streetP = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
    private val dst = RectF()
    private var tint = 0

    override fun onDraw(c: Canvas) {
        val st = state ?: return
        val h = height.toFloat()
        var x = 0f
        st.arrow?.let { b ->
            val color = if (theme.refined || theme.bg == DashTheme.NIGHT.bg) Color.WHITE else theme.text
            if (tint != color) {
                tint = color
                bmpP.colorFilter = android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
            }
            val side = h * 0.9f
            val sc = side / maxOf(b.width, b.height)
            val bw = b.width * sc; val bh = b.height * sc
            dst.set(x, (h - bh) / 2, x + bw, (h + bh) / 2)
            c.drawBitmap(b, null, dst, bmpP)
            x += side + h * 0.25f
        }
        distP.color = theme.text; distP.textSize = h * 0.48f
        c.drawText(st.distance, x, h * 0.5f, distP)
        streetP.color = theme.dim; streetP.textSize = h * 0.28f
        // Sokak adı sığmazsa kısalt
        var street = st.street
        val avail = width - x
        while (street.length > 3 && streetP.measureText(street) > avail) street = street.dropLast(2) + "…"
        c.drawText(street, x, h * 0.9f, streetP)
    }
}
