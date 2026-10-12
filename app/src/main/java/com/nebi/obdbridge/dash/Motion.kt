package com.nebi.obdbridge.dash

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Telefon sensörlerinden türetilenler (v1.1, 2026-10-11): yön, rakım ve eğim, hava basıncı, G-kuvveti.
// Bu dosyadaki sınıflar Android'e bağlı değil (birim testli); sensör ve ağ bağlantısı DashboardActivity'de.

/** Gidiş yönü: GPS'in hareket yönünden (telefon pusulası araçta metal yüzünden sapar). Dururken son yön kalır. */
class Heading {
    var degrees: Double? = null; private set
    var ts = 0L; private set

    fun onLocation(ts: Long, speedKmh: Double?, bearing: Double?, bearingAccDeg: Double? = null) {
        if (bearing == null || speedKmh == null || speedKmh < 8) return            // yavaşta GPS yönü rastgele
        if (bearingAccDeg != null && bearingAccDeg > 25) return
        degrees = ((bearing % 360) + 360) % 360
        this.ts = ts
    }

    companion object {
        private val NAMES = listOf("K", "KD", "D", "GD", "G", "GB", "B", "KB")
        /** 0 → "K", 45 → "KD" ... (Türkçe pusula) */
        fun name(deg: Double): String = NAMES[(((deg + 22.5) % 360 + 360) % 360 / 45).toInt() % 8]
    }
}

/**
 * Rakım ve eğim. Noktalar gidilen yola (metre) göre tutulur; eğim son [window] metrenin doğrusal eğimi.
 * Kaynak önceliği: arazi yükseklik haritası (Open-Meteo, ~90 m çözünürlük, gürültüsüz) > GPS rakımı (±10–20 m oynar).
 */
class AltitudeTrack(private val clock: () -> Long = System::currentTimeMillis) {
    enum class Src { DEM, GPS }
    private data class Pt(val dist: Double, val alt: Double, val ts: Long)
    private val dem = ArrayDeque<Pt>()
    private val gps = ArrayDeque<Pt>()
    /** Gidilen yol (m), konum güncellemelerinden */
    var distance = 0.0; private set
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN
    /** GPS rakımı yumuşatılmış (gösterim için) */
    private var gpsAlt = Double.NaN

    fun onLocation(ts: Long, lat: Double, lon: Double, accuracyM: Float?, mslAlt: Double?, vAccM: Float?) {
        if (accuracyM != null && accuracyM > 30) return
        if (!lastLat.isNaN()) {
            val d = haversine(lastLat, lastLon, lat, lon)
            if (d < 500) distance += d          // sıçrama (tünel çıkışı vb.) yolu bozmasın
        }
        lastLat = lat; lastLon = lon
        if (mslAlt != null && (vAccM == null || vAccM < 25)) {
            gpsAlt = if (gpsAlt.isNaN()) mslAlt else gpsAlt + (mslAlt - gpsAlt) * 0.2
            push(gps, Pt(distance, mslAlt, ts))
        }
    }

    /** Arazi haritasından gelen rakım; [dist] isteğin yapıldığı andaki [distance] */
    fun onDem(dist: Double, alt: Double) = push(dem, Pt(dist, alt, clock()))

    private fun push(q: ArrayDeque<Pt>, p: Pt) {
        q.addLast(p)
        while (q.size > 2 && p.dist - q.first().dist > 2000) q.removeFirst()
        while (q.size > 400) q.removeFirst()
    }

    private fun fresh(q: ArrayDeque<Pt>) = q.lastOrNull()?.takeIf { distance - it.dist < 600 && clock() - it.ts < 10 * 60_000 }

    val source: Src? get() = when { fresh(dem) != null -> Src.DEM; !gpsAlt.isNaN() && fresh(gps) != null -> Src.GPS; else -> null }

    val altitude: Double? get() = when (source) {
        Src.DEM -> {
            // Son iki harita noktası arasında gidilen yola göre doğrusal
            val a = dem.last()
            val b = dem.getOrNull(dem.size - 2)
            if (b == null || a.dist - b.dist < 1) a.alt else a.alt + (a.alt - b.alt) / (a.dist - b.dist) * (distance - a.dist).coerceAtMost(300.0)
        }
        Src.GPS -> gpsAlt
        null -> null
    }

