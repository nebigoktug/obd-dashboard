package com.nebi.obdbridge.dash

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.nebi.obdbridge.BridgeService
import com.nebi.obdbridge.dash.RouteTracker.LatLon
import kotlin.math.cos
import kotlin.math.sin

/** Ekrandaki sıradaki manevra (kart ve şerit). [eta] "08:42 · 12 km" */
data class NaviState(
    val distance: String,
    val street: String,
    val arrow: Bitmap?,
    val eta: String? = null,
)

/**
 * Süren navigasyon (etkinlikten bağımsız: tema değişince ekran yeniden kurulur, yolculuk sürer).
 * Hedef tercihlere de yazılır; uygulama kapanıp [MAX_RESUME_MS] içinde açılırsa kaldığı yerden devam eder.
 */
object Nav {
    val tracker = RouteTracker()
    var destName: String = ""; private set
    var guidance: Guidance? = null
    val voice = VoicePlanner()
    /** Son bilinen konum (arama kutusu yakındaki yerleri önce göstersin) */
    var lastFix: LatLon? = null
    val active get() = tracker.dest != null

    fun start(ctx: Context, lat: Double, lon: Double, name: String) {
        tracker.setDest(lat, lon); destName = name; guidance = null; voice.reset()
        prefs(ctx).edit().putString("nav_dest", "$lat,$lon").putString("nav_dest_name", name)
            .putLong("nav_dest_ts", System.currentTimeMillis()).apply()
    }

    fun stop(ctx: Context) {
        tracker.clear(); guidance = null; voice.reset()
        prefs(ctx).edit().remove("nav_dest").remove("nav_dest_name").remove("nav_dest_ts").apply()
    }

    /** Süreç yeniden başladıysa kayıtlı hedefe devam */
    fun restore(ctx: Context) {
        if (active) return
        val p = prefs(ctx)
        val ts = p.getLong("nav_dest_ts", 0)
        if (System.currentTimeMillis() - ts > MAX_RESUME_MS) return
        val (la, lo) = p.getString("nav_dest", null)?.split(',')?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 2 } ?: return
        tracker.setDest(la, lo); destName = p.getString("nav_dest_name", "").orEmpty()
    }

    /** Ekran durumu; navigasyon yoksa ya da rota henüz gelmediyse null */
    fun state(): NaviState? {
        val g = guidance ?: return null
        val m = g.next ?: return null
        val d = g.nextDistM ?: return null
        val street = if (m.turn == Turn.FINISH) destName.ifEmpty { "Varış" } else m.street
        val eta = g.remainingS?.let {
            java.text.SimpleDateFormat("HH:mm", java.util.Locale("tr")).format(java.util.Date(System.currentTimeMillis() + (it * 1000).toLong()))
        }
        return NaviState(Guidance.formatDist(d), street, ManeuverIcon.get(m.turn),
            listOfNotNull(eta, Guidance.formatDist(g.remainingM)).joinToString(" · "))
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("dash", Context.MODE_PRIVATE)
    private const val MAX_RESUME_MS = 3 * 3600_000L
}

/** Manevra okları: beyaz, saydam zemin (kartta temaya boyanır) */
object ManeuverIcon {
    private val cache = HashMap<Turn, Bitmap>()

    fun get(t: Turn): Bitmap = cache.getOrPut(t) { draw(t) }

