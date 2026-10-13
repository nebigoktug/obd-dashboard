package com.nebi.obdbridge.dash

import com.nebi.obdbridge.obd.Sample
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

// Dashboard'un Android'den bağımsız çekirdeği: sinyal tanımları, veri kaynakları, türetilen değerler,
// yumuşatma. Göstergeler yalnız "hangi key'i göster" bilir; veri nereden gelirse gelsin aynı çalışır.

enum class Level { NORMAL, COLD, WARN, CRIT }

/** Bir sinyalin ekranda nasıl gösterileceği. [scale]: ham değer → gösterilen birim. */
data class SignalDef(
    val key: String,
    val label: String,
    val unit: String,
    val min: Double,
    val max: Double,
    val decimals: Int = 0,
    val scale: Double = 1.0,
    val warnAbove: Double? = null,
    val critAbove: Double? = null,
    val warnBelow: Double? = null,
    val critBelow: Double? = null,
    val coldBelow: Double? = null,
    val staleMs: Long = 1500,
    val minTauMs: Long = 60,
    val maxTauMs: Long = 250,
    val tickStep: Double? = null,      // halkada etiket aralığı
    val tickLabelDiv: Double = 1.0,    // etiket metni için bölen (devir: 1000 → "3")
    val text: ((Double) -> String)? = null,   // sayı yerine metin (yön: "KD 45°")
) {
    fun level(v: Double): Level = when {
        critAbove != null && v >= critAbove -> Level.CRIT
        critBelow != null && v <= critBelow -> Level.CRIT
        warnAbove != null && v >= warnAbove -> Level.WARN
        warnBelow != null && v <= warnBelow -> Level.WARN
        coldBelow != null && v < coldBelow -> Level.COLD
        else -> Level.NORMAL
    }

    fun format(v: Double): String = text?.invoke(v) ?: if (decimals == 0) Math.round(v).toString() else "%.${decimals}f".format(v)
}

object SignalCatalog {
    /** Araç profiline göre (devir ölçeği/kırmızı bölge, hız ölçeği) yeniden kurulur: [configure] */
    @Volatile var ALL: List<SignalDef> = build(Vehicle())
        private set
    @Volatile private var BY_KEY = ALL.associateBy { it.key }

    fun configure(v: Vehicle) {
        ALL = build(v)
        BY_KEY = ALL.associateBy { it.key }
    }

    private fun build(v: Vehicle) = listOf(
        SignalDef("speed", "Hız", "km/h", 0.0, v.speedMax.toDouble(), tickStep = 20.0, maxTauMs = 300),
        SignalDef("rpm", "Devir", "rpm", 0.0, v.rpmMax.toDouble(), warnAbove = v.redline - 500.0, critAbove = v.redline.toDouble(),
            tickStep = 1000.0, tickLabelDiv = 1000.0, maxTauMs = 220),
        SignalDef("gear", "Vites", "", 0.0, 6.0, staleMs = 1500, minTauMs = 0, maxTauMs = 0),
        SignalDef("coolant", "Motor", "°C", 40.0, 130.0, warnAbove = 105.0, critAbove = 112.0, coldBelow = 60.0,
            staleMs = 6000, minTauMs = 300, maxTauMs = 800, tickStep = 30.0),
        SignalDef("iat", "Emme havası", "°C", -20.0, 80.0, warnAbove = 55.0, critAbove = 70.0,
            staleMs = 6000, minTauMs = 300, maxTauMs = 800),
        SignalDef("load", "Motor yükü", "%", 0.0, 100.0, staleMs = 2500, tickStep = 25.0),
        SignalDef("boost", "Turbo", "bar", -0.2, 1.5, decimals = 2, staleMs = 2500, tickStep = 0.5),
        SignalDef("map", "Emme basıncı", "kPa", 0.0, 250.0, staleMs = 2500, tickStep = 50.0),
        SignalDef("rail", "Rail basıncı", "bar", 0.0, 1600.0, scale = 0.01, staleMs = 2500, tickStep = 400.0),
        SignalDef("battery", "Akü", "V", 10.0, 16.0, decimals = 1, warnAbove = 14.8, critAbove = 15.2,
            warnBelow = 12.4, critBelow = 11.8, staleMs = 6000, minTauMs = 300, maxTauMs = 800, tickStep = 2.0),
        SignalDef("dtc_count", "Arıza kodu", "", 0.0, 10.0, warnAbove = 1.0, staleMs = 120_000),
        // Türetilen (Insights): tahmini, depo dolumlarıyla kalibre edilir
        SignalDef("fuel_lph", "Anlık tüketim", "L/sa", 0.0, 20.0, decimals = 1, staleMs = 2500, minTauMs = 300, maxTauMs = 800),
        SignalDef("fuel_l100", "Tüketim", "L/100", 0.0, 30.0, decimals = 1, staleMs = 2500, minTauMs = 400, maxTauMs = 1000),
        SignalDef("fuel_avg", "Ort. tüketim", "L/100", 0.0, 30.0, decimals = 1, staleMs = 5000, minTauMs = 0, maxTauMs = 0),
        // Hava basıncı (Motion.baro): hava servisi ya da motor durmuşken MAP
        SignalDef("baro", "Hava basıncı", "hPa", 900.0, 1060.0, staleMs = 600_000, minTauMs = 1000, maxTauMs = 3000),
    )
    operator fun get(key: String): SignalDef? = BY_KEY[key]
}

