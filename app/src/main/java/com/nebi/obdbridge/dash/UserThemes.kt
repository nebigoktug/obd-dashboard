package com.nebi.obdbridge.dash

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Kullanıcının kendi temaları (2026-10-18): şablon çerçeveyi (assets/theme_template.jpg, referansla aynı) bir yapay zekâya
 * kendi tarzında yeniden çizdiren kullanıcı tek resim seçer; sönük taban, rakam renkleri, vurgu rengi ve önizleme
 * telefonda üretilir (tools/dash_assets/make_theme.py'nin aynısı, bant referans temanınki).
 * Dosyalar: files/themes/<id>/{lit,base,thumb}.webp + theme.json.
 */
object UserThemes {
    const val TEMPLATE_ASSET = "theme_template.jpg"
    /** Şablonla birlikte yapay zekâya gönderilen istek (kullanıcı [TARZINI YAZ] yerine tarzını yazar) */
    const val PROMPT = "Bu araç gösterge paneli resmini şu tarzda yeniden çiz: [TARZINI YAZ]. " +
        "İki yuvarlak kadranın yerini, boyutunu ve halka kalınlığını aynen koru; resmin oranı ve dış çerçevesi aynı kalsın. " +
        "Halkalar dolu ve parlak olsun. Rakam, yazı, ibre ya da logo ekleme. Ortadaki küçük araba ve yol çizgileri kalabilir."

    // Referans çerçeve (1376x768) ve uygulamanın kestiği bölüm: make_theme.py ile aynı
    private const val FW = 1376; private const val FH = 768
    private val CROP = Rect(24, 92, 1352, 690)
    private const val BAND_IN = 211f; private const val BAND_OUT = 355f

    private fun dir(ctx: Context) = File(ctx.filesDir, "themes")

    /** Kayıtlı temaları yükle ([ImageTheme.user]) */
    fun load(ctx: Context) {
        ImageTheme.user = dir(ctx).listFiles()?.sortedBy { it.name }?.mapNotNull { d ->
            runCatching {
                val j = JSONObject(File(d, "theme.json").readText())
                ImageTheme(d.name, j.getString("name"), 0, 0, 0, BAND_IN, BAND_OUT,
                    j.getInt("litLabel"), j.getInt("dimLabel"), light = j.getBoolean("light"), accent = j.getInt("accent"),
                    cardAlpha = 215, dir = d)
            }.getOrNull()
        } ?: emptyList()
    }

    fun delete(ctx: Context, t: ImageTheme) { t.dir?.deleteRecursively(); load(ctx) }

    /** Hazırlanmış tema (henüz kaydedilmedi) */
    class Draft(val lit: Bitmap, val base: Bitmap, val light: Boolean, val litLabel: Int, val dimLabel: Int, val accent: Int)

    /** Resmi çerçeveye oturt ve katmanları üret (arka planda çağır) */
    fun prepare(ctx: Context, uri: Uri): Draft {
        val lit = frame(ctx, uri)
        val w = lit.width; val h = lit.height
        val px = IntArray(w * h); lit.getPixels(px, 0, w, 0, 0, w, h)
        // Açık mı koyu mu: iki halkanın arası (make_theme.py "panel")
        val panel = avg(px, w, 968, 195, 1456, 430)
        val light = luma(panel) > 0.55
        val out = px.copyOf()
        var litSum = 0.0; var dimSum = 0.0; var n = 0
        var sr = 0.0; var sg = 0.0; var sb = 0.0; var sn = 0
        val hsv = FloatArray(3)
        for (c in listOf(DashImageGeom.SPEED_C, DashImageGeom.RPM_C)) {
            val cx = c[0]; val cy = c[1]
            for (y in (cy - BAND_OUT - 2).toInt().coerceAtLeast(0)..(cy + BAND_OUT + 2).toInt().coerceAtMost(h - 1))
                for (x in (cx - BAND_OUT - 2).toInt().coerceAtLeast(0)..(cx + BAND_OUT + 2).toInt().coerceAtMost(w - 1)) {
                    val r = hypot(x - cx, y - cy)
                    val a = ((Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())) - 135) % 360 + 360) % 360
                    if (a > 271) continue   // alttaki açıklık zemin, karartılmaz
                    // Yumuşak kenar (~2 px)
                    val t = (((r - BAND_IN) / 2.4f + 0.5f).coerceIn(0f, 1f) * ((BAND_OUT - r) / 2.4f + 0.5f).coerceIn(0f, 1f))
                    if (t <= 0f) continue
                    val i = y * w + x; val p = px[i]
                    val pr = Color.red(p).toFloat(); val pg = Color.green(p).toFloat(); val pb = Color.blue(p).toFloat()
                    val dr: Float; val dg: Float; val db: Float
                    if (!light) {
                        val g = (pr + pg + pb) / 3 * 0.15f
                        dr = (pr * 0.85f + g) * 0.38f; dg = (pg * 0.85f + g) * 0.38f; db = (pb * 0.85f + g) * 0.38f
                    } else {
                        dr = pr * 0.4f + Color.red(panel) * 0.6f; dg = pg * 0.4f + Color.green(panel) * 0.6f; db = pb * 0.4f + Color.blue(panel) * 0.6f
                    }
                    val d = Color.rgb(dr.toInt(), dg.toInt(), db.toInt())
                    out[i] = Color.rgb((pr * (1 - t) + dr * t).toInt(), (pg * (1 - t) + dg * t).toInt(), (pb * (1 - t) + db * t).toInt())
                    if (t > 0.9f) {
                        litSum += luma(p); dimSum += luma(d); n++
                        Color.colorToHSV(p, hsv)
                        if (hsv[1] > 0.35f && hsv[2] > 0.35f) { sr += pr; sg += pg; sb += pb; sn++ }
                    }
                }
        }
        val base = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        val litL = if (n > 0) litSum / n else 0.5
        val dimL = if (n > 0) dimSum / n else 0.3
        val accent = if (sn > 0) {
            Color.colorToHSV(Color.rgb((sr / sn).toInt(), (sg / sn).toInt(), (sb / sn).toInt()), hsv)
            hsv[1] = hsv[1].coerceAtLeast(0.45f); hsv[2] = if (light) hsv[2].coerceIn(0.35f, 0.7f) else hsv[2].coerceAtLeast(0.8f)
            Color.HSVToColor(hsv)
        } else if (light) 0xFF1F6FD1.toInt() else 0xFF7FB5DD.toInt()
        return Draft(lit, base, light,
            litLabel = if (litL > 0.55) 0xFF20252B.toInt() else 0xFFF2F4F6.toInt(),
            dimLabel = if (dimL > 0.5) 0xFF2A2F36.toInt() else 0xFFC8CCD0.toInt(),
            accent = accent)
    }

    /** Önizleme: halka bandının olması gereken yeri kesikli çizgiyle gösterir (kaymışsa kullanıcı görsün) */
    fun preview(d: Draft): Bitmap {
        val s = 0.4f
        val b = Bitmap.createBitmap((d.lit.width * s).toInt(), (d.lit.height * s).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawBitmap(d.lit, null, RectF(0f, 0f, b.width.toFloat(), b.height.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 3f; color = 0xFFFF3B6B.toInt()
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 7f), 0f)
        }
        for (cc in listOf(DashImageGeom.SPEED_C, DashImageGeom.RPM_C))
            for (r in listOf(BAND_IN, BAND_OUT)) c.drawCircle(cc[0] * s, cc[1] * s, r * s, p)
        return b
    }

    /** Kaydet; yeni temayı döndürür */
    fun save(ctx: Context, d: Draft, name: String): ImageTheme {
        val id = "user_" + System.currentTimeMillis()
        val dd = File(dir(ctx), id).apply { mkdirs() }
        webp(d.lit, File(dd, "lit.webp")); webp(d.base, File(dd, "base.webp")); webp(thumb(d), File(dd, "thumb.webp"))
        File(dd, "theme.json").writeText(JSONObject().put("name", name).put("light", d.light).put("litLabel", d.litLabel)
            .put("dimLabel", d.dimLabel).put("accent", d.accent).toString())
        load(ctx)
        return ImageTheme.byId(id)
    }

    /** Şablonu paylaşım için önbelleğe kopyala (ShareProvider yalnız cache/share'den verir) */
    fun templateFile(ctx: Context): File {
        val f = File(File(ctx.cacheDir, "share").apply { mkdirs() }, "gosterge_tema_sablonu.jpg")
        if (!f.exists()) ctx.assets.open(TEMPLATE_ASSET).use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    /** Seçilen resmi 2400x1080 çerçeveye getir: 2,22:1 ise doğrudan, değilse şablon gibi 16:9 kabul edip kes */
    private fun frame(ctx: Context, uri: Uri): Bitmap {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, o) }
        require(o.outWidth > 0 && o.outHeight > 0) { "Resim okunamadı" }
        var sample = 1
        while (o.outWidth / (sample * 2) >= 1376) sample *= 2
        val src = ctx.contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Resim okunamadı")
        val out = Bitmap.createBitmap(2400, 1080, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); val p = Paint(Paint.FILTER_BITMAP_FLAG)
        val ar = src.width.toFloat() / src.height
        val dst = RectF(0f, 0f, 2400f, 1080f)
        if (abs(ar - 2400f / 1080f) < 0.08f) c.drawBitmap(src, null, dst, p)
        else {
            // Önce şablon oranına (1376:768) ortadan kes, sonra şablondaki kesim bölgesi
            val far = FW.toFloat() / FH
            val (cw, ch) = if (ar > far) (src.height * far) to src.height.toFloat() else src.width.toFloat() to (src.width / far)
            val ox = (src.width - cw) / 2; val oy = (src.height - ch) / 2
            val k = cw / FW
            c.drawBitmap(src, Rect((ox + CROP.left * k).toInt(), (oy + CROP.top * k).toInt(), (ox + CROP.right * k).toInt(), (oy + CROP.bottom * k).toInt()), dst, p)
        }
        src.recycle()
        return out
    }

    /** Tema seçicisi önizlemesi: sönük taban + %60 / %45 ışıklı dilim (make_theme.py thumb) */
    private fun thumb(d: Draft): Bitmap {
        val s = 480f / 2400
        val b = Bitmap.createBitmap(480, 216, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); val p = Paint(Paint.FILTER_BITMAP_FLAG); val dst = RectF(0f, 0f, 480f, 216f)
        c.drawBitmap(d.base, null, dst, p)
        val path = Path()
        for ((cc, fr) in listOf(DashImageGeom.SPEED_C to 0.6f, DashImageGeom.RPM_C to 0.45f)) {
            val o = RectF((cc[0] - BAND_OUT) * s, (cc[1] - BAND_OUT) * s, (cc[0] + BAND_OUT) * s, (cc[1] + BAND_OUT) * s)
            val i = RectF((cc[0] - BAND_IN) * s, (cc[1] - BAND_IN) * s, (cc[0] + BAND_IN) * s, (cc[1] + BAND_IN) * s)
            path.arcTo(o, 135f, 270f * fr, true); path.arcTo(i, 135f + 270f * fr, -270f * fr); path.close()
        }
        c.save(); c.clipPath(path); c.drawBitmap(d.lit, null, dst, p); c.restore()
        return b
    }

    private fun webp(b: Bitmap, f: File) = f.outputStream().use {
        @Suppress("DEPRECATION")
        b.compress(if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP, 85, it)
    }

    private fun luma(c: Int) = (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)) / 255

    private fun avg(px: IntArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int): Int {
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (y in y0 until y1 step 3) for (x in x0 until x1 step 3) { val p = px[y * w + x]; r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++ }
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }
}
