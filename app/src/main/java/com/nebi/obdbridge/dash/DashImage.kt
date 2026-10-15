package com.nebi.obdbridge.dash

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.widget.FrameLayout
import com.nebi.obdbridge.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * "Görsel" teması: referans tasarımın kendisi resim katmanı olarak kullanılır (tools/dash_assets/make_layers.py).
 *  - dash_base.jpg: kadran bantları sönük, yazılar silinmiş taban
 *  - dash_lit.jpg : bantlar ışıklı; değere kadar olan dilim bundan kesilip tabanın üstüne çizilir
 * Rakamlar, kutucuklar ve üst çubuk resimdeki yerlerine canlı çizilir. Koordinatlar 2400x1080 resim pikseli.
 */
object DashImageGeom {
    const val W = 2400f
    const val H = 1080f
    val SPEED_C = floatArrayOf(502.4f, 547.6f)
    val RPM_C = floatArrayOf(1922.9f, 547.6f)
    const val BAND_IN = 211f      // ışıklı bandın iç yarıçapı
    const val BAND_OUT = 355f     // dış yarıçapı (çentikli dış bant dahil)
    const val BIG_SIZE = 205f     // ortadaki büyük rakam
    const val BIG_BASE = 616f     // büyük rakamın taban çizgisi (y)
    const val CAP_SIZE = 46f
    const val CAP_BASE = 681f
    val RPM_BAR = floatArrayOf(1791f, 786f, 2053f)   // x0, y, x1
    val TOPBAR = RectF(715f, 32f, 1655f, 123f)
    /** Büyük hava (sol üst) ve saat (sağ üst): halkaların üstündeki çerçeve alanı */
    val WEATHER = RectF(150f, 26f, 690f, 150f)
    val CLOCK = RectF(1600f, 26f, 2140f, 150f)   // sağ köşe Android gizlilik noktasına (konum/mikrofon) yer bırakır
    /** Orta kutucuklar: sol üst, sağ üst, sol alt, sağ alt */
    val TILES = listOf(
        RectF(916f, 233f, 1222f, 414f), RectF(1236f, 233f, 1547f, 414f),
        RectF(916f, 625f, 1222f, 806f), RectF(1236f, 625f, 1547f, 806f),
    )
    val NAVI = RectF(930f, 440f, 1540f, 600f)
    /** Araç ekranı orta sütunu (HEADUNIT.md): ekran yazısının altından yol çizimine kadar */
    val NAV_CARD = RectF(900f, 205f, 1525f, 470f)
    val MEDIA_CARD = RectF(900f, 492f, 1525f, 835f)
    /** Alt değer şeridi: yol çiziminin iki yanı (araç x≈1150..1250) */
    val STRIP = RectF(780f, 855f, 1620f, 990f)
    /** Uyarı hapı: ekran yazısının üstü (yazı x≈1080..1330, y≈135..170) */
    val PILL = RectF(860f, 118f, 1540f, 188f)
    /** Sinyal okları (ESP32 kutusu, Volvo): hava/saat yazılarının altı, halkaların üstündeki boşluk */
    val BLINK_L = floatArrayOf(780f, 172f)
    val BLINK_R = floatArrayOf(1620f, 172f)
    const val BLINK_SIZE = 84f
    /** Devir halkasında rpm yazısının altındaki kısa not ("yakıt kesik") */
    const val NOTE_BASE = 742f
    const val START = 135f
    const val SWEEP = 270f
    /** Kadran rakamları (resimden Gemini ile silindi, araç profiline göre canlı çiziliyor) */
    const val LABEL_R = 278f
    const val LABEL_SIZE = 36f
    /** Çentikler: rakamların dışında, bandın dış kenarına yakın (2400 ölçeği) */
    const val TICK_R0 = 312f
    const val TICK_MAJOR = 26f
    const val TICK_MINOR = 13f
    /** İsteğe bağlı ekran yazısı (Vehicle.badge), referanstaki marka yazısının yeri */
    val LOGO = RectF(1068f, 132f, 1068f + 271f, 132f + 48f)
}

/** Resmi ekrana sığdırır (oranı korur, ortalar) ve çocuk görünümleri resimdeki yerlerine koyar. */
@SuppressLint("ViewConstructor")  // yalnız koddan oluşturuluyor
class DashImageLayout(ctx: Context, private val dials: DashImageDials) : FrameLayout(ctx) {
    private val placed = ArrayList<Pair<android.view.View, RectF>>()

    init {
        addView(dials, LayoutParams(-1, -1))
    }