/** Ham veri kaynağı: canlı araç, demo ya da ileride başka bir kaynak. */
interface SignalSource {
    fun sample(key: String): Sample?
    val name: String          // durum çubuğunda: "Canlı", "Demo"
    val online: Boolean       // veri akıyor mu
    val rpmHz: Double         // bağlantı kalitesi göstergesi için
}

/**
 * Sahte sürüş: şehir içi/yol döngüsü, 5 vitesli manuel şanzıman, değerler gerçek araç gibi ~5 Hz güncellenir
 * (yumuşatmanın etkisi görülsün diye). Araç yokken tasarım ve test için.
 */
class DemoSource(private val clock: () -> Long = System::currentTimeMillis) : SignalSource {
    override val name = "Demo"
    override val online = true
    override val rpmHz = 5.0
    private val t0 = clock()
    private val ratios = RATIOS.toDoubleArray()

    companion object {
        /** rpm / (km/h), dizel 5 ileri; [0] kullanılmıyor */
        val RATIOS = listOf(0.0, 118.0, 63.0, 42.0, 32.0, 25.5)
    }

    override fun sample(key: String): Sample? {
        val tick = (clock() - t0) / 200 * 200            // 5 Hz basamak
        val t = tick / 1000.0
        // Her 70 sn'de 8 sn trafik ışığı: araç durur, rölantiye düşer (turbo sıfır noktası da böyle öğrenilir)
        val cyc = t % 70
        val drive = (55 + 45 * sin(t / 9.0) + 12 * sin(t / 2.3)).coerceAtLeast(0.0)
        val speed = when {
            cyc < 4 -> drive * (1 - cyc / 4)       // yavaşla
            cyc < 12 -> 0.0                        // dur
            cyc < 18 -> drive * (cyc - 12) / 6     // kalk
            else -> drive
        }
        val gear = when { speed < 15 -> 1; speed < 30 -> 2; speed < 50 -> 3; speed < 75 -> 4; else -> 5 }
        val rpm = if (speed < 1) 805.0 else (speed * ratios[gear]).coerceIn(820.0, 4300.0)
        val load = if (speed < 1) 30.0 else (35 + 30 * sin(t / 1.7) + 20 * sin(t / 0.9)).coerceIn(5.0, 100.0)
        val v = when (key) {
            "speed" -> speed
            "rpm" -> rpm
            "load" -> load
            "map" -> if (speed < 1) 101.0 else 101 + load * 1.2
            "coolant" -> (15 + t * 1.5).coerceAtMost(88.0)
            "iat" -> 24.0 + 3 * sin(t / 30)
            "battery" -> 14.1 + 0.1 * sin(t / 5)
            "rail" -> 25000 + load * 900
            "dtc_count" -> 2.0
            else -> return null
        }
        return Sample(key, v, t0 + tick)
    }
}