    /** Yüzde eğim (+ yokuş yukarı); yeterli yol yoksa null */
    val grade: Double? get() {
        val (q, window) = when (source) { Src.DEM -> dem to 300.0; Src.GPS -> gps to 500.0; null -> return null }
        val end = q.last().dist
        val pts = q.filter { end - it.dist <= window }
        if (pts.size < 3 || end - pts.first().dist < window * 0.6) return null
        return (slope(pts.map { it.dist }, pts.map { it.alt }) * 100).coerceIn(-25.0, 25.0)
    }

    fun reset() { dem.clear(); gps.clear(); distance = 0.0; lastLat = Double.NaN; lastLon = Double.NaN; gpsAlt = Double.NaN }

    companion object {
        fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
            val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
            val h = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
            return 2 * r * asin(sqrt(h))
        }

        /** En küçük kareler eğimi */
        fun slope(x: List<Double>, y: List<Double>): Double {
            val mx = x.average(); val my = y.average()
            var num = 0.0; var den = 0.0
            for (i in x.indices) { num += (x[i] - mx) * (y[i] - my); den += (x[i] - mx) * (x[i] - mx) }
            return if (den < 1e-9) 0.0 else num / den
        }
    }
}

/**
 * Hava basıncı (hPa). Kaynak önceliği: telefonun barometresi > hava durumu servisinin yerel basıncı > aracın MAP
 * sensörü (kontak açık, motor durmuşken: manifold dış basınçta). Rölantide ve yakıt kesmede MAP dizelde bile
 * 2..60 kPa yüksek okunuyor (gerçek sürüş, 2026-10-12), o anlar kullanılmaz.
 * MAP ile servis karşılaştırılarak MAP sensörünün kayması da izlenir.
 */
class Baro(private val clock: () -> Long = System::currentTimeMillis) {
    enum class Src(val label: String) { PHONE("telefon"), CAR("araç MAP"), WEATHER("hava servisi") }
    private var phone = Double.NaN; private var phoneTs = 0L
    private var car = Double.NaN; private var carTs = 0L; private var carN = 0
    private var weather = Double.NaN; private var weatherTs = 0L

    fun onPhone(hpa: Double) { phone = if (phone.isNaN()) hpa else phone + (hpa - phone) * 0.1; phoneTs = clock() }

    /** Her OBD turunda; yalnız motor durmuşken (devir 0, kontak açık) örneklenir */
    fun onCar(mapKpa: Double?, rpm: Double?, speed: Double?) {
        if (mapKpa == null || rpm == null || rpm > 0 || (speed ?: 0.0) >= 1 || mapKpa !in 60.0..110.0) return
        val hpa = mapKpa * 10
        car = if (car.isNaN() || clock() - carTs > CAR_MS) hpa else car + (hpa - car) * 0.2
        carTs = clock(); carN++
    }

    fun onWeather(hpa: Double) { weather = hpa; weatherTs = clock() }

    private val carFresh get() = !car.isNaN() && carN >= 6 && clock() - carTs < CAR_MS

    val source: Src? get() = when {
        !phone.isNaN() && clock() - phoneTs < 60_000 -> Src.PHONE
        !weather.isNaN() && clock() - weatherTs < 90 * 60_000 -> Src.WEATHER
        carFresh -> Src.CAR
        else -> null
    }

    val hpa: Double? get() = when (source) { Src.PHONE -> phone; Src.CAR -> car; Src.WEATHER -> weather; null -> null }

    /** MAP sensörü − hava servisi (hPa); ikisi de tazeyse */
    val mapOffset: Double? get() =
        if (carFresh && !weather.isNaN() && clock() - weatherTs < 90 * 60_000) car - weather else null

    companion object {
        /** Motor durmuşken alınan basınç bu kadar süre geçerli (hava basıncı saatte ~1 hPa değişir) */
        const val CAR_MS = 3 * 3600_000L
        /** Basınçtan yükseklik (standart atmosfer); [seaHpa] deniz seviyesi basıncı */
        fun altitude(hpa: Double, seaHpa: Double = 1013.25) = 44330.0 * (1 - (hpa / seaHpa).pow(1 / 5.255))
    }
}