    /** [r] resim koordinatlarında; yerleşim sırasında ekrana çevrilir. */
    fun place(v: android.view.View, r: RectF) {
        placed += v to r
        addView(v, LayoutParams(1, 1))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val (s, ox, oy) = dials.fit(width, height)
        for ((v, rc) in placed) {
            val w = (rc.width() * s).toInt(); val h = (rc.height() * s).toInt()
            if (v.measuredWidth != w || v.measuredHeight != h) {
                v.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            }
            val x = (ox + rc.left * s).toInt(); val y = (oy + rc.top * s).toInt()
            v.layout(x, y, x + w, y + h)
        }
    }
}

/** Kadranlar: taban resmi, değere kadar ışıklı dilim, ortadaki rakamlar ve devir çubuğu. */
@SuppressLint("ViewConstructor")  // yalnız koddan oluşturuluyor
class DashImageDials(ctx: Context, spec: WidgetSpec, private val th: ImageTheme = ImageTheme.ALL[0]) : DashWidget(ctx, spec) {
    /** Devir halkasında rpm altında kısa not (yakıt kesik / boşta); null = yok */
    var note: String? = null
    /** Aracın sinyal lambaları: bit0 sol, bit1 sağ (yanıp sönmeyi araç yapar, burada yalnız yansıtılır) */
    var blink = 0
    private val blinkP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3DDC6E.toInt() }
    private val arrowPath = Path()
    private val base: Bitmap = load(ctx, th.base)
    private val lit: Bitmap = load(ctx, th.lit)
    /** Resmin kenar rengi: ekran oranı farklıysa kalan şerit bununla boyanır */
    private val edgeColor = base.getPixel(4, base.height / 2)
    private val ink = if (th.light) 0xFF15181C.toInt() else Color.WHITE
    private val badgeP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
        textAlign = Paint.Align.CENTER; letterSpacing = 0.55f
        color = if (th.light) 0xFF2A2F36.toInt() else 0xFFF2F5F8.toInt()
    }
    private val tickP = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val labelP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    private val bmpP = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val litP = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val edgeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val bigP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); fontFeatureSettings = "tnum"
    }
    private val capP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); letterSpacing = 0.03f
    }
    private val barP = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val dst = RectF()
    private val oval = RectF()
    private val path = Path()
    private val shaderM = Matrix()
    private var fitKey = ""
    private var s = 1f; private var ox = 0f; private var oy = 0f

    override fun keys() = listOf("speed", "rpm", "gear", spec.bar ?: "coolant")

    /** (ölçek, x ofseti, y ofseti): resmi oranını koruyarak ortala. */
    fun fit(w: Int, h: Int): Triple<Float, Float, Float> {
        val sc = min(w / DashImageGeom.W, h / DashImageGeom.H)
        return Triple(sc, (w - DashImageGeom.W * sc) / 2, (h - DashImageGeom.H * sc) / 2)
    }

    override fun onDraw(c: Canvas) {
        val key = "$width/$height"
        if (key != fitKey) {
            fitKey = key
            val (sc, x, y) = fit(width, height)
            s = sc; ox = x; oy = y
            dst.set(ox, oy, ox + DashImageGeom.W * s, oy + DashImageGeom.H * s)
            shaderM.setScale(s, s); shaderM.postTranslate(ox, oy)
            litP.shader = BitmapShader(lit, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(shaderM) }
        }
        c.drawColor(edgeColor)   // resmin kenarındaki çerçeveyle aynı ton
        c.drawBitmap(base, null, dst, bmpP)

        val v = Vehicle.current
        if (v.badge.isNotBlank()) {
            val r = DashImageGeom.LOGO
            badgeP.textSize = 34f * s
            c.drawText(v.badge.uppercase(), ox + r.centerX() * s, oy + (r.centerY() + 12f) * s, badgeP)
        }
        dial(c, DashImageGeom.SPEED_C, frame["speed"], centerKey = null)
        // Otomatik vites: vites tahmini yok, ortada devir yazar
        dial(c, DashImageGeom.RPM_C, frame["rpm"], centerKey = if (v.manual) "gear" else null)
        // Rakamlar ışıklı dilimin üstüne: hız 20'şer (240 ve üstünde 40'ar), devir binlik
        val sMax = v.speedMax
        val sStep = if (sMax > 220) 40 else 20
        ticks(c, DashImageGeom.SPEED_C, sMax / 10, 2, frame["speed"])                  // 10 km/h'de bir, 20'de büyük
        ticks(c, DashImageGeom.RPM_C, v.rpmMax / 500, 2, frame["rpm"])                // 500 rpm'de bir, binde büyük
        labels(c, DashImageGeom.SPEED_C, (0..sMax step sStep).map { n -> n.toString() }, frame["speed"], th.speedLitLabel ?: th.litLabel)
        labels(c, DashImageGeom.RPM_C, (0..v.rpmMax / 1000).map { n -> n.toString() }, frame["rpm"], th.litLabel)

        if (blink and 1 != 0) arrow(c, DashImageGeom.BLINK_L, left = true)
        if (blink and 2 != 0) arrow(c, DashImageGeom.BLINK_R, left = false)

        // Devir kadranının altındaki ince çubuk (varsayılan: motor sıcaklığı)
        frame[spec.bar ?: "coolant"]?.let { bd ->
            val (x0, y, x1) = DashImageGeom.RPM_BAR
            val ax0 = ox + x0 * s; val ax1 = ox + x1 * s; val ay = oy + y * s
            barP.strokeWidth = 11 * s; barP.color = ink; barP.alpha = 45
            c.drawLine(ax0, ay, ax1, ay, barP)
            if (!bd.missing) {
                barP.color = if (bd.level == Level.NORMAL) (if (th.light) 0xFF15181C.toInt() else 0xFFF2F5F8.toInt()) else theme.levelColor(bd.level)
                barP.alpha = alphaFor(bd)
                val f = ((bd.value - bd.def.min) / (bd.def.max - bd.def.min)).coerceIn(0.0, 1.0).toFloat()
                c.drawLine(ax0, ay, ax0 + (ax1 - ax0) * f, ay, barP)
            }
        }
    }

    /** Gösterge panelindeki gibi dolu yeşil ok: sap + üçgen uç */
    private fun arrow(c: Canvas, at: FloatArray, left: Boolean) {
        val z = DashImageGeom.BLINK_SIZE * s
        val cx = ox + at[0] * s; val cy = oy + at[1] * s
        val d = if (left) -1f else 1f
        arrowPath.reset()
        arrowPath.moveTo(cx + d * z * 0.5f, cy)                 // uç
        arrowPath.lineTo(cx, cy - z * 0.45f)
        arrowPath.lineTo(cx, cy - z * 0.18f)
        arrowPath.lineTo(cx - d * z * 0.5f, cy - z * 0.18f)
        arrowPath.lineTo(cx - d * z * 0.5f, cy + z * 0.18f)
        arrowPath.lineTo(cx, cy + z * 0.18f)
        arrowPath.lineTo(cx, cy + z * 0.45f)
        arrowPath.close()
        blinkP.setShadowLayer(z * 0.25f, 0f, 0f, 0x803DDC6E.toInt())
        c.drawPath(arrowPath, blinkP)
    }

    /** [n] aralıklı çentik; her [majorEvery]'de bir büyük. Işıklı dilimin üstündekiler temanın çentik rengiyle. */
    private fun ticks(c: Canvas, center: FloatArray, n: Int, majorEvery: Int, d: Display?) {
        val cx = ox + center[0] * s; val cy = oy + center[1] * s
        val f = if (d == null || d.missing) 0f else ((d.value - d.def.min) / (d.def.max - d.def.min)).coerceIn(0.0, 1.0).toFloat()
        val r0 = DashImageGeom.TICK_R0 * s
        for (i in 0..n) {
            val frac = i.toFloat() / n
            val major = i % majorEvery == 0
            val a = Math.toRadians((DashImageGeom.START + DashImageGeom.SWEEP * frac).toDouble())
            val len = (if (major) DashImageGeom.TICK_MAJOR else DashImageGeom.TICK_MINOR) * s
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            val lit = frac <= f
            tickP.color = if (lit) th.tickLit ?: (th.speedLitLabel ?: th.litLabel) else th.tickDim ?: th.dimLabel
            tickP.alpha = if (major) 230 else 150
            tickP.strokeWidth = (if (major) 4.5f else 2.5f) * s
            // Büyükler dışa doğru uzar, küçükler dış kenarda kısa
            val ra = if (major) r0 else r0 + len
            c.drawLine(cx + ra * ca, cy + ra * sa, cx + (r0 + DashImageGeom.TICK_MAJOR * s) * ca, cy + (r0 + DashImageGeom.TICK_MAJOR * s) * sa, tickP)
        }
    }

    /** Kadran rakamları; ışıklı dilimin üstündekiler temanın [ImageTheme.litLabel] renginde. */
    private fun labels(c: Canvas, center: FloatArray, texts: List<String>, d: Display?, litColor: Int) {
        val cx = ox + center[0] * s; val cy = oy + center[1] * s
        val f = if (d == null || d.missing) 0f else ((d.value - d.def.min) / (d.def.max - d.def.min)).coerceIn(0.0, 1.0).toFloat()
        labelP.textSize = DashImageGeom.LABEL_SIZE * s
        val n = texts.size - 1
        for ((i, t) in texts.withIndex()) {
            val frac = i.toFloat() / n
            val a = Math.toRadians((DashImageGeom.START + DashImageGeom.SWEEP * frac).toDouble())
            // Alt uçlardaki rakamlar referansta biraz içeride
            val r = DashImageGeom.LABEL_R * s * (if (frac < 0.08f || frac > 0.92f) 0.955f else 1f)
            val x = cx + r * cos(a).toFloat(); val y = cy + r * sin(a).toFloat() + labelP.textSize * 0.36f
            labelP.color = if (frac <= f) litColor else th.dimLabel
            // Desenli bantta okunurluk: rakamın tersi renkte hafif hâle
            val lum = 0.2126 * Color.red(labelP.color) + 0.7152 * Color.green(labelP.color) + 0.0722 * Color.blue(labelP.color)
            labelP.setShadowLayer(labelP.textSize * 0.2f, 0f, 0f, if (lum > 128) 0xE8000000.toInt() else 0xD0FFFFFF.toInt())
            c.drawText(t, x, y, labelP)
        }
    }

    private fun dial(c: Canvas, center: FloatArray, d: Display?, centerKey: String?) {
        val cx = ox + center[0] * s
        val cy = oy + center[1] * s
        if (d != null && !d.missing) {
            val f = ((d.value - d.def.min) / (d.def.max - d.def.min)).coerceIn(0.0, 1.0).toFloat()
            val sw = DashImageGeom.SWEEP * f
            if (sw > 0.3f) {
                // Işıklı dilim: halka kesiti, ışıklı resimden boyanır (kenarlar yumuşak)
                val ro = th.bandOut * s; val ri = th.bandIn * s
                path.reset()
                oval.set(cx - ro, cy - ro, cx + ro, cy + ro)
                path.arcTo(oval, DashImageGeom.START, sw, true)
                oval.set(cx - ri, cy - ri, cx + ri, cy + ri)
                path.arcTo(oval, DashImageGeom.START + sw, -sw)
                path.close()
                litP.alpha = alphaFor(d)
                c.drawPath(path, litP)
                // Dilim ucunda ince parlak kenar (ibre hissi)
                val a = Math.toRadians((DashImageGeom.START + sw).toDouble())
                edgeP.color = ink; edgeP.alpha = (alphaFor(d) * 0.8f).toInt(); edgeP.strokeWidth = 4 * s
                c.drawLine(cx + ri * cos(a).toFloat(), cy + ri * sin(a).toFloat(),
                    cx + ro * cos(a).toFloat(), cy + ro * sin(a).toFloat(), edgeP)
            }
        }
        // Orta: büyük rakam (ya da vites) ve altında birim
        val cd = if (centerKey != null) frame[centerKey] else d
        val big = when {
            cd == null || cd.missing -> if (centerKey == "gear") "N" else "—"
            else -> cd.text
        }
        bigP.textSize = DashImageGeom.BIG_SIZE * s; bigP.color = Color.WHITE; bigP.alpha = alphaFor(cd)
        c.drawText(big, cx, oy + DashImageGeom.BIG_BASE * s, bigP)
        val cap = when {
            d == null -> ""
            centerKey != null -> if (d.missing) "— ${d.def.unit}" else "${d.def.format(d.value)} ${d.def.unit}"
            else -> d.def.unit
        }
        capP.textSize = DashImageGeom.CAP_SIZE * s; capP.color = 0xFFC9D0D6.toInt()
        c.drawText(cap, cx, oy + DashImageGeom.CAP_BASE * s, capP)
        if (centerKey == "gear") note?.let {
            capP.textSize = DashImageGeom.CAP_SIZE * s * 0.62f; capP.color = th.accent
            c.drawText(it, cx, oy + DashImageGeom.NOTE_BASE * s, capP)
        }
        if (centerKey == "gear" && shift != 0) {
            capP.color = theme.accent; capP.textSize = DashImageGeom.CAP_SIZE * s
            c.drawText(if (shift > 0) "▲" else "▼", cx + DashImageGeom.BIG_SIZE * s * 0.55f, oy + (DashImageGeom.BIG_BASE - 110) * s, capP)
        }
    }

    companion object {
        private fun load(ctx: Context, id: Int): Bitmap =
            BitmapFactory.decodeResource(ctx.resources, id, BitmapFactory.Options().apply { inScaled = false })
    }
}