/**
 * Manuel şanzımanda vites tahmini: her viteste devir/hız oranı sabittir.
 *
 * v1 (oranları anlık kümeleme, sıraya göre numara) gerçek araçta yanlış öğreniyordu:
 * - debriyaj kaydırma, vites geçişi ve boşta süzülme sahte kümeler üretiyordu;
 * - sahte kümeler "onaylı" kaydedilip kalıcı oluyordu;
 * - numara sıraya göre verildiği için sahte küme ya da hiç kullanılmamış vites bütün numaraları kaydırıyordu;
 * - hız PID'i tam sayı km/h: 10 km/h'te ±%5 oran hatası.
 *
 * v2:
 * - Yalnız **kararlı** ölçümler öğrenilir: hız ≥ [LEARN_SPEED], art arda [STABLE_N] ölçümde oran ±%[STABLE_TOL].
 *   Geçiş ve kaydırma anında oran sürekli değiştiği için bunlar elenir.
 * - Oranlar log ölçekli bir **histograma** birikir (kalıcı). Vitesler histogramın tepeleridir.
 *   Tek seferlik sapmalar tepe oluşturamaz.
 * - Tepeler 5 ileri bir dizel kutusunun tipik oranlarına ([PRIOR]) ortak bir ölçek katsayısıyla
 *   (lastik çevresi ve diferansiyel bilinmiyor) eşlenir. Böylece hiç kullanılmamış vites numaraları kaydırmaz.
 * - Gösterim: oran bir tepeye toleransla uyuyorsa ve [SHOW_N] ölçüm aynı vitesi gösteriyorsa vites değişir.
 *   Uymuyorsa (debriyaj, boşta) null.
 */
class GearEstimator(private val prior: DoubleArray = Vehicle.current.gearPrior()) {
    /** ln(oran) histogramı, [BIN] genişliğinde */
    private val hist = DoubleArray(BINS)
    private var total = 0.0
    private var runRatio = 0.0
    private var runN = 0
    private var shown: Int? = null
    private var cand: Int? = null
    private var candN = 0
    /** gear -> oran (rpm per km/h); [rebuild] ile güncellenir */
    var ratios: Map<Int, Double> = emptyMap(); private set
    private var sinceRebuild = 0

    /** Yeni ölçüm: öğrenir ve gösterilecek vitesi döndürür. */
    fun update(rpm: Double, speed: Double): Int? {
        learn(rpm, speed)
        return show(rpm, speed)
    }

    private fun learn(rpm: Double, speed: Double) {
        if (speed < LEARN_SPEED || rpm < LEARN_RPM || rpm > 5000) { runN = 0; return }
        val r = rpm / speed
        // Tam sayı hız düşük hızda oranı oynatır: tolerans hızla daralır
        if (runN > 0 && abs(r - runRatio) / runRatio <= STABLE_TOL + 1.0 / speed) {
            runN++
            runRatio += (r - runRatio) / runN
        } else { runRatio = r; runN = 1 }
        if (runN >= STABLE_N) add(r, 1.0)
    }

    private fun add(r: Double, w: Double) {
        val i = bin(r) ?: return
        hist[i] += w
        total += w
        if (total > 20_000) { for (k in hist.indices) hist[k] *= 0.5; total *= 0.5 }  // eski sürüşler yavaşça unutulur
        if (++sinceRebuild >= 25 || ratios.isEmpty()) { sinceRebuild = 0; rebuild() }
    }

    private fun show(rpm: Double, speed: Double): Int? {
        val g = gear(rpm, speed)
        if (g == null) { shown = null; cand = null; candN = 0; return null }
        if (g == shown) { cand = null; candN = 0; return g }
        if (g == cand) candN++ else { cand = g; candN = 1 }
        if (candN >= SHOW_N) { shown = g; cand = null; candN = 0 }
        return shown
    }

    /** Ölçüme uyan vites (geçmişsiz). Debriyaj/boşta/öğrenilmemişse null. */
    fun gear(rpm: Double, speed: Double): Int? {
        if (speed < MIN_SPEED || rpm < MIN_RPM || ratios.isEmpty()) return null
        val r = rpm / speed
        // Tam sayı hız: ±0,5 km/h'in oran etkisi toleransa eklenir
        val tol = MATCH + 0.5 / speed
        val best = ratios.minBy { abs(ln(r / it.value)) }
        return if (abs(ln(r / best.value)) <= tol) best.key else null
    }

    /** Öğrenilen oranlar, 1. vitesten yukarı (eski arayüz). */
    fun confirmedRatios(): List<Double> = ratios.toSortedMap().values.toList()

    fun reset() {
        hist.fill(0.0); total = 0.0; ratios = emptyMap(); runN = 0; shown = null; cand = null
    }

