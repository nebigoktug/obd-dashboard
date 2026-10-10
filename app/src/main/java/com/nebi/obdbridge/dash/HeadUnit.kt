package com.nebi.obdbridge.dash

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.nebi.obdbridge.NaviListener
import com.nebi.obdbridge.NaviState

// Araç ekranının orta sütunu (dashboard/HEADUNIT.md): nav kartı, medya kartı, alt değer şeridi.
// Görsel temada referans resmin üstüne, resim koordinatlarında yerleşir (DashImageGeom).

/** Referans resmin dilinde ortak çizim: koyu cam kart, ince açık kenar, ince yazı. */
private object Glass {
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF10141A.toInt(); alpha = 205 }
    val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; alpha = 38 }
    val pressed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = 28 }
    const val TEXT = 0xFFF2F5F8.toInt()
    const val DIM = 0xFF9AA2A9.toInt()
    const val ICE = 0xFF7FB5DD.toInt()     // referansın buz mavisi
    const val AMBER = 0xFFE08B36.toInt()   // devir halkasının kehribar ucu

    fun card(c: Canvas, r: RectF, h: Float) {
        val rad = h * 0.09f
        c.drawRoundRect(r, rad, rad, fill)
        edge.strokeWidth = maxOf(1f, h * 0.004f)
        c.drawRoundRect(r, rad, rad, edge)
    }

    private val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)

    /** Görünüm başına rol adına göre önbellekli yazı fırçası (çizimde nesne oluşturmamak için). */
    fun text(cache: HashMap<String, Paint>, role: String, size: Float, color: Int = TEXT): Paint =
        cache.getOrPut(role) { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = light; fontFeatureSettings = "tnum" } }
            .apply { textSize = size; this.color = color; alpha = 255; textAlign = Paint.Align.LEFT; letterSpacing = 0f }

    fun ellipsize(s: String, p: Paint, w: Float): String {
        if (p.measureText(s) <= w) return s
        var t = s
        while (t.length > 1 && p.measureText("$t…") > w) t = t.dropLast(1)
        return "$t…"
    }
}

/** Basılan düğmenin kısa süre aydınlanması için ortak dokunma yardımcısı. */
abstract class TouchCard(ctx: Context) : View(ctx) {
    protected val pc = HashMap<String, Paint>()
    protected val hits = ArrayList<Pair<RectF, () -> Unit>>()
    private var down: RectF? = null

    @SuppressLint("ClickableViewAccessibility")  // kart içindeki bölgeler; erişilebilirlik için düğmeler sesli okunmuyor
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { down = hits.firstOrNull { it.first.contains(e.x, e.y) }?.first; invalidate() }
            MotionEvent.ACTION_UP -> {
                val d = down
                down = null
                hits.firstOrNull { it.first === d && d.contains(e.x, e.y) }?.second?.invoke()
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { down = null; invalidate() }
        }
        return true
    }

    protected fun drawPressed(c: Canvas, r: RectF, rad: Float) {
        if (down === r) c.drawRoundRect(r, rad, rad, Glass.pressed)
    }
}

// ---------------- Navigasyon kartı ----------------

/**
 * Rota varken: Yandex'in oku, mesafe, sokak (NaviListener). Dokununca Yandex açılır.
 * Rota yokken: Ev / İş hızlı hedefleri ve "Yandex".
 */
@SuppressLint("ViewConstructor")
class NaviCard(ctx: Context, private val prefs: android.content.SharedPreferences, private val onNeedPlace: (String) -> Unit) : TouchCard(ctx) {
    var state: NaviState? = null
        set(v) { if (field !== v) { field = v; invalidate() } }