    private fun draw(t: Turn): Bitmap {
        val s = 96f
        val b = Bitmap.createBitmap(s.toInt(), s.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE; style = Paint.Style.STROKE; strokeWidth = s * 0.12f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
        val p = Path()
        fun head(x: Float, y: Float, deg: Double) {
            // Ok ucu: [deg] yönünde (0 = yukarı, saat yönü +)
            val a = Math.toRadians(deg); val l = s * 0.2f; val w = s * 0.16f
            val dx = sin(a).toFloat(); val dy = -cos(a).toFloat()
            val h = Path()
            h.moveTo(x + dx * l * 0.6f, y + dy * l * 0.6f)
            h.lineTo(x - dx * l * 0.5f - dy * w, y - dy * l * 0.5f + dx * w)
            h.lineTo(x - dx * l * 0.5f + dy * w, y - dy * l * 0.5f - dx * w)
            h.close(); c.drawPath(h, fill)
        }
        fun bend(deg: Double, stemX: Float = s * 0.5f) {
            // Gövde alttan ortaya, sonra [deg] yönünde
            val mx = stemX; val my = s * 0.55f
            val a = Math.toRadians(deg); val l = s * 0.3f
            val ex = mx + sin(a).toFloat() * l; val ey = my - cos(a).toFloat() * l
            p.moveTo(stemX, s * 0.92f); p.lineTo(mx, my); p.lineTo(ex, ey)
            c.drawPath(p, line); head(ex, ey, deg)
        }
        when (t) {
            Turn.STRAIGHT -> bend(0.0)
            Turn.SLIGHT_LEFT, Turn.FORK_LEFT, Turn.EXIT_LEFT -> bend(-40.0, s * 0.56f)
            Turn.SLIGHT_RIGHT, Turn.FORK_RIGHT, Turn.EXIT_RIGHT -> bend(40.0, s * 0.44f)
            Turn.LEFT -> bend(-90.0, s * 0.62f)
            Turn.RIGHT -> bend(90.0, s * 0.38f)
            Turn.HARD_LEFT -> bend(-135.0, s * 0.62f)
            Turn.HARD_RIGHT -> bend(135.0, s * 0.38f)
            Turn.UTURN_LEFT, Turn.UTURN_RIGHT -> {
                val r = s * 0.17f; val right = t == Turn.UTURN_RIGHT
                val x0 = if (right) s * 0.32f else s * 0.68f; val x1 = if (right) x0 + 2 * r else x0 - 2 * r
                p.moveTo(x0, s * 0.92f); p.lineTo(x0, s * 0.38f)
                p.arcTo(minOf(x0, x1), s * 0.38f - r, maxOf(x0, x1), s * 0.38f + r, 180f, if (right) 180f else -180f, false)
                p.lineTo(x1, s * 0.55f)
                c.drawPath(p, line); head(x1, s * 0.6f, 180.0)
            }
            Turn.ROUNDABOUT, Turn.LEAVE_ROUNDABOUT -> {
                val r = s * 0.2f; val cx = s * 0.5f; val cy = s * 0.45f
                c.drawCircle(cx, cy, r, line)
                p.moveTo(cx, s * 0.95f); p.lineTo(cx, cy + r)
                p.moveTo(cx + r * 0.7f, cy - r * 0.7f); p.lineTo(cx + r * 1.5f, cy - r * 1.5f)
                c.drawPath(p, line); head(cx + r * 1.5f, cy - r * 1.5f, 45.0)
            }
            Turn.FINISH -> {
                // Bayrak
                p.moveTo(s * 0.3f, s * 0.92f); p.lineTo(s * 0.3f, s * 0.1f)
                c.drawPath(p, line)
                val f = Path(); f.moveTo(s * 0.3f, s * 0.1f); f.lineTo(s * 0.78f, s * 0.24f); f.lineTo(s * 0.3f, s * 0.4f); f.close()
                c.drawPath(f, fill)
            }
        }
        return b
    }
}

/**
 * Türkçe sesli yönlendirme (Android TTS). Konuşurken müzik kısılır (geçici odak), bitince geri gelir.
 * Türkçe ses yüklü değilse sessiz kalır ([ready] false).
 */
class NavVoice(ctx: Context) {
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
    var ready = false; private set
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(ctx.applicationContext) { status ->
            val t = tts ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) { Log.w(BridgeService.TAG, "TTS açılamadı"); return@TextToSpeech }
            val r = t.setLanguage(java.util.Locale("tr", "TR"))
            ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            if (!ready) Log.w(BridgeService.TAG, "Türkçe TTS sesi yok ($r)")
            t.setAudioAttributes(attrs)
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { am.abandonAudioFocusRequest(focus) }
                @Deprecated("eski arayüz") override fun onError(id: String?) { am.abandonAudioFocusRequest(focus) }
            })
        }
    }

    fun say(text: String) {
        val t = tts ?: return
        if (!ready) return
        am.requestAudioFocus(focus)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav")
    }

    fun shutdown() { tts?.stop(); tts?.shutdown(); tts = null; am.abandonAudioFocusRequest(focus) }
}