    /** Histogram tepelerini bulur, [PRIOR]'a eşleyip vites numarası verir. */
    private fun rebuild() {
        val sm = DoubleArray(BINS) { i -> (-2..2).sumOf { d -> (hist.getOrNull(i + d) ?: 0.0) * (3 - abs(d)) } / 9 }
        val minMass = maxOf(MIN_PEAK, total * 0.01)
        data class Peak(val ratio: Double, val mass: Double)
        val peaks = ArrayList<Peak>()
        for (i in sm.indices) {
            if (sm[i] <= 0 || (i > 0 && sm[i - 1] > sm[i]) || (i < BINS - 1 && sm[i + 1] >= sm[i])) continue
            var m = 0.0; var lw = 0.0
            for (d in -4..4) { val h = hist.getOrNull(i + d) ?: continue; m += h; lw += h * center(i + d) }
            if (m < minMass) continue
            peaks += Peak(exp(lw / m), m)
        }
        // Birbirine %12'den yakın tepeler aynı vitestir: büyüğü kalır
        peaks.sortByDescending { it.mass }
        val kept = ArrayList<Peak>()
        for (p in peaks) if (kept.none { abs(ln(it.ratio / p.ratio)) < 0.12 }) kept += p
        val top = kept.take(prior.size).sortedByDescending { it.ratio }
        ratios = assign(top.map { it.ratio }, prior)
    }

    companion object {
        const val MIN_SPEED = 5.0
        const val MIN_RPM = 900.0
        const val LEARN_SPEED = 10.0
        const val LEARN_RPM = 1000.0
        const val STABLE_N = 3
        const val STABLE_TOL = 0.02
        const val SHOW_N = 2
        const val MATCH = 0.05
        const val MIN_PEAK = 15.0
        /** Tipik 5 ileri dizel, 205/55R16: rpm per km/h. Yalnız numaralandırma için; gerçek değerler öğrenilir. */
        val PRIOR = doubleArrayOf(118.0, 63.0, 39.0, 28.0, 22.0)
        private const val LO = 2.3      // ln(10)
        private const val BIN = 0.005   // %0,5
        private const val BINS = 520    // ln 10 .. ln ~134'ün ötesi
        private fun bin(r: Double): Int? = ((ln(r) - LO) / BIN).toInt().takeIf { r > 0 && it in 0 until BINS }
        private fun center(i: Int) = LO + (i + 0.5) * BIN

        /**
         * Tepeleri (büyükten küçüğe oran) vites numaralarına eşler: sırayı koruyan her seçim için ortak ölçek
         * (ln uzayında ortalama fark) hesaplanır, kalan sapması en küçük seçim kazanır. Ölçek ±%30'la sınırlı.
         */
        fun assign(peaks: List<Double>, prior: DoubleArray = PRIOR): Map<Int, Double> {
            if (peaks.isEmpty()) return emptyMap()
            val n = prior.size
            var best: IntArray? = null
            var bestCost = Double.MAX_VALUE
            fun rec(start: Int, chosen: IntArray, depth: Int) {
                if (depth == peaks.size) {
                    val diffs = peaks.indices.map { ln(peaks[it]) - ln(prior[chosen[it]]) }
                    val ls = diffs.average()
                    if (abs(ls) > 0.3) return
                    // Tek tepe varsa ölçek belirsiz: önceliğe olan uzaklık maliyet olur
                    val cost = diffs.sumOf { (it - ls) * (it - ls) } + (if (peaks.size == 1) ls * ls else ls * ls * 0.1)
                    if (cost < bestCost) { bestCost = cost; best = chosen.clone() }
                    return
                }
                for (g in start until n - (peaks.size - depth - 1)) { chosen[depth] = g; rec(g + 1, chosen, depth + 1) }
            }
            rec(0, IntArray(peaks.size), 0)
            val b = best ?: return peaks.indices.associate { it + 1 to peaks[it] }
            return peaks.indices.associate { b[it] + 1 to peaks[it] }
        }

        /** Bilinen oranlarla hazır (demo ve testler için). */
        fun fromRatios(rs: List<Double>) = GearEstimator(PRIOR).apply {
            for (r in rs) repeat(MIN_PEAK.toInt() * 4) { add(r, 1.0) }
        }
    }