/**
 * G-kuvveti: telefon tutucuda herhangi bir açıyla durabilir. "Aşağı" yerçekiminden, "ileri" ise düz yolda
 * hızlanıp yavaşlarken öğrenilir (yatay ivme ile hızın türevi aynı yönde). Kalibrasyon düğmesi gerekmez.
 * Telefon tutucuda oynarsa (yerçekimi yönü > 12° değişirse) yeniden öğrenir.
 */
class GMeter {
    enum class State { NO_SENSOR, LEARNING, READY, UNRELIABLE }
    var state = State.NO_SENSOR; private set
    var long = 0.0; private set          // g, + hızlanma
    var lat = 0.0; private set           // g, + sağa viraj (araç sola itilir)
    var maxBrake = 0.0; private set      // g (pozitif)
    var maxAccel = 0.0; private set
    var maxLat = 0.0; private set
    /** Öğrenme ilerlemesi 0..1 */
    val progress get() = (learnN / LEARN_N.toDouble()).coerceAtMost(1.0)

    private val grav = DoubleArray(3)
    private var gravInit = false
    private val down0 = DoubleArray(3)
    private val fwdSum = DoubleArray(3)
    private var learnN = 0
    private var agree = 0.0
    private val fwd = DoubleArray(3)
    private val h = DoubleArray(3)       // yatay ivme, yumuşatılmış
    private var lastTs = 0L
    private var dvdt = 0.0               // m/s², hız kaynağından
    private var dvdtTs = 0L
    private var lastSpeed = Double.NaN
    private var lastSpeedTs = 0L

    /** Hız (km/h) güncellemesi: ileri yönü öğrenmek için ivme */
    fun onSpeed(ts: Long, kmh: Double) {
        if (!lastSpeed.isNaN() && ts - lastSpeedTs in 150..2000) {
            val a = (kmh - lastSpeed) / 3.6 / ((ts - lastSpeedTs) / 1000.0)
            dvdt += (a - dvdt) * 0.5
            dvdtTs = ts
        }
        lastSpeed = kmh; lastSpeedTs = ts
    }

    /**
     * Ham ivmeölçer (m/s², telefon ekseni, yerçekimi dahil). [gravity] varsa sistemin süzdüğü yerçekimi
     * (TYPE_GRAVITY), yoksa ivmeden alçak geçirgenle bulunur.
     */
    fun onAccel(ts: Long, a: FloatArray, gravity: FloatArray?) {
        val dt = if (lastTs == 0L) 0.02 else ((ts - lastTs) / 1000.0).coerceIn(0.001, 0.2)
        lastTs = ts
        if (state == State.NO_SENSOR) state = State.LEARNING
        if (gravity != null) for (i in 0..2) grav[i] = gravity[i].toDouble()
        else {
            // Yerçekimi sensörü yoksa: yavaş alçak geçirgen, araç belirgin hızlanırken dondurulur
            // (yoksa süren ivmelenmeyi yerçekimi sanıp yutar)
            val accelerating = ts - dvdtTs < 1500 && abs(dvdt) > 0.4
            val k = if (!gravInit) 1.0 else if (accelerating) 0.0 else 1 - exp(-dt / 20.0)
            for (i in 0..2) grav[i] += (a[i] - grav[i]) * k
        }
        gravInit = true
        val gm = norm(grav)
        if (gm < 8.3 || gm > 11.3) { state = State.UNRELIABLE; return }   // dururken 1 g olmalı: sensör bozuk/kaymış
        if (state == State.UNRELIABLE) state = State.LEARNING
        // Android dururken +1 g "yukarı" gösterir: d yukarı birim vektör
        val d = DoubleArray(3) { grav[it] / gm }
        // Telefon oynadı mı
        if (norm(down0) == 0.0) d.copyInto(down0)
        else if (dot(d, down0) < cos(Math.toRadians(12.0))) { relearn(); d.copyInto(down0) }

        // Yatay doğrusal ivme, ~0,3 sn yumuşatma (motor/yol titreşimi)
        val l = DoubleArray(3) { a[it] - grav[it] }
        val ld = dot(l, d)
        val k = 1 - exp(-dt / 0.3)
        for (i in 0..2) h[i] += (l[i] - ld * d[i] - h[i]) * k

        // İleri yönü öğren: belirgin hızlanma/yavaşlama anlarında yatay ivme, hız türeviyle aynı işaretle
        val hm = norm(h)
        if (ts - dvdtTs < 1500 && abs(dvdt) > 0.9 && hm > 0.7) {
            val s = if (dvdt > 0) 1.0 else -1.0
            if (learnN > 0) { val f = unit(fwdSum); agree += (dot(h, f) * s / hm - agree) * 0.05 }
            for (i in 0..2) fwdSum[i] += h[i] * s
            learnN++
        }
        if (learnN >= LEARN_N && (agree > 0.7 || learnN >= 3 * LEARN_N)) {
            // Yatay düzleme izdüşür
            val fd = dot(fwdSum, d)
            for (i in 0..2) fwd[i] = fwdSum[i] - fd * d[i]
            val n = norm(fwd); for (i in 0..2) fwd[i] /= n
            state = State.READY
        }
        if (state != State.READY) { long = 0.0; lat = 0.0; return }
        val right = cross(fwd, d)   // ileri × yukarı = sağ
        long = dot(h, fwd) / G
        lat = dot(h, right) / G
        if (-long > maxBrake) maxBrake = -long
        if (long > maxAccel) maxAccel = long
        if (abs(lat) > maxLat) maxLat = abs(lat)
    }