    private val rect = RectF()
    private val bmpP = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
    }
    private val dst = RectF()
    private val btns = Array(3) { RectF() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        rect.set(1f, 1f, w - 1, h - 1)
        Glass.card(c, rect, h)
        hits.clear()
        val st = state
        if (st != null) drawRoute(c, st, w, h) else drawPlaces(c, w, h)
    }

    private fun drawRoute(c: Canvas, st: NaviState, w: Float, h: Float) {
        val pad = h * 0.12f
        var x = pad
        st.arrow?.let { b ->
            val side = h - 2 * pad
            val sc = side / maxOf(b.width, b.height)
            val bw = b.width * sc; val bh = b.height * sc
            dst.set(x, (h - bh) / 2, x + bw, (h + bh) / 2)
            c.drawBitmap(b, null, dst, bmpP)
            x += side + pad
        }
        val dist = Glass.text(pc, "r1", h * 0.30f)
        c.drawText(Glass.ellipsize(st.distance, dist, w - x - pad), x, h * 0.48f, dist)
        val street = Glass.text(pc, "r2", h * 0.16f, Glass.DIM)
        c.drawText(Glass.ellipsize(st.street, street, w - x - pad), x, h * 0.76f, street)
        hits += rect to ::openNavi
    }

    private fun drawPlaces(c: Canvas, w: Float, h: Float) {
        val pad = h * 0.1f
        val title = Glass.text(pc, "r3", h * 0.11f, Glass.DIM)
        title.letterSpacing = 0.12f
        c.drawText("NEREYE?", pad * 1.2f, pad + h * 0.11f, title)
        val top = pad + h * 0.2f
        val bw = (w - pad * 4) / 3
        val labels = arrayOf("Ev", "İş", "Yandex")
        val rad = h * 0.07f
        for (i in 0 until 3) {
            val r = btns[i]
            r.set(pad + i * (bw + pad), top, pad + i * (bw + pad) + bw, h - pad)
            drawPressed(c, r, rad)
            Glass.edge.strokeWidth = maxOf(1f, h * 0.005f)
            c.drawRoundRect(r, rad, rad, Glass.edge)
            val key = if (i == 0) "home" else "work"
            val set = i == 2 || prefs.getString("place_$key", null) != null
            drawPlaceIcon(c, i, r.centerX(), r.top + r.height() * 0.38f, r.height() * 0.26f, if (set) Glass.ICE else Glass.DIM)
            val lp = Glass.text(pc, "r4", r.height() * 0.17f, if (set) Glass.TEXT else Glass.DIM)
            lp.textAlign = Paint.Align.CENTER
            c.drawText(labels[i], r.centerX(), r.bottom - r.height() * 0.16f, lp)
            hits += r to {
                if (i == 2) openNavi()
                else prefs.getString("place_$key", null)?.let { routeTo(it) } ?: onNeedPlace(key)
            }
        }
    }

    private val iconP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val iconPath = Path()

    /** İnce çizgi simgeler: ev, çanta, ok (referansın termometre/akü simgeleriyle aynı dil). */
    private fun drawPlaceIcon(c: Canvas, i: Int, cx: Float, cy: Float, s: Float, color: Int) {
        iconP.color = color; iconP.strokeWidth = s * 0.08f
        iconPath.reset()
        when (i) {
            0 -> {
                iconPath.moveTo(cx - s * 0.55f, cy - s * 0.02f); iconPath.lineTo(cx, cy - s * 0.5f); iconPath.lineTo(cx + s * 0.55f, cy - s * 0.02f)
                iconPath.moveTo(cx - s * 0.4f, cy - s * 0.15f); iconPath.lineTo(cx - s * 0.4f, cy + s * 0.45f)
                iconPath.lineTo(cx + s * 0.4f, cy + s * 0.45f); iconPath.lineTo(cx + s * 0.4f, cy - s * 0.15f)
                iconPath.moveTo(cx - s * 0.1f, cy + s * 0.45f); iconPath.lineTo(cx - s * 0.1f, cy + s * 0.12f)
                iconPath.lineTo(cx + s * 0.1f, cy + s * 0.12f); iconPath.lineTo(cx + s * 0.1f, cy + s * 0.45f)
            }
            1 -> {
                iconPath.addRoundRect(cx - s * 0.55f, cy - s * 0.25f, cx + s * 0.55f, cy + s * 0.45f, s * 0.08f, s * 0.08f, Path.Direction.CW)
                iconPath.moveTo(cx - s * 0.2f, cy - s * 0.25f); iconPath.lineTo(cx - s * 0.2f, cy - s * 0.45f)
                iconPath.lineTo(cx + s * 0.2f, cy - s * 0.45f); iconPath.lineTo(cx + s * 0.2f, cy - s * 0.25f)
                iconPath.moveTo(cx - s * 0.55f, cy + s * 0.05f); iconPath.lineTo(cx + s * 0.55f, cy + s * 0.05f)
            }
            else -> {
                // Navigasyon oku (üçgen imleç)
                iconPath.moveTo(cx, cy - s * 0.5f); iconPath.lineTo(cx + s * 0.4f, cy + s * 0.45f)
                iconPath.lineTo(cx, cy + s * 0.22f); iconPath.lineTo(cx - s * 0.4f, cy + s * 0.45f); iconPath.close()
            }
        }
        c.drawPath(iconPath, iconP)
    }

    private fun openNavi() {
        val pm = context.packageManager
        val pkg = listOf(YANDEX_NAVI, "ru.yandex.yandexmaps").firstOrNull { pm.getLaunchIntentForPackage(it) != null } ?: return
        context.startActivity(pm.getLaunchIntentForPackage(pkg)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** "enlem,boylam" → Yandex Navigator'da rota. */
    private fun routeTo(latLon: String) {
        val (lat, lon) = latLon.split(',').map { it.trim() }.takeIf { it.size == 2 } ?: return
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("yandexnavi://build_route_on_map?lat_to=$lat&lon_to=$lon"))
            .setPackage(YANDEX_NAVI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(i) } catch (_: Exception) { openNavi() }
    }

    companion object {
        const val YANDEX_NAVI = "ru.yandex.yandexnavi"

        /** "41.0123, 28.9876" ya da Google/Yandex harita bağlantısından enlem,boylam çıkarır. */
        fun parsePlace(s: String): String? {
            val m = Regex("(-?\\d{1,2}\\.\\d+)\\s*[, ]\\s*(-?\\d{1,3}\\.\\d+)").find(s) ?: return null
            val lat = m.groupValues[1].toDouble(); val lon = m.groupValues[2].toDouble()
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            return "$lat,$lon"
        }
    }
}