    /** Kalıcı kayıt: histogram (yoğun bölümler) */
    fun save(): String = buildString {
        for (i in hist.indices) if (hist[i] >= 0.5) append(i).append('=').append("%.1f".format(java.util.Locale.US, hist[i])).append(';')
    }

    /** [save] çıktısını yükler. */
    fun load(s: String?) {
        if (s.isNullOrBlank()) return
        for (e in s.split(';')) {
            val i = e.substringBefore('=').toIntOrNull() ?: continue
            val v = e.substringAfter('=').toDoubleOrNull() ?: continue
            if (i in 0 until BINS) { hist[i] = v; total += v }
        }
        rebuild()
    }
}

/**
 * Seyrek gelen değeri (≈5 Hz) her karede yumuşak hedefe yaklaştırır. Zaman sabiti gelen verinin aralığına
 * uyarlanır: veri seyrekse daha yumuşak, sıksa daha çabuk (yumuşatma gereğinden fazla gecikme eklemesin).
 */
class Smoother(private val minTau: Long, private val maxTau: Long) {
    var shown = Double.NaN; private set
    private var target = Double.NaN
    private var lastTs = Long.MIN_VALUE   // 0 geçerli bir zaman damgası olabilir
    private var interval = 200.0

    fun setTarget(v: Double, ts: Long) {
        if (ts == lastTs) return
        if (lastTs != Long.MIN_VALUE) interval = interval * 0.8 + (ts - lastTs).coerceIn(20, 3000) * 0.2
        lastTs = ts
        target = v
        if (shown.isNaN() || maxTau == 0L) shown = v
    }

    fun step(dtMs: Double): Double {
        if (target.isNaN()) return shown
        val tau = (interval * 0.6).coerceIn(minTau.toDouble(), maxOf(minTau, maxTau).toDouble())
        shown = if (tau <= 0) target else shown + (target - shown) * (1 - exp(-dtMs / tau))
        return shown
    }
}

/** Ekrana hazır tek değer. */
data class Display(
    val def: SignalDef,
    val value: Double,      // yumuşatılmış, gösterilen birimde
    val text: String,
    val level: Level,
    val stale: Boolean,     // eski veri: soluk göster, sıfıra düşürme
    val missing: Boolean,   // hiç veri yok: "—"
    val ageMs: Long,
)

/**
 * Kaynaktan ham değerleri alır, türetilen sinyalleri (vites, turbo, vites önerisi) hesaplar, yumuşatır.
 * Her karede [frame] çağrılır.
 */
class DashModel(var source: SignalSource, var gears: GearEstimator = GearEstimator(), private val clock: () -> Long = System::currentTimeMillis) {
    /** Türetilen bilgiler (yakıt, uyarılar); her karede dışarıdan güncellenir */
    var insights: Insights? = null
    var motion: Motion? = null
    /** Yolculuk ortalaması için (fuel_avg) */
    var trip: Trip? = null
    /** Hız göstergesi düzeltmesi: OBD hızı bununla bölünür (1 + sapma); 1 = kapalı */
    var speedFactor = 1.0
    private val smoothers = HashMap<String, Smoother>()
    private var lastFrame = 0L
    private var lastPairTs = 0L
    private var idleMap = Double.NaN       // rölantideki emme basıncı ≈ atmosfer (araçta atmosfer PID'i yok)
    private var gear: Int? = null
    private var gearTs = 0L
    var shift = 0; private set             // +1 yükselt, -1 düşür, 0 yok

    fun frame(keys: Collection<String>): Map<String, Display> {
        val now = clock()
        val dt = if (lastFrame == 0L) 16.0 else (now - lastFrame).toDouble().coerceIn(1.0, 200.0)
        lastFrame = now
        derive(now)
        val out = HashMap<String, Display>(keys.size)
        for (k in keys) {
            val def = SignalCatalog[k] ?: continue
            val s = raw(k)
            if (s == null) {
                out[k] = Display(def, def.min, "—", Level.NORMAL, stale = true, missing = true, ageMs = Long.MAX_VALUE)
                continue
            }
            val sm = smoothers.getOrPut(k) { Smoother(def.minTauMs, def.maxTauMs) }
            sm.setTarget(s.value * def.scale, s.ts)
            val v = sm.step(dt)
            val age = now - s.ts
            val stale = age > def.staleMs || !source.online
            val missing = age > 5 * def.staleMs.coerceAtLeast(1000)
            out[k] = Display(def, v, if (missing) "—" else def.format(v), def.level(v), stale, missing, age)
        }
        return out
    }