    /** Test için: öğrenilen ileri yön (telefon ekseni) */
    internal fun forward() = fwd.copyOf()

    /** Demo: rekorları uydurma değerlerle güncel tut */
    internal fun notePeaks(long: Double, lat: Double) {
        if (-long > maxBrake) maxBrake = -long
        if (long > maxAccel) maxAccel = long
        if (abs(lat) > maxLat) maxLat = abs(lat)
    }

    fun resetPeaks() { maxBrake = 0.0; maxAccel = 0.0; maxLat = 0.0 }

    fun relearn() {
        fwdSum.fill(0.0); learnN = 0; agree = 0.0
        if (state == State.READY) state = State.LEARNING
        long = 0.0; lat = 0.0
    }

    companion object {
        const val G = 9.80665
        const val LEARN_N = 150     // ~3 sn belirgin ivmelenme (50 Hz)
        fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        fun norm(a: DoubleArray) = sqrt(dot(a, a))
        fun unit(a: DoubleArray): DoubleArray { val n = norm(a); return DoubleArray(3) { if (n == 0.0) 0.0 else a[it] / n } }
        fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    }
}

/** Ekran ve kayıt için tek nokta: DashboardActivity besler, Core.raw ve Detay okur. */
class Motion(clock: () -> Long = System::currentTimeMillis) {
    val heading = Heading()
    val alt = AltitudeTrack(clock)
    val baro = Baro(clock)
    val g = GMeter()

    /** Demo kaynağı için uydurma değerler (tasarım ve ekran görüntüsü) */
    fun demo(t: Double, speedKmh: Double, ts: Long) {
        heading.onLocation(ts, 50.0, (t * 3) % 360)
        baro.onWeather(1008.0 + 2 * sin(t / 60))
        demoAlt = 850 + 40 * sin(t / 25); demoGrade = 6 * cos(t / 25)
        demoLong = 0.25 * sin(t / 3.1); demoLat = 0.3 * sin(t / 4.7)
        g.notePeaks(demoLong!!, demoLat!!)
    }
    /** Demo kapanınca uydurma değerler gerçek sensörlere bırakılır */
    fun endDemo() { demoAlt = null; demoGrade = null; demoLong = null; demoLat = null; g.resetPeaks() }
    var demoAlt: Double? = null; private set
    var demoGrade: Double? = null; private set
    var demoLong: Double? = null; private set
    var demoLat: Double? = null; private set