// ---------------- Medya kartı ----------------

/**
 * Çalan uygulamayı (Spotify, YT Music, radyo…) kumanda eder. Oturumlar bildirim dinleyicisi izniyle okunur
 * (NaviListener için verilen izin yeterli). Çalan yoksa son kullanılan uygulama gösterilir, ⏯ onu açar.
 */
class MediaHub(private val ctx: Context) {
    var controller: MediaController? = null; private set
    var onChange: (() -> Unit)? = null
    private val msm = ctx.getSystemService(MediaSessionManager::class.java)
    private val listener = MediaSessionManager.OnActiveSessionsChangedListener { pick(it) }
    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { onChange?.invoke() }
        override fun onPlaybackStateChanged(state: PlaybackState?) { onChange?.invoke() }
        override fun onSessionDestroyed() { pick(sessions()) }
    }
    private val prefs = ctx.getSharedPreferences("dash", Context.MODE_PRIVATE)

    private fun component() = ComponentName(ctx, NaviListener::class.java)

    private fun sessions(): List<MediaController>? =
        try { msm.getActiveSessions(component()) } catch (_: SecurityException) { null }

    fun start() {
        try { msm.addOnActiveSessionsChangedListener(listener, component()) } catch (_: SecurityException) {}
        pick(sessions())
    }

    fun stop() {
        try { msm.removeOnActiveSessionsChangedListener(listener) } catch (_: Exception) {}
        controller?.unregisterCallback(cb)
    }

    /** Çalan oturum öncelikli; yoksa en üstteki (sistem sırası = en son kullanılan). */
    private fun pick(list: List<MediaController>?) {
        val best = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list?.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(cb)
            controller = best
            best?.registerCallback(cb)
            best?.packageName?.let { prefs.edit().putString("last_media_pkg", it).apply() }
        }
        onChange?.invoke()
    }

    val hasAccess get() = sessions() != null
    val lastPackage: String? get() = prefs.getString("last_media_pkg", null)

    fun playPause() {
        val c = controller
        if (c == null) { openApp(); return }
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }
    fun next() = controller?.transportControls?.skipToNext()
    fun prev() = controller?.transportControls?.skipToPrevious()

    fun openApp() {
        val c = controller
        try {
            c?.sessionActivity?.send() ?: run {
                val pkg = c?.packageName ?: lastPackage ?: return
                ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        } catch (_: Exception) {}
    }

    fun appLabel(pkg: String?): String = try {
        pkg?.let { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(it, 0)).toString() } ?: ""
    } catch (_: Exception) { "" }
}

