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
import com.nebi.obdbridge.R

// Araç ekranının orta sütunu (dashboard/HEADUNIT.md): nav kartı, medya kartı, alt değer şeridi.
// Görsel temada referans resmin üstüne, resim koordinatlarında yerleşir (DashImageGeom).

/** Referans resmin dilinde ortak çizim: koyu cam kart, ince açık kenar, ince yazı. */
internal object Glass {
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF10141A.toInt(); alpha = 205 }
    val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; alpha = 38 }
    val pressed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = 28 }
    var TEXT = 0xFFF2F5F8.toInt()
    var DIM = 0xFF9AA2A9.toInt()
    var ICE = 0xFF7FB5DD.toInt()     // temanın vurgu rengi (referansta buz mavisi)
    const val AMBER = 0xFFE08B36.toInt()   // devir halkasının kehribar ucu
    /** Kenar çizgisinin rengi (koyu temada beyaz, açık temada siyah); [edge] her kullanımdan sonra buna döner */
    var EDGE = Color.WHITE

    /**
     * Zemin üstündeki yazının rengi (kart dışındaki saat, hava, sekmeler, alt şerit). Temalar arasında zemin
     * çok farklı (mermer, tuğla, gökyüzü): yazının altındaki bölgenin parlaklığı tema resminden ölçülür,
     * açık zeminde koyu yazı + beyaz hâle, koyu zeminde açık yazı + siyah hâle.
     */
    class Ink(val text: Int, val dim: Int, val halo: Int)
    private val DARK_INK = Ink(0xFF15181C.toInt(), 0xFF3A4048.toInt(), 0xF0FFFFFF.toInt())
    private val LIGHT_INK = Ink(0xFFF2F5F8.toInt(), 0xFFC4CAD0.toInt(), 0xF5000000.toInt())
    private var luma: Bitmap? = null
    private val inks = HashMap<RectF, Ink>()

    /** [r] resim koordinatında (2400×1080, DashImageGeom); resim yoksa koyu zemin varsayılır */
    fun ink(r: RectF): Ink = inks.getOrPut(r) {
        val b = luma ?: return@getOrPut LIGHT_INK
        val sx = b.width / DashImageGeom.W; val sy = b.height / DashImageGeom.H
        var sum = 0.0; var n = 0
        for (y in (r.top * sy).toInt().coerceIn(0, b.height - 1)..(r.bottom * sy).toInt().coerceIn(0, b.height - 1))
            for (x in (r.left * sx).toInt().coerceIn(0, b.width - 1)..(r.right * sx).toInt().coerceIn(0, b.width - 1)) {
                val c = b.getPixel(x, y)
                sum += 0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c); n++
            }
        if (n > 0 && sum / n / 255 > 0.55) DARK_INK else LIGHT_INK
    }

    /** Yazıya zemin karşıtı hâle (yoğun desenli zeminde de okunsun) */
    fun halo(p: Paint, ink: Ink) = p.setShadowLayer(p.textSize * 0.22f, 0f, 0f, ink.halo)

    /** Temaya göre kart rengi: koyu cam ya da (gündüz) açık cam */
    fun use(t: ImageTheme, res: android.content.res.Resources) {
        inks.clear()
        luma = try {
            android.graphics.BitmapFactory.decodeResource(res, t.base, android.graphics.BitmapFactory.Options().apply { inSampleSize = 16 })
        } catch (_: Exception) { null }
        val l = t.light
        fill.color = if (l) 0xFFFFFFFF.toInt() else 0xFF10141A.toInt(); fill.alpha = if (l) 150 else 140   // yarı şeffaf: tema resmi arkadan görünsün
        EDGE = if (l) Color.BLACK else Color.WHITE
        edge.color = EDGE; edge.alpha = if (l) 30 else 38
        pressed.color = EDGE; pressed.alpha = if (l) 22 else 28
        TEXT = if (l) 0xFF15181C.toInt() else 0xFFF2F5F8.toInt()
        DIM = if (l) 0xFF5F6670.toInt() else 0xFF9AA2A9.toInt()
        ICE = t.accent
    }

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
 * Rota varken: sıradaki manevranın oku, mesafe, sokak, varış saati (kendi navigasyonumuz, [Nav]). Dokununca [onRouteTap].
 * Rota yokken: Ev / İş hızlı hedefleri ve "Ara" (hedef arama). Ev/İş girilmemişse dokununca arama açılır.
 */