    // Ekranın okuduğu değerler (demoda uydurma olanlar)
    val altitudeM get() = demoAlt ?: alt.altitude
    val gradePct get() = demoGrade ?: alt.grade
    val gLong get() = demoLong ?: g.long.takeIf { g.state == GMeter.State.READY }
    val gLat get() = demoLat ?: g.lat.takeIf { g.state == GMeter.State.READY }
}

/**
 * Arazi yükseklik haritası (Open-Meteo elevation API, ~90 m çözünürlük). Gidilen her ~100 m'de bir nokta biriktirilir,
 * 5 nokta (~500 m) ya da 60 sn dolunca tek istekle sorulur. Koordinatlar 4 basamağa (~10 m) yuvarlanır.
 * Ayarlardan kapatılabilir ("Hassas rakım (çevrimiçi)").
 */
class ElevationClient(private val onResult: (Double, Double) -> Unit, private val clock: () -> Long = System::currentTimeMillis) {
    private data class P(val dist: Double, val lat: Double, val lon: Double)
    private val pending = ArrayList<P>()
    private var lastDist = -1e9
    private var firstTs = 0L
    @Volatile private var busy = false
    @Volatile private var backoffUntil = 0L
    @Volatile var requests = 0; private set
    @Volatile var failures = 0; private set

    fun onPosition(dist: Double, lat: Double, lon: Double) {
        if (dist - lastDist < 100) return
        lastDist = dist
        if (pending.isEmpty()) firstTs = clock()
        pending += P(dist, lat, lon)
        if (pending.size >= 5 || (pending.size >= 2 && clock() - firstTs > 60_000)) flush()
    }

    private fun flush() {
        if (busy || clock() < backoffUntil) { if (pending.size > 20) pending.subList(0, pending.size - 20).clear(); return }
        val batch = ArrayList(pending); pending.clear()
        busy = true
        Thread({
            try {
                val lats = batch.joinToString(",") { "%.4f".format(java.util.Locale.US, it.lat) }
                val lons = batch.joinToString(",") { "%.4f".format(java.util.Locale.US, it.lon) }
                val con = (java.net.URL("https://api.open-meteo.com/v1/elevation?latitude=$lats&longitude=$lons").openConnection()
                    as java.net.HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
                val a = org.json.JSONObject(con.inputStream.bufferedReader().use { it.readText() }).getJSONArray("elevation")
                for (i in 0 until minOf(a.length(), batch.size)) onResult(batch[i].dist, a.getDouble(i))
                requests++
            } catch (_: Exception) {
                failures++
                backoffUntil = clock() + 5 * 60_000     // çevrimdışı: 5 dk bekle
            } finally { busy = false }
        }, "elevation").start()
    }
}

/**
 * GPS sıklığı (pil): araç bağlı değilken ve telefon 60 sn kıpırdamadıysa 10 sn'de bir; hareket, OBD bağlantısı
 * ya da GPS hızı görülünce hemen 0,5 sn'ye döner. Telefonun kıpırdaması ivmeölçerden ([onAccel]).
 */
class GpsPacer(private val clock: () -> Long = System::currentTimeMillis) {
    private var mean = Double.NaN
    private var lastMove = clock()

    fun onAccel(a: FloatArray) {
        val m = sqrt((a[0] * a[0] + a[1] * a[1] + a[2] * a[2]).toDouble())
        if (mean.isNaN()) mean = m
        if (abs(m - mean) > 0.5) lastMove = clock()
        mean += (m - mean) * 0.02
    }

    /** [sensors] false: ivmeölçer kapalı, yalnız GPS hızına bakılır */
    fun intervalMs(obdLive: Boolean, demo: Boolean, gpsKmh: Double?, sensors: Boolean): Long {
        val now = clock()
        if (obdLive || demo || (gpsKmh ?: 0.0) > 5) { lastMove = maxOf(lastMove, now); return FAST }
        // İvmeölçer kapalıysa kıpırdama görülmez: yalnız GPS hızı (yukarıda) uyandırır
        if (!sensors && gpsKmh == null) return FAST
        return if (now - lastMove > STILL_MS) SLOW else FAST
    }

    companion object {
        const val FAST = 500L
        const val SLOW = 10_000L
        const val STILL_MS = 60_000L
    }
}