@SuppressLint("ViewConstructor")  // yalnız koddan oluşturuluyor
class MediaCard(ctx: Context, private val hub: MediaHub) : TouchCard(ctx) {
    private val rect = RectF()
    private val art = RectF()
    private val artP = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val artM = Matrix()
    private var artBmp: Bitmap? = null
    private val btn = Array(3) { RectF() }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val iconP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Glass.TEXT }
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        rect.set(1f, 1f, w - 1, h - 1)
        Glass.card(c, rect, h)
        hits.clear()
        val pad = h * 0.1f
        val ctl = hub.controller
        val md = ctl?.metadata
        val ps = ctl?.playbackState
        val playing = ps?.state == PlaybackState.STATE_PLAYING

        // Kapak (yoksa nota simgeli boş kare)
        val side = h * 0.52f
        art.set(pad, pad, pad + side, pad + side)
        val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val rad = side * 0.1f
        if (bmp != null) {
            if (bmp !== artBmp) { artBmp = bmp; artP.shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            val sc = maxOf(side / bmp.width, side / bmp.height)
            artM.setScale(sc, sc); artM.postTranslate(art.left + (side - bmp.width * sc) / 2, art.top + (side - bmp.height * sc) / 2)
            artP.shader.setLocalMatrix(artM)
            c.drawRoundRect(art, rad, rad, artP)
        } else {
            c.drawRoundRect(art, rad, rad, Glass.pressed)
            val np = Glass.text(pc, "r5", side * 0.45f, Glass.DIM); np.textAlign = Paint.Align.CENTER
            c.drawText("♪", art.centerX(), art.centerY() + side * 0.16f, np)
        }
        hits += art to { hub.openApp() }

        // Başlık, sanatçı, kaynak uygulama
        val tx = art.right + pad
        val tw = w - tx - pad
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: if (!hub.hasAccess) "Bildirim erişimi gerekli" else if (hub.lastPackage != null) "Çalan yok" else "Müzik açılmadı"
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val tp = Glass.text(pc, "r6", h * 0.15f)
        c.drawText(Glass.ellipsize(title, tp, tw), tx, art.top + h * 0.17f, tp)
        val ap = Glass.text(pc, "r7", h * 0.11f, Glass.DIM)
        c.drawText(Glass.ellipsize(artist, ap, tw), tx, art.top + h * 0.33f, ap)
        val src = hub.appLabel(ctl?.packageName ?: hub.lastPackage)
        if (src.isNotEmpty()) {
            val sp = Glass.text(pc, "r8", h * 0.08f, Glass.ICE); sp.letterSpacing = 0.1f
            c.drawText(Glass.ellipsize(src.uppercase(), sp, tw), tx, art.bottom - h * 0.02f, sp)
        }

        // İlerleme çubuğu
        val dur = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val by = art.bottom + pad * 0.9f
        bar.strokeWidth = h * 0.018f
        bar.color = Color.WHITE; bar.alpha = 40
        c.drawLine(pad, by, w - pad, by, bar)
        if (ps != null && dur > 0) {
            var pos = ps.position
            if (playing) pos += ((SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime) * ps.playbackSpeed).toLong()
            val f = (pos.toFloat() / dur).coerceIn(0f, 1f)
            bar.color = Glass.TEXT; bar.alpha = 230
            c.drawLine(pad, by, pad + (w - 2 * pad) * f, by, bar)
        }

        // Düğmeler: ⏮ ⏯ ⏭ (eşit genişlikte büyük dokunma alanları)
        val top = by + pad * 0.5f
        val bw = (w - 2 * pad) / 3
        for (i in 0 until 3) {
            val r = btn[i]
            r.set(pad + i * bw, top, pad + (i + 1) * bw, h - pad * 0.4f)
            drawPressed(c, r, r.height() * 0.2f)
            val cx = r.centerX(); val cy = r.centerY(); val s = r.height() * 0.32f
            when (i) {
                0 -> { triangle(c, cx + s * 0.15f, cy, s, left = true); c.drawRect(cx - s * 0.95f, cy - s * 0.7f, cx - s * 0.75f, cy + s * 0.7f, iconP) }
                1 -> if (playing) {
                    c.drawRect(cx - s * 0.6f, cy - s * 0.75f, cx - s * 0.2f, cy + s * 0.75f, iconP)
                    c.drawRect(cx + s * 0.2f, cy - s * 0.75f, cx + s * 0.6f, cy + s * 0.75f, iconP)
                } else triangle(c, cx + s * 0.2f, cy, s * 1.1f, left = false)
                2 -> { triangle(c, cx - s * 0.15f, cy, s, left = false); c.drawRect(cx + s * 0.75f, cy - s * 0.7f, cx + s * 0.95f, cy + s * 0.7f, iconP) }
            }
            hits += r to when (i) { 0 -> { -> hub.prev(); Unit }; 1 -> { -> hub.playPause() }; else -> { -> hub.next(); Unit } }
        }
        if (playing) postInvalidateDelayed(500)   // ilerleme çubuğu
    }

    /** Ucu [x]'te olan dolu üçgen. */
    private fun triangle(c: Canvas, x: Float, cy: Float, s: Float, left: Boolean) {
        path.reset()
        val d = if (left) 1 else -1
        path.moveTo(x - d * s * 0.8f, cy); path.lineTo(x + d * s * 0.4f, cy - s * 0.75f); path.lineTo(x + d * s * 0.4f, cy + s * 0.75f); path.close()
        c.drawPath(path, iconP)
    }
}