@SuppressLint("ViewConstructor")
class NaviCard(ctx: Context, private val prefs: android.content.SharedPreferences, private val onRouteTap: () -> Unit) : TouchCard(ctx) {
    var state: NaviState? = null
        set(v) { if (field !== v) { field = v; invalidate() } }

    private val rect = RectF()
    private val bmpP = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var tint = 0
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
            if (tint != Glass.TEXT) { tint = Glass.TEXT; bmpP.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN) }
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
        st.eta?.let {
            val eta = Glass.text(pc, "r5", h * 0.12f, Glass.DIM)
            eta.textAlign = Paint.Align.RIGHT
            c.drawText(it, w - pad, pad + h * 0.1f, eta)
        }
        hits += rect to onRouteTap
    }

    private fun drawPlaces(c: Canvas, w: Float, h: Float) {
        val pad = h * 0.1f
        val title = Glass.text(pc, "r3", h * 0.11f, Glass.DIM)
        title.letterSpacing = 0.12f
        c.drawText("NEREYE?", pad * 1.2f, pad + h * 0.11f, title)
        val top = pad + h * 0.2f
        val bw = (w - pad * 4) / 3
        val labels = arrayOf("Ev", "İş", "Ara")
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
                val ll = prefs.getString("place_$key", null)?.split(',')?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 2 }
                when {
                    i == 2 -> PlaceSearchActivity.open(context, PlaceSearchActivity.MODE_NAV)
                    ll == null -> PlaceSearchActivity.open(context, key)
                    !YandexNav.available(context) -> PlaceSearchActivity.open(context, key)   // uyarıyı o gösterir
                    else -> Nav.start(context, ll[0], ll[1], prefs.getString("place_${key}_name", null) ?: labels[i])
                }
            }
        }
    }

    private val iconP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val iconPath = Path()

    /** İnce çizgi simgeler: ev, çanta, büyüteç (referansın termometre/akü simgeleriyle aynı dil). */
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
                // Büyüteç
                iconPath.addCircle(cx - s * 0.1f, cy - s * 0.1f, s * 0.32f, Path.Direction.CW)
                iconPath.moveTo(cx + s * 0.13f, cy + s * 0.13f); iconPath.lineTo(cx + s * 0.48f, cy + s * 0.48f)
            }
        }
        c.drawPath(iconPath, iconP)
    }
}

// ---------------- Medya kartı ----------------

/**
 * Çalan uygulamayı (YT Music, Spotify, radyo…) gösterir ve kumanda eder. İzin istemeden, sırayla:
 *  1. Uygulamanın medya tarayıcı servisine bağlanır (Android Auto / saatler için açık kapı): kabul eden
 *     uygulamada başlık, kapak, ilerleme ve kontroller gelir. Kabul edip etmemek o uygulamanın kararı.
 *  2. Spotify ("Device Broadcast Status" açıksa) ve eski usul çalarların (com.android.music.*) yayınları: başlık, sanatçı.
 *  3. Hiçbiri yoksa: medya tuşları (her uygulamada çalışır) ve AudioManager'dan "çalıyor mu".
 * İsteyen kullanıcı bildirim erişimi verirse (Ayarlar, isteğe bağlı) bütün uygulamaların oturumları okunur.
 */
class MediaHub(private val ctx: Context) {
    /** Kartın gösterdiği: kaynak ne olursa olsun aynı biçim */
    data class Now(val title: String?, val artist: String?, val art: Bitmap?, val playing: Boolean,
                   val durMs: Long, val state: PlaybackState?, val pkg: String?)