    /** Türetilen sinyaller dahil ham değer. */
    fun raw(k: String): Sample? = when (k) {
        "gear" -> gear?.let { Sample("gear", it.toDouble(), gearTs) }
        "speed" -> source.sample("speed")?.let { if (speedFactor == 1.0 || source.name != "Canlı") it else it.copy(value = it.value / speedFactor) }
        "fuel_lph" -> insights?.fuelLph?.let { Sample(k, it, source.sample("rpm")?.ts ?: clock()) }
        "fuel_l100" -> insights?.fuelL100?.let { Sample(k, it, source.sample("rpm")?.ts ?: clock()) }
        "fuel_avg" -> {
            val l = insights?.stats?.liters ?: 0.0; val km = trip?.km ?: 0.0
            if (km >= 0.5) Sample(k, l / km * 100, clock()) else null
        }
        "baro" -> motion?.baro?.hpa?.let { Sample(k, it, clock()) }
        "boost" -> {
            val m = source.sample("map")
            // Rölanti henüz görülmediyse standart atmosfer (101,3 kPa) varsayılır
            if (m == null) null else Sample("boost", (m.value - (if (idleMap.isNaN()) 101.3 else idleMap)) / 100.0, m.ts)
        }
        else -> source.sample(k)
    }

    private fun derive(now: Long) {
        val rpm = source.sample("rpm") ?: return
        val speed = source.sample("speed") ?: return
        val map = source.sample("map")
        // Rölantide (duruyor, düşük devir) emme basıncı atmosfere eşit: turbo sıfır noktası
        if (map != null && speed.value < 1 && rpm.value in 600.0..1000.0) {
            idleMap = if (idleMap.isNaN()) map.value else idleMap * 0.95 + map.value * 0.05
        }
        val pairTs = maxOf(rpm.ts, speed.ts)
        if (pairTs != lastPairTs && abs(rpm.ts - speed.ts) < 500) {
            lastPairTs = pairTs
            gear = if (Vehicle.current.manual) gears.update(rpm.value, speed.value) else null
            gearTs = pairTs
            // Basit dizel ekonomi önerisi; aracın kendi önerisi çözülürse onunla değiştirilecek
            val g = gear
            shift = when {
                g == null -> 0
                rpm.value > (if (Vehicle.current.fuel == Fuel.PETROL) 3000 else 2600) && g < Vehicle.current.gears -> 1
                rpm.value < (if (Vehicle.current.fuel == Fuel.PETROL) 1500 else 1250) && g > 1 -> -1
                else -> 0
            }
        }
    }
}

/**
 * Yolculuk özeti: hız örneklerinin integrali (OBD ya da GPS). [GAP_MS]'den uzun veri kesintisi yeni yolculuk başlatır.
 * Duruşlar süreye dahil, ortalama hız yalnız hareketli süreden.
 */
class Trip {
    var startTs = 0L; private set
    var lastTs = 0L; private set
    var km = 0.0; private set
    var movingMs = 0L; private set
    var maxKmh = 0.0; private set
    private var lastSampleTs = 0L
    private var lastKmh = 0.0

    fun update(s: Sample?) {
        if (s == null || s.ts == lastSampleTs) return
        if (lastTs == 0L || s.ts - lastTs > GAP_MS) { reset(s.ts) }
        else {
            val dt = (s.ts - lastSampleTs).coerceIn(0, 5000)
            val v = (lastKmh + s.value) / 2           // yamuk kuralı
            km += v * dt / 3_600_000.0
            if (v >= 2) movingMs += dt
        }
        lastSampleTs = s.ts; lastTs = s.ts; lastKmh = s.value
        if (s.value > maxKmh) maxKmh = s.value
    }

    private fun reset(ts: Long) { startTs = ts; km = 0.0; movingMs = 0; maxKmh = 0.0 }

    val durationMs get() = if (startTs == 0L) 0L else lastTs - startTs
    val avgKmh get() = if (movingMs < 10_000) 0.0 else km / (movingMs / 3_600_000.0)

    companion object { const val GAP_MS = 10 * 60_000L }
}