// ---------------- Alt değer şeridi ----------------

/** Referanstaki dört kutucuğun küçülmüş hali: yol çiziminin iki yanında ikişer değer. */
@SuppressLint("ViewConstructor")  // yalnız koddan oluşturuluyor
class ValueStrip(ctx: Context, private val specs: List<WidgetSpec>) : DashWidget(ctx, specs.first()) {
    private val pc = HashMap<String, Paint>()
    override fun keys() = specs.map { it.signal }
    private val gapFrac = 0.16f   // ortadaki araç çizimi için boşluk

    companion object {
        /** Şeritte dar yer: kısa etiketler */
        val SHORT = mapOf("boost" to "TURBO", "load" to "YÜK", "battery" to "AKÜ", "coolant" to "MOTOR", "iat" to "EMME",
            "rail" to "RAİL", "map" to "MAP", "speed" to "HIZ", "rpm" to "DEVİR", "dtc_count" to "ARIZA",
            "fuel_lph" to "L/SA", "fuel_l100" to "TÜKETİM", "fuel_avg" to "ORT. TÜK.")
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val side = w * (1 - gapFrac) / 2
        val slot = side / 2
        val lp = Glass.text(pc, "r9", h * 0.22f, Glass.DIM); lp.letterSpacing = 0.1f; lp.textAlign = Paint.Align.CENTER
        val vp = Glass.text(pc, "r10", h * 0.44f); vp.textAlign = Paint.Align.CENTER
        val up = Glass.text(pc, "r11", h * 0.22f, Glass.DIM)
        specs.take(4).forEachIndexed { i, spec ->
            val cx = if (i < 2) slot * (i + 0.5f) else w - side + slot * (i - 2 + 0.5f)
            val d = frame[spec.signal]
            val def = SignalCatalog[spec.signal] ?: return@forEachIndexed
            c.drawText(SHORT[spec.signal] ?: def.label.uppercase(), cx, h * 0.3f, lp)
            val txt = d?.text ?: "—"
            vp.color = if (d != null && d.level != Level.NORMAL && !d.missing) theme.levelColor(d.level) else Glass.TEXT
            vp.alpha = alphaFor(d)
            val unit = if (d == null || d.missing) "" else def.unit
            val tw = vp.measureText(txt); val uw = if (unit.isEmpty()) 0f else up.measureText(" $unit")
            val x0 = cx - (tw + uw) / 2
            vp.textAlign = Paint.Align.LEFT
            c.drawText(txt, x0, h * 0.82f, vp)
            vp.textAlign = Paint.Align.CENTER
            if (unit.isNotEmpty()) c.drawText(" $unit", x0 + tw, h * 0.82f, up)
        }
    }
}