    var controller: MediaController? = null; private set
    var onChange: (() -> Unit)? = null
    private val msm = ctx.getSystemService(MediaSessionManager::class.java)
    private val audio = ctx.getSystemService(android.media.AudioManager::class.java)
    private val listener = MediaSessionManager.OnActiveSessionsChangedListener { pick(it) }
    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { onChange?.invoke() }
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            if (!hasAccess) pickBrowsed()
            onChange?.invoke()
        }
        override fun onSessionDestroyed() { if (hasAccess) pick(sessions()) else pickBrowsed() }
    }
    private val prefs = ctx.getSharedPreferences("dash", Context.MODE_PRIVATE)

    private fun component() = ComponentName(ctx, NaviListener::class.java)

    private fun sessions(): List<MediaController>? =
        try { msm.getActiveSessions(component()) } catch (_: SecurityException) { null }

    fun start() {
        try { msm.addOnActiveSessionsChangedListener(listener, component()) } catch (_: SecurityException) {}
        val s = sessions()
        if (s != null) pick(s) else connectBrowsers()
        registerBroadcasts()
    }

    fun stop() {
        try { msm.removeOnActiveSessionsChangedListener(listener) } catch (_: Exception) {}
        controller?.unregisterCallback(cb); controller = null
        browsers.values.forEach { b -> runCatching { b.second?.unregisterCallback(cb); b.first.disconnect() } }
        browsers.clear()
        runCatching { ctx.unregisterReceiver(bcastReceiver) }
    }

    /** Çalan oturum öncelikli; yoksa en üstteki (sistem sırası = en son kullanılan). */
    private fun pick(list: List<MediaController>?) {
        val best = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list?.firstOrNull()
        use(best)
    }

    private fun use(best: MediaController?) {
        if (best?.sessionToken != controller?.sessionToken) {
            if (hasAccess) controller?.unregisterCallback(cb)
            controller = best
            if (hasAccess) best?.registerCallback(cb)
            best?.packageName?.let { prefs.edit().putString("last_media_pkg", it).apply() }
        }
        onChange?.invoke()
    }

    val hasAccess get() = sessions() != null
    val lastPackage: String? get() = prefs.getString("last_media_pkg", null)

    // ---------- 1. Medya tarayıcı servisleri ----------

    /** paket → (tarayıcı, bağlanınca denetleyici) */
    private val browsers = HashMap<String, Pair<android.media.browse.MediaBrowser, MediaController?>>()

    /** Kurulu yaygın çalarlar + son kullanılan; hepsine bağlanmak bütün uygulamaları uyandırırdı */
    private fun connectBrowsers() {
        val pm = ctx.packageManager
        val services = try {
            pm.queryIntentServices(Intent("android.media.browse.MediaBrowserService"), 0)
        } catch (_: Exception) { emptyList() }
        val last = lastPackage
        val wanted = services.filter { it.serviceInfo.packageName in KNOWN_PLAYERS || it.serviceInfo.packageName == last }
            .distinctBy { it.serviceInfo.packageName }
        for (ri in wanted) {
            val si = ri.serviceInfo
            if (browsers.containsKey(si.packageName)) continue
            lateinit var b: android.media.browse.MediaBrowser
            b = android.media.browse.MediaBrowser(ctx, ComponentName(si.packageName, si.name), object : android.media.browse.MediaBrowser.ConnectionCallback() {
                override fun onConnected() {
                    android.util.Log.i(TAG, "medya tarayıcısı kabul etti: ${si.packageName}")
                    val c = runCatching { MediaController(ctx, b.sessionToken) }.getOrNull() ?: return
                    c.registerCallback(cb)
                    browsers[si.packageName] = b to c
                    pickBrowsed()
                }
                override fun onConnectionFailed() { android.util.Log.i(TAG, "medya tarayıcısı reddetti: ${si.packageName}"); browsers.remove(si.packageName) }
                override fun onConnectionSuspended() { browsers[si.packageName]?.second?.unregisterCallback(cb); browsers.remove(si.packageName) }
            }, null)
            browsers[si.packageName] = b to null
            runCatching { b.connect() }.onFailure { browsers.remove(si.packageName) }
        }
    }

    /** Bağlı tarayıcılardan çalanı, yoksa son kullanılanı seç (bildirim erişimi yokken) */
    private fun pickBrowsed() {
        if (hasAccess) return
        val cs = browsers.values.mapNotNull { it.second }
        val best = cs.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: cs.firstOrNull { it.packageName == controller?.packageName && it.metadata != null }
            ?: cs.firstOrNull { it.packageName == lastPackage && it.metadata != null }
            ?: cs.firstOrNull { it.metadata != null }
        if (best?.sessionToken != controller?.sessionToken) {
            controller = best
            best?.packageName?.let { prefs.edit().putString("last_media_pkg", it).apply() }
        }
        onChange?.invoke()
    }

    // ---------- 2. Yayınlar ----------

    private var bcast: Now? = null
    private var bcastAt = 0L
    private val bcastReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            i ?: return
            val spotify = i.action?.startsWith("com.spotify.music.") == true
            val pkg = if (spotify) SPOTIFY else bcast?.pkg
            val track = i.getStringExtra("track")
            val artist = i.getStringExtra("artist")
            val playing = if (i.hasExtra("playing")) i.getBooleanExtra("playing", false) else bcast?.playing ?: true
            bcast = Now(track ?: bcast?.title, artist ?: bcast?.artist, null, playing,
                if (spotify) i.getIntExtra("length", 0).toLong() else 0L, null, pkg)
            bcastAt = System.currentTimeMillis()
            if (pkg != null) prefs.edit().putString("last_media_pkg", pkg).apply()
            onChange?.invoke()
        }
    }

    private fun registerBroadcasts() {
        val f = android.content.IntentFilter().apply {
            for (a in listOf("com.spotify.music.metadatachanged", "com.spotify.music.playbackstatechanged",
                "com.android.music.metachanged", "com.android.music.playstatechanged")) addAction(a)
        }
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(bcastReceiver, f, Context.RECEIVER_EXPORTED)
            else ctx.registerReceiver(bcastReceiver, f)
        }
    }

    // ---------- Kartın okuduğu ----------

    fun now(): Now {
        controller?.let { c ->
            val md = c.metadata
            val ps = c.playbackState
            return Now(md?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() },
                (md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))?.takeIf { it.isNotBlank() },
                md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
                ps?.state == PlaybackState.STATE_PLAYING, md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L, ps, c.packageName)
        }
        val active = runCatching { audio.isMusicActive }.getOrDefault(false)
        // Yayın bilgisi bayatlamadıysa (çalarken yayın tekrarlanmayabilir: ses çalıyorsa geçerli say)
        bcast?.takeIf { active || System.currentTimeMillis() - bcastAt < 10 * 60_000 }?.let { return it.copy(playing = active || it.playing && System.currentTimeMillis() - bcastAt < 5_000) }
        return Now(null, null, null, active, 0L, null, null)
    }

    fun playPause() {
        val c = controller
        when {
            c != null -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
            now().playing || lastPackage != null -> key(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            else -> openApp()
        }
    }
    fun next() { controller?.transportControls?.skipToNext() ?: key(android.view.KeyEvent.KEYCODE_MEDIA_NEXT) }
    fun prev() { controller?.transportControls?.skipToPrevious() ?: key(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS) }

    /** Medya tuşu (kulaklık/direksiyon tuşu gibi): izin gerektirmez, son çalan uygulamaya gider */
    private fun key(code: Int) {
        runCatching {
            audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
            audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ onChange?.invoke() }, 600)
    }

    fun openApp() {
        val c = controller
        try {
            c?.sessionActivity?.send() ?: run {
                val pkg = c?.packageName ?: bcast?.pkg ?: lastPackage ?: return
                ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        } catch (_: Exception) {}
    }

    fun appLabel(pkg: String?): String = try {
        pkg?.let { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(it, 0)).toString() } ?: ""
    } catch (_: Exception) { "" }

    companion object {
        const val TAG = "MediaHub"
        const val SPOTIFY = "com.spotify.music"
        /** Medya tarayıcısına bağlanılan yaygın çalarlar (Türkiye'de kullanılanlar dahil) */
        val KNOWN_PLAYERS = setOf(
            "com.google.android.apps.youtube.music", "com.google.android.youtube", SPOTIFY, "com.apple.android.music", "deezer.android.app",
            "com.aspiro.tidal", "com.amazon.mp3", "com.soundcloud.android", "com.turkcell.gncplay", "com.turkcell.fizy",
            "com.miui.player", "com.sec.android.app.music", "com.maxmpz.audioplayer", "org.videolan.vlc",
            "com.google.android.apps.podcasts", "au.com.shiftyjelly.pocketcasts", "de.danoeh.antennapod",
            "fm.castbox.audiobook.radio.podcast", "com.audible.application", "tunein.player", "com.radio.pocketfm",
            "com.kodarkooperativet.blackplayerex", "com.musicolet", "in.krosbits.musicolet", "com.shazam.android",
        )
    }
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
        iconP.color = Glass.TEXT
        val now = hub.now()
        val ps = now.state
        val playing = now.playing

        // Kapak (yoksa nota simgeli boş kare)
        val side = h * 0.52f
        art.set(pad, pad, pad + side, pad + side)
        val bmp = now.art
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

        // Başlık, sanatçı, kaynak uygulama (şarkı adını vermeyen uygulamada yalnız çalıyor/durdu)
        val tx = art.right + pad
        val tw = w - tx - pad
        val src = hub.appLabel(now.pkg ?: hub.lastPackage)
        val title = now.title ?: when { playing -> "Çalıyor"; hub.lastPackage != null -> "Çalan yok"; else -> "Müzik" }
        val artist = now.artist ?: when { now.title != null -> ""; playing -> "Şarkı adı için: Ayarlar › Medya"; else -> "▶ ile başlat" }
        val tp = Glass.text(pc, "r6", h * 0.15f)
        c.drawText(Glass.ellipsize(title, tp, tw), tx, art.top + h * 0.17f, tp)
        val ap = Glass.text(pc, "r7", h * 0.11f, Glass.DIM)
        c.drawText(Glass.ellipsize(artist, ap, tw), tx, art.top + h * 0.33f, ap)
        if (src.isNotEmpty()) {
            val sp = Glass.text(pc, "r8", h * 0.08f, Glass.ICE); sp.letterSpacing = 0.1f
            c.drawText(Glass.ellipsize(src.uppercase(), sp, tw), tx, art.bottom - h * 0.02f, sp)
        }

        // İlerleme çubuğu
        val dur = now.durMs
        val by = art.bottom + pad * 0.9f
        bar.strokeWidth = h * 0.018f
        bar.color = Glass.TEXT; bar.alpha = 40
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
        // İlerleme çubuğu; bilgi gelmeyen uygulamada "çalıyor mu" seyrek yoklanır
        if (playing) postInvalidateDelayed(500) else if (hub.controller == null) postInvalidateDelayed(2000)
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
        /** Şeridin iki yanı (yol çiziminin solu / sağı), resim koordinatında: yazı rengi ayrı ölçülür */
        private val STRIP_L = DashImageGeom.STRIP.let { RectF(it.left, it.top, it.left + it.width() * 0.42f, it.bottom) }
        private val STRIP_R = DashImageGeom.STRIP.let { RectF(it.right - it.width() * 0.42f, it.top, it.right, it.bottom) }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val side = w * (1 - gapFrac) / 2
        val slot = side / 2
        val lp = Glass.text(pc, "r9", h * 0.22f, Glass.DIM); lp.letterSpacing = 0.1f; lp.textAlign = Paint.Align.CENTER
        val vp = Glass.text(pc, "r10", h * 0.44f); vp.textAlign = Paint.Align.CENTER
        val up = Glass.text(pc, "r11", h * 0.22f, Glass.DIM)
        specs.take(4).forEachIndexed { i, spec ->
            val ink = Glass.ink(if (i < 2) STRIP_L else STRIP_R)
            lp.color = ink.dim; up.color = ink.dim
            Glass.halo(lp, ink); Glass.halo(vp, ink); Glass.halo(up, ink)
            val cx = if (i < 2) slot * (i + 0.5f) else w - side + slot * (i - 2 + 0.5f)
            val d = frame[spec.signal]
            val def = SignalCatalog[spec.signal] ?: return@forEachIndexed
            c.drawText(SHORT[spec.signal] ?: def.label.uppercase(), cx, h * 0.3f, lp)
            val txt = d?.text ?: "—"
            vp.color = if (d != null && d.level != Level.NORMAL && !d.missing) theme.levelColor(d.level) else ink.text
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
    /** WMO hava durumu kodu (Open-Meteo) ve gündüz mü */
    @Volatile var code: Int? = null; private set
    @Volatile var isDay = true; private set
    /** Yerel hava basıncı (hPa, bulunulan rakımda) */
    @Volatile var pressureHpa: Double? = null; private set
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
                val url = java.net.URL("https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m,weather_code,is_day,surface_pressure"
                    .format(java.util.Locale.US, loc.latitude, loc.longitude))
                val con = (url.openConnection() as java.net.HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
                val body = con.inputStream.bufferedReader().use { it.readText() }
                val cur = org.json.JSONObject(body).getJSONObject("current")
                code = cur.optInt("weather_code", -1).takeIf { it >= 0 }
                isDay = cur.optInt("is_day", 1) == 1
                tempC = cur.getDouble("temperature_2m")
                pressureHpa = cur.optDouble("surface_pressure").takeIf { !it.isNaN() }
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

/** En önemli uyarı + kaç tane daha olduğu. Uyarı yokken görünmez, ekran yazısı (varsa) görünür. */
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
        val tp = Glass.text(pc, "t", h * 0.4f, 0xFFF2F5F8.toInt())   // hap her temada koyu
        val more = if (alerts.size > 1) "  +${alerts.size - 1}" else ""
        val mp = Glass.text(pc, "m", h * 0.34f, 0xFF9AA2A9.toInt())
        val text = Glass.ellipsize(a.text, tp, w - h * 1.6f - mp.measureText(more))
        val tw = h * 0.9f + tp.measureText(text) + mp.measureText(more) + h * 0.7f
        rect.set((w - tw) / 2, 1f, (w + tw) / 2, h - 1)
        c.drawRoundRect(rect, h / 2, h / 2, bg)
        Glass.edge.strokeWidth = maxOf(1f, h * 0.025f)
        Glass.edge.color = color; Glass.edge.alpha = 160
        c.drawRoundRect(rect, h / 2, h / 2, Glass.edge)
        Glass.edge.color = Glass.EDGE; Glass.edge.alpha = if (Glass.EDGE == Color.BLACK) 30 else 38
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

/** Detay sekmesinde arıza kodları şeridi: "ARIZA KODLARI  özet  ›" (özet her saniye yenilenir). */
@SuppressLint("ViewConstructor")  // yalnız koddan oluşturuluyor
class DtcBar(ctx: Context, private val summary: () -> Pair<String, Boolean>) : View(ctx) {
    private val pc = HashMap<String, Paint>()
    private val rect = RectF()
    private val dp = ctx.resources.displayMetrics.density

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        rect.set(1f, 1f, w - 1, h - 1)
        Glass.card(c, rect, 300 * dp)
        val pad = 14 * dp
        val tp = Glass.text(pc, "t", 12 * dp, Glass.ICE); tp.letterSpacing = 0.15f
        val base = h / 2 + 5 * dp
        c.drawText("ARIZA KODLARI", pad, base, tp)
        val (s, warn) = summary()
        val vp = Glass.text(pc, "v", 16 * dp, if (warn) Glass.AMBER else Glass.TEXT); vp.color = if (warn) Glass.AMBER else Glass.TEXT
        val x = pad + tp.measureText("ARIZA KODLARI") + 20 * dp
        c.drawText(Glass.ellipsize(s, vp, w - x - 40 * dp), x, base, vp)
        val ap = Glass.text(pc, "a", 22 * dp, Glass.DIM); ap.textAlign = Paint.Align.RIGHT
        c.drawText("›", w - pad, base + 2 * dp, ap)
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


// ---------------- Büyük saat ve hava (araç ekranının üst köşeleri) ----------------

/** Sağ üst: büyük saat, altında tarih. */
class ClockView(ctx: Context) : View(ctx) {
    private val pc = HashMap<String, Paint>()
    private val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale("tr"))
    private val day = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale("tr"))
    private val now = java.util.Date()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        now.time = System.currentTimeMillis()
        val ink = Glass.ink(DashImageGeom.CLOCK)
        val tp = Glass.text(pc, "t", h * 0.62f, ink.text); tp.textAlign = Paint.Align.RIGHT; Glass.halo(tp, ink)
        c.drawText(hm.format(now), w, h * 0.62f, tp)
        val dp = Glass.text(pc, "d", h * 0.22f, ink.dim); dp.textAlign = Paint.Align.RIGHT; dp.letterSpacing = 0.06f; Glass.halo(dp, ink)
        c.drawText(day.format(now), w, h * 0.95f, dp)
        postInvalidateDelayed(1000 - now.time % 1000)
    }
}