// ---------------- Hava durumu ----------------

/** Open-Meteo (anahtarsız): son bilinen konumla 30 dk'da bir anlık sıcaklık. */
class Weather(private val ctx: Context) {
    @Volatile var tempC: Double? = null; private set
    @Volatile private var fetchedAt = 0L
    @Volatile private var busy = false

    fun refreshIfStale() {
        val now = System.currentTimeMillis()
        if (busy || now - fetchedAt < (if (tempC == null) 60_000 else 30 * 60_000)) return
        val loc = lastLocation() ?: return
        busy = true
        fetchedAt = now
        Thread({
            try {
                val url = java.net.URL("https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m"
                    .format(java.util.Locale.US, loc.latitude, loc.longitude))
                val con = (url.openConnection() as java.net.HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
                val body = con.inputStream.bufferedReader().use { it.readText() }
                tempC = org.json.JSONObject(body).getJSONObject("current").getDouble("temperature_2m")
            } catch (e: Exception) {
                android.util.Log.w("ObdWeather", "hava alınamadı: ${e.message}")
                fetchedAt = System.currentTimeMillis() - 29 * 60_000   // 1 dk sonra tekrar dene
            } finally { busy = false }
        }, "weather").start()
    }

    private fun lastLocation(): android.location.Location? {
        if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        return try {
            listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER,
                android.location.LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        } catch (_: SecurityException) { null }
    }
}

// ---------------- Durum hapı (VOLVO yazısının yerinde) ----------------

/** En önemli uyarı + kaç tane daha olduğu. Uyarı yokken görünmez, referanstaki VOLVO yazısı kalır. */
class StatusPill(ctx: Context) : View(ctx) {
    var alerts: List<Alert> = emptyList()
        set(v) { if (field != v) { field = v; visibility = if (v.isEmpty()) INVISIBLE else VISIBLE; invalidate() } }
    private val pc = HashMap<String, Paint>()
    private val rect = RectF()
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF12161C.toInt() }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val a = alerts.firstOrNull() ?: return
        val w = width.toFloat(); val h = height.toFloat()
        val color = when (a.level) { Level.CRIT -> 0xFFE0603A.toInt(); Level.WARN -> Glass.AMBER; Level.COLD -> Glass.ICE; Level.NORMAL -> Glass.TEXT }
        val tp = Glass.text(pc, "t", h * 0.4f)
        val more = if (alerts.size > 1) "  +${alerts.size - 1}" else ""
        val mp = Glass.text(pc, "m", h * 0.34f, Glass.DIM)
        val text = Glass.ellipsize(a.text, tp, w - h * 1.6f - mp.measureText(more))
        val tw = h * 0.9f + tp.measureText(text) + mp.measureText(more) + h * 0.7f
        rect.set((w - tw) / 2, 1f, (w + tw) / 2, h - 1)
        c.drawRoundRect(rect, h / 2, h / 2, bg)
        Glass.edge.strokeWidth = maxOf(1f, h * 0.025f)
        Glass.edge.color = color; Glass.edge.alpha = 140
        c.drawRoundRect(rect, h / 2, h / 2, Glass.edge)
        Glass.edge.color = Color.WHITE; Glass.edge.alpha = 38
        dot.color = color
        c.drawCircle(rect.left + h * 0.5f, h / 2, h * 0.13f, dot)
        val x = rect.left + h * 0.85f
        c.drawText(text, x, h * 0.64f, tp)
        if (more.isNotEmpty()) c.drawText(more, x + tp.measureText(text), h * 0.64f, mp)
    }
}

// ---------------- Detay sekmesi bilgi kartları ----------------

/** Başlık + "etiket: değer" satırları; içerik [rows] ile her saniye yeniden okunur. */
@SuppressLint("ViewConstructor")  // yalnız koddan oluşturuluyor
class InfoCard(ctx: Context, private val title: String, private val rows: () -> List<Pair<String, String>>) : View(ctx) {
    private val pc = HashMap<String, Paint>()
    private val rect = RectF()
    private val dp = ctx.resources.displayMetrics.density

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        rect.set(1f, 1f, w - 1, h - 1)
        Glass.card(c, rect, 300 * dp)
        val pad = 14 * dp
        val tp = Glass.text(pc, "t", 12 * dp, Glass.ICE); tp.letterSpacing = 0.15f
        c.drawText(title, pad, pad + 12 * dp, tp)
        val lp = Glass.text(pc, "l", 13 * dp, Glass.DIM)
        val vp = Glass.text(pc, "v", 16 * dp); vp.textAlign = Paint.Align.RIGHT
        var y = pad + 40 * dp
        for ((k, v) in rows()) {
            if (y > h - 6 * dp) break
            c.drawText(k, pad, y, lp)
            c.drawText(Glass.ellipsize(v, vp, w - 2 * pad - lp.measureText(k) - 8 * dp), w - pad, y, vp)
            y += 26 * dp
        }
        postInvalidateDelayed(1000)
    }
}

// ---------------- Geçmiş sekmesi ----------------

/** Geçmiş listesinde bir yolculuk satırı: tarih/saat, süre, km, tüketim, puan. */
@SuppressLint("ViewConstructor")  // yalnız koddan oluşturuluyor
class TripRow(ctx: Context, private val t: TripSummary) : View(ctx) {
    private val pc = HashMap<String, Paint>()
    private val rect = RectF()
    private val dp = ctx.resources.displayMetrics.density
    private val day = java.text.SimpleDateFormat("d MMM EEE", java.util.Locale("tr"))
    private val hour = java.text.SimpleDateFormat("HH:mm", java.util.Locale("tr"))
    private val dayText = day.format(java.util.Date(t.start))
    private val hourText = "${hour.format(java.util.Date(t.start))}–${hour.format(java.util.Date(t.start + t.durationMs))}"
    private val cols = listOf(
        "%.1f km".format(t.km) to "mesafe",
        (t.durationMs / 60_000).let { if (it >= 60) "${it / 60} sa ${it % 60} dk" else "$it dk" } to "süre",
        (t.l100?.let { "%.1f".format(it) } ?: "—") to "L/100 (tahmini)",
        "${t.score}" to "puan",
    )

    init { isClickable = true }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        rect.set(1f, 1f, w - 1, h - 1)
        Glass.card(c, rect, 200 * dp)
        if (isPressed) c.drawRoundRect(rect, 18 * dp, 18 * dp, Glass.pressed)
        val pad = 14 * dp
        val dp1 = Glass.text(pc, "d", 15 * dp)
        c.drawText(dayText, pad, h * 0.45f, dp1)
        val tp = Glass.text(pc, "t", 12 * dp, Glass.DIM)
        c.drawText(hourText, pad, h * 0.78f, tp)
        val x0 = w * 0.3f
        val slot = (w - x0 - pad) / cols.size
        cols.forEachIndexed { i, (v, k) ->
            val x = x0 + i * slot
            val vp = Glass.text(pc, "v", 17 * dp, if (i == 3) scoreColor(t.score) else Glass.TEXT)
            c.drawText(v, x, h * 0.48f, vp)
            c.drawText(k, x, h * 0.78f, Glass.text(pc, "k", 11 * dp, Glass.DIM))
        }
    }

    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }

    private fun scoreColor(s: Int) = when { s >= 85 -> Glass.ICE; s >= 65 -> Glass.TEXT; else -> Glass.AMBER }
}