/**
 * Sol üst: durum simgesi, büyük sıcaklık, kısa açıklama.
 * Simgeler: Meteocons (Bas Milius, MIT lisansı), res/drawable-nodpi/wx_*.png.
 */
@SuppressLint("ViewConstructor")
class WeatherView(ctx: Context, private val weather: Weather) : View(ctx) {
    private val pc = HashMap<String, Paint>()
    private val bmpP = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    /** Simgeler kod değişince (çizim dışında) yüklenir */
    private var iconId: Int? = null
    private var iconBmp: android.graphics.Bitmap? = null

    private fun bitmapFor(id: Int?): android.graphics.Bitmap? {
        if (id != iconId) { iconId = id; iconBmp = id?.let { android.graphics.BitmapFactory.decodeResource(resources, it) } }
        return iconBmp
    }

    // Hava ilk açılışta birkaç saniye sonra gelir: o zamana kadar sık bak
    init { post(object : Runnable { override fun run() { bitmapFor(icon(weather.code)); invalidate(); postDelayed(this, if (iconId == null) 2_000 else 30_000) } }) }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        val t = weather.tempC
        val code = weather.code
        var x = 0f
        iconBmp?.takeIf { iconId == icon(code) }?.let { b ->
            val side = h * 1.05f
            dst.set(-h * 0.08f, -h * 0.1f, side - h * 0.08f, side - h * 0.1f)
            c.drawBitmap(b, null, dst, bmpP)
            x = side * 0.95f
        }
        val ink = Glass.ink(DashImageGeom.WEATHER)
        val tp = Glass.text(pc, "t", h * 0.62f, ink.text); Glass.halo(tp, ink)
        c.drawText(t?.let { "${Math.round(it)}°" } ?: "—°", x, h * 0.62f, tp)
        val dp = Glass.text(pc, "d", h * 0.22f, ink.dim); dp.letterSpacing = 0.06f; Glass.halo(dp, ink)
        c.drawText(Glass.ellipsize(text(code), dp, width - x), x, h * 0.95f, dp)
    }

    /** WMO kodu → simge (gece/gündüz ayrı) */
    private fun icon(c: Int?): Int? {
        val d = weather.isDay
        return when (c) {
            null -> null
            0 -> if (d) R.drawable.wx_clear_day else R.drawable.wx_clear_night
            1, 2 -> if (d) R.drawable.wx_partly_cloudy_day else R.drawable.wx_partly_cloudy_night
            3 -> if (d) R.drawable.wx_overcast_day else R.drawable.wx_overcast_night
            45, 48 -> if (d) R.drawable.wx_fog_day else R.drawable.wx_fog_night
            in 51..55 -> R.drawable.wx_drizzle
            56, 57, 66, 67 -> R.drawable.wx_sleet
            in 61..65 -> R.drawable.wx_rain
            in 71..77 -> R.drawable.wx_snow
            in 80..82 -> if (d) R.drawable.wx_partly_cloudy_day_rain else R.drawable.wx_partly_cloudy_night_rain
            85, 86 -> if (d) R.drawable.wx_partly_cloudy_day_snow else R.drawable.wx_partly_cloudy_night_snow
            95 -> if (d) R.drawable.wx_thunderstorms_day else R.drawable.wx_thunderstorms_night
            96, 99 -> R.drawable.wx_thunderstorms_rain
            else -> R.drawable.wx_overcast
        }
    }

    /** WMO kodu → kısa Türkçe açıklama */
    private fun text(c: Int?) = when (c) {
        null -> "Hava bilgisi yok"; 0 -> if (weather.isDay) "Güneşli" else "Açık"; 1 -> "Az bulutlu"; 2 -> "Parçalı bulutlu"; 3 -> "Kapalı"
        45, 48 -> "Sisli"; in 51..57 -> "Çisenti"; in 61..65 -> "Yağmurlu"; 66, 67 -> "Dondurucu yağmur"
        in 71..77 -> "Karlı"; in 80..82 -> "Sağanak"; 85, 86 -> "Kar sağanağı"; in 95..99 -> "Gök gürültülü"; else -> ""
    }
}
