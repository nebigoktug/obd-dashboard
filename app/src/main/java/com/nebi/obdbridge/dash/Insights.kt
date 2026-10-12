package com.nebi.obdbridge.dash

import com.nebi.obdbridge.obd.Sample
import kotlin.math.abs
import kotlin.math.max

// Eldeki standart PID'lerden türetilen bilgiler (2026-10-10, kullanıcı "hepsini yapalım" dedi).
// Android'siz; DashModel her karede [Insights.update] çağırır. Eşikler S40 1.6D için ilk tahmin:
// ilk sürüş kayıtlarıyla (blok kaydı CSV'si: rpm/speed/load/map/rail/coolant/battery) ayarlanacak.
// Ölçüm 2026-10-09: rölantide 802 rpm, yük %47, MAP 103 kPa, soğutucu 62 °C.

enum class AlertKind { DPF, COLD, CHARGE_LOW, CHARGE_HIGH, BATTERY_WEAK, TURBO_LOW }

data class Alert(val kind: AlertKind, val text: String, val level: Level, val priority: Int)

/** Anlık girdi: aynı karede okunan değerler (yoksa null). */
data class Inputs(
    val ts: Long,
    val rpm: Double?, val speed: Double?, val load: Double?, val map: Double?, val coolant: Double?,
    val battery: Double?, val rail: Double?, val gear: Int?, val gpsSpeed: Double?,
    val obdOnline: Boolean,
    /** Benzinlide hava debisi (g/s) ve yakıt debisi PID'i (L/sa): varsa tahmin yerine bunlar */
    val maf: Double? = null,
    val fuelRate: Double? = null,
)

class Insights {
    // ---------- Çıktılar ----------
    var alerts: List<Alert> = emptyList(); private set
    var fuelCut = false; private set
    var coasting = false; private set
    /** Tahmini tüketim; kalibrasyon yoksa da gösterilir ("tahmini") */
    var fuelLph: Double? = null; private set
    var fuelL100: Double? = null; private set
    val stats = DriveStats()
    val perf = Perf()
    val health = Health()
    val fuel = FuelModel()

    private var engineOnSince = 0L

    fun update(i: Inputs) {
        val running = i.obdOnline && (i.rpm ?: 0.0) > 400
        if (!running) engineOnSince = 0L else if (engineOnSince == 0L) engineOnSince = i.ts

        val v = Vehicle.current
        // Yakıt kesme: viteste, gaz yok, devir rölantinin üstünde. Yükten ayırt etmek dizelde güvenilir;
        // benzinlide yalnız yakıt debisi PID'i 0 diyorsa
        fuelCut = running && (if (v.fuel == Fuel.DIESEL) i.gear != null && (i.rpm ?: 0.0) > CUT_RPM && (i.load ?: 100.0) <= CUT_LOAD
            else i.fuelRate != null && i.fuelRate < 0.05 && (i.speed ?: 0.0) > 10)
        // Boşta süzülme / debriyaj: araç gidiyor, devir hiçbir vitese uymuyor
        coasting = running && (i.speed ?: 0.0) >= 8 && i.gear == null

        fuelLph = when {
            !running -> null
            fuelCut -> 0.0
            else -> fuel.lph(i.rpm, i.load, i.maf, i.fuelRate, v.fuel)
        }
        fuelL100 = fuelLph?.let { l -> i.speed?.takeIf { it >= 5 }?.let { l / it * 100 } }

        stats.update(i, running, fuelCut, coasting, fuelLph)
        perf.update(i.ts, i.speed, i.load)
        health.update(i, running, engineOnSince)
        fuel.accumulate(i.ts, fuelLph, i.speed)
        alerts = buildAlerts(i, running)
    }

    private fun buildAlerts(i: Inputs, running: Boolean): List<Alert> {
        val out = ArrayList<Alert>(3)
        val v = Vehicle.current
        if (health.dpfActive && v.fuel == Fuel.DIESEL) out += Alert(AlertKind.DPF, "DPF temizleniyor olabilir · motoru kapatma", Level.WARN, 0)
        if (health.turboLow && v.turbo) out += Alert(AlertKind.TURBO_LOW, "Turbo basıncı normalden düşük", Level.WARN, 1)
        when (health.charge) {
            Health.Charge.LOW -> out += Alert(AlertKind.CHARGE_LOW, "Şarj düşük · %.1f V".format(i.battery ?: 0.0), Level.CRIT, 2)
            Health.Charge.HIGH -> out += Alert(AlertKind.CHARGE_HIGH, "Şarj voltajı yüksek · %.1f V".format(i.battery ?: 0.0), Level.WARN, 3)
            Health.Charge.WEAK -> out += Alert(AlertKind.BATTERY_WEAK, "Akü zayıf · %.1f V".format(i.battery ?: 0.0), Level.WARN, 4)
            Health.Charge.OK -> {}
        }
        val c = i.coolant
        if (running && c != null && c < COLD_C) {
            val hot = (i.rpm ?: 0.0) > COLD_RPM
            out += Alert(AlertKind.COLD, if (hot) "Motor soğuk · devri düşür" else "Motor ısınıyor · ${c.toInt()} °C",
                if (hot) Level.WARN else Level.COLD, if (hot) 1 else 5)
        }
        return out.sortedBy { it.priority }
    }

    companion object {
        const val CUT_RPM = 1100.0
        const val CUT_LOAD = 3.0
        const val COLD_C = 60.0
        const val COLD_RPM = 2500.0
    }
}

// ---------- Yolculuk istatistikleri ----------

class DriveStats {
    var idleMs = 0L; private set
    var coastMs = 0L; private set
    var cutMs = 0L; private set
    var cutKm = 0.0; private set
    val gearMs = LongArray(8)   // [1..7]
    var harshAccel = 0; private set
    var harshBrake = 0; private set
    var liters = 0.0; private set
    private var lastTs = 0L
    private var lastSpeed: Double? = null
    private var lastSpeedTs = 0L
    private var accelHold = false
    private var brakeHold = false

    fun reset() {
        idleMs = 0; coastMs = 0; cutMs = 0; cutKm = 0.0; gearMs.fill(0); harshAccel = 0; harshBrake = 0; liters = 0.0
    }

    fun update(i: Inputs, running: Boolean, cut: Boolean, coast: Boolean, lph: Double?) {
        val dt = if (lastTs == 0L) 0L else (i.ts - lastTs).coerceIn(0, 2000)
        lastTs = i.ts
        val v = i.speed ?: 0.0
        if (running && v < 1) idleMs += dt
        if (coast) coastMs += dt
        if (cut) { cutMs += dt; cutKm += v * dt / 3_600_000.0 }
        i.gear?.let { if (it in 1..7) gearMs[it] += dt }
        if (lph != null) liters += lph * dt / 3_600_000.0
        // Sert hızlanma/fren: hız değişimi (m/s²), tam sayı hız gürültüsüne karşı ≥ 0,6 sn aralıkla
        val sp = i.speed
        if (sp != null) {
            val prev = lastSpeed
            if (prev == null) { lastSpeed = sp; lastSpeedTs = i.ts }
            else if (i.ts - lastSpeedTs >= 600) {
                val a = (sp - prev) / 3.6 / ((i.ts - lastSpeedTs) / 1000.0)
                if (a > HARSH_ACCEL) { if (!accelHold) harshAccel++; accelHold = true } else if (a < HARSH_ACCEL * 0.6) accelHold = false
                if (a < -HARSH_BRAKE) { if (!brakeHold) harshBrake++; brakeHold = true } else if (a > -HARSH_BRAKE * 0.6) brakeHold = false
                lastSpeed = sp; lastSpeedTs = i.ts
            }
        }
    }

    /** 100'den düşülen basit puan: sert olaylar ve uzun rölanti/boşta süzülme. */
    fun score(trip: Trip): Int {
        val min = max(1.0, trip.durationMs / 60_000.0)
        var p = 100.0
        p -= (harshAccel + harshBrake * 1.5) * 3
        p -= idleMs / 60_000.0 / min * 30      // yolculuğun %100'ü rölanti → −30
        p -= coastMs / 60_000.0 / min * 20
        return p.coerceIn(0.0, 100.0).toInt()
    }

    companion object {
        const val HARSH_ACCEL = 3.0   // m/s² (0-100 ~9 sn)
        const val HARSH_BRAKE = 4.0
    }
}

// ---------- Performans ----------

/** 0–100 ve 60–100 ölçümü + anlık tahmini tekerlek gücü. */
class Perf {
    var best0100: Double? = null
    var best60100: Double? = null
    var last0100: Double? = null; private set
    var last60100: Double? = null; private set
    var peakKw = 0.0
    var kw: Double? = null; private set
    /** Ekranda: "hazır" (duruyor), "ölçülüyor", null */
    var state: String? = null; private set

    private var t0 = 0L
    private var t60 = 0L
    private var lastV = -1.0
    private var lastVTs = 0L
    private var aEma = 0.0

    fun update(ts: Long, speed: Double?, load: Double?) {
        val v = speed ?: run { t0 = 0; t60 = 0; state = null; return }
        when {
            v < 1 -> { t0 = 0; t60 = 0; state = "hazır" }
            t0 == 0L && lastV in 0.0..0.9 && v >= 1 -> { t0 = ts; state = "ölçülüyor" }
        }
        if (v < lastV - 1) { t0 = 0; t60 = 0; if (state == "ölçülüyor") state = null }   // yavaşladı: ölçüm iptal
        if (lastV in 0.0..59.99 && v >= 60) t60 = ts
        if (lastV in 0.0..99.99 && v >= 100) {
            if (t0 != 0L) { val s = (ts - t0) / 1000.0; last0100 = s; best0100 = minOf(best0100 ?: s, s) }
            if (t60 != 0L) { val s = (ts - t60) / 1000.0; last60100 = s; best60100 = minOf(best60100 ?: s, s) }
            t0 = 0; t60 = 0; state = null
        }
        // Güç: P = m·a·v + hava direnci + yuvarlanma (S40: ~1400 kg sürücüyle, CdA ≈ 0,68 m²)
        if (lastV >= 0 && ts - lastVTs >= 400) {
            val a = (v - lastV) / 3.6 / ((ts - lastVTs) / 1000.0)
            aEma = aEma * 0.6 + a * 0.4
            val ms = v / 3.6
            val p = (MASS * aEma * ms + 0.5 * 1.2 * CDA * ms * ms * ms + 0.012 * MASS * 9.81 * ms) / 1000
            kw = if ((load ?: 0.0) >= 60 && aEma > 0.3) p else null
            kw?.let { if (it > peakKw) peakKw = it }
            lastV = v; lastVTs = ts
        } else if (lastV < 0) { lastV = v; lastVTs = ts }
    }

    companion object {
        const val MASS = 1400.0
        const val CDA = 0.68
    }
}

// ---------- Sağlık: şarj, DPF, turbo, hız sapması ----------

class Health {
    enum class Charge { OK, LOW, HIGH, WEAK }
    var charge = Charge.OK; private set
    var dpfActive = false; private set
    var dpfCount = 0
    var turboLow = false; private set
    /** OBD hızı / GPS hızı − 1 (ör. +0,04 = gösterge %4 fazla); null = henüz öğrenilmedi */
    var speedoError: Double? = null
    var speedoN = 0

    // Rölanti tabanı (sıcak motor): DPF temizlenmesinde rölanti devri/yükü yükselir
    var idleRpm = 0.0
    var idleLoad = 0.0
    var idleN = 0
    private var dpfSince = 0L
    private var dpfOffSince = 0L
    private var lowSince = 0L
    private var highSince = 0L

    /** Turbo tabanı: devir dilimine göre yüksek yükte ortalama turbo (bar); 1500..4000, 250'lik */
    val boostBase = DoubleArray(11)
    val boostN = IntArray(11)
    private val recent = ArrayDeque<Pair<Long, Double>>()   // (ts, ölçüm / taban)

    fun update(i: Inputs, running: Boolean, engineOnSince: Long) {
        val v = i.battery
        // Şarj: motor çalışırken 13,2..14,9 V beklenir; 30 sn süren sapma uyarı
        charge = when {
            v == null -> Charge.OK
            running && i.ts - engineOnSince > 20_000 && v < 13.2 -> { if (lowSince == 0L) lowSince = i.ts; if (i.ts - lowSince > 30_000) Charge.LOW else charge }
            running && v > 14.9 -> { if (highSince == 0L) highSince = i.ts; if (i.ts - highSince > 30_000) Charge.HIGH else charge }
            !running && i.obdOnline && v < 12.2 -> Charge.WEAK
            else -> { lowSince = 0; highSince = 0; Charge.OK }
        }

        // Sıcak rölanti
        val rpm = i.rpm; val load = i.load; val speed = i.speed ?: 0.0
        val warmIdle = running && speed < 1 && (i.coolant ?: 0.0) >= 75 && rpm != null && load != null && rpm < 1300
        if (warmIdle) {
            val regenLike = idleN >= 300 && (rpm!! > idleRpm + 80 || load!! > idleLoad + 12)
            if (regenLike) {
                if (dpfSince == 0L) dpfSince = i.ts
                dpfOffSince = 0
                if (!dpfActive && i.ts - dpfSince > 20_000) { dpfActive = true; dpfCount++ }
            } else {
                dpfSince = 0
                if (dpfActive) { if (dpfOffSince == 0L) dpfOffSince = i.ts; if (i.ts - dpfOffSince > 30_000) dpfActive = false }
                // Taban yalnız normal rölantiden öğrenilir
                idleN++
                val k = if (idleN < 300) 1.0 / idleN else 0.003
                idleRpm += (rpm!! - idleRpm) * k
                idleLoad += (load!! - idleLoad) * k
            }
        } else if (!running) { dpfSince = 0 }

        // Turbo: yüksek yükte (≥ %80) devir dilimine göre turbo basıncı; taban oturduktan sonra %25 düşüş uyarı.
        // Soğuk motorda (< 70 °C) ECU turboyu kısıyor (gerçek sürüş 2026-10-12: %30-40 düşük): o anlar sayılmaz
        val map = i.map
        if (running && (i.coolant ?: 0.0) >= 70 && rpm != null && load != null && map != null && load >= 80 && rpm in 1500.0..4000.0 && idleN > 0) {
            val boost = (map - BARO) / 100.0
            val b = ((rpm - 1500) / 250).toInt().coerceIn(0, 10)
            if (boostN[b] < 200 || !turboLow) {
                boostN[b]++
                val k = if (boostN[b] < 200) 1.0 / boostN[b] else 0.002
                boostBase[b] += (boost - boostBase[b]) * k
            }
            if (boostN[b] >= 100 && boostBase[b] > 0.2) {
                recent.addLast(i.ts to boost / boostBase[b])
                while (recent.isNotEmpty() && i.ts - recent.first().first > 60_000) recent.removeFirst()
                if (recent.size >= 30) {
                    val med = recent.map { it.second }.sorted()[recent.size / 2]
                    turboLow = med < 0.75
                }
            }
        }

        // Hız göstergesi sapması: sabit hızda (≥ 40 km/h) OBD ile GPS karşılaştırması
        val g = i.gpsSpeed
        val o = i.speed
        if (i.obdOnline && g != null && o != null && o >= 40 && g >= 35 && abs(o - g) / g < 0.15) {
            val e = o / g - 1
            speedoN++
            speedoError = (speedoError ?: e) + (e - (speedoError ?: e)) * (if (speedoN < 200) 1.0 / speedoN else 0.005)
        }
    }

    companion object {
        const val BARO = 101.3   // atmosfer PID'i yok; turbo tabanı için sabit yeterli (oransal karşılaştırma)
    }
}

// ---------- Tahmini yakıt ----------

/**
 * Standart PID'lerde yakıt debisi yok. Dizelde yük (PID 04) püskürtme miktarıyla ilişkili:
 * L/sa ≈ K · devir · (yük − Y0) / 1e5. S40'ta rölantide yük %47 olduğu için Y0 ile kaydırılır;
 * K, depo dolumlarıyla (litre ve km) kalibre edilir. Kalibrasyonsuz varsayılan: rölanti ≈ 0,7 L/sa,
 * 100 km/h'te ≈ 5 L/100 km olacak şekilde seçildi (ilk tahmin, ölçülmedi).
 */
class FuelModel {
    var k = DEFAULT_K
    var y0 = DEFAULT_Y0
    var calibrations = 0
    /** Son dolumdan beri tahmini litre ve km (kalibrasyon için) */
    var estLiters = 0.0
    var km = 0.0
    private var lastTs = 0L

    /** Kaynak önceliği: yakıt debisi PID'i > benzinlide MAF (stokiyometrik) > yük tahmini. Kalibrasyon hepsine uygulanır. */
    fun lph(rpm: Double?, load: Double?, maf: Double? = null, rate: Double? = null, f: Fuel = Fuel.DIESEL): Double? {
        val cal = k / DEFAULT_K
        return when {
            rate != null -> rate * cal
            f == Fuel.PETROL && maf != null -> maf * 3600 / (14.7 * 745) * cal   // g/s hava → L/sa benzin
            // Isınınca rölanti yükü Y0'ın altına iniyor (S40: %47 → %38); motor yakıt kesmede değilse
            // rölanti tüketiminin altına düşmez
            rpm != null && load != null -> max(k * rpm * max(load - y0, 0.0) / 1e5, IDLE_LPH * cal)
            else -> null
        }?.coerceIn(0.0, 40.0)
    }

    /** Hangi kaynak kullanılıyor (Ayarlar/Detay'da gösterilir) */
    fun source(maf: Boolean, rate: Boolean, f: Fuel) = when {
        rate -> "yakıt debisi (OBD)"; f == Fuel.PETROL && maf -> "hava debisi (MAF)"; else -> "motor yükü tahmini"
    }

    fun accumulate(ts: Long, lph: Double?, speed: Double?) {
        val dt = if (lastTs == 0L) 0L else (ts - lastTs).coerceIn(0, 2000)
        lastTs = ts
        if (lph != null) estLiters += lph * dt / 3_600_000.0
        if (speed != null) km += speed * dt / 3_600_000.0
    }

    /** Depo dolumu: pompadaki litre. Tahmin/gerçek oranıyla K düzeltilir (ilk dolumda tam, sonra yumuşak). */
    fun fillUp(realLiters: Double): Boolean {
        if (estLiters < 3 || realLiters < 3) { estLiters = 0.0; km = 0.0; return false }
        val ratio = (realLiters / estLiters).coerceIn(0.4, 2.5)
        val w = if (calibrations == 0) 1.0 else 0.5
        k *= 1 + (ratio - 1) * w
        calibrations++
        estLiters = 0.0; km = 0.0
        return true
    }

    companion object {
        const val DEFAULT_Y0 = 40.0
        // rölanti: 802 rpm, yük 47 → 0,7 L/sa ⇒ K ≈ 0,7e5 / (802·7) ≈ 12,5
        const val DEFAULT_K = 12.5
        /** Sıcak rölanti tabanı; 2026-10-11 sürüşünde model 0,2 veriyordu (1.6 dizelde beklenen ≈ 0,5–0,7, ölçülmedi) */
        const val IDLE_LPH = 0.55
    }
}

// ---------- Kalıcı kayıt (öğrenilen tabanlar, rekorlar, yakıt katsayısı) ----------

fun Insights.save(): String = org.json.JSONObject().apply {
    put("k", fuel.k); put("cal", fuel.calibrations); put("estL", fuel.estLiters); put("km", fuel.km)
    put("idleRpm", health.idleRpm); put("idleLoad", health.idleLoad); put("idleN", health.idleN)
    put("dpf", health.dpfCount)
    health.speedoError?.let { put("speedo", it) }; put("speedoN", health.speedoN)
    put("boostBase", org.json.JSONArray(health.boostBase.toList())); put("boostN", org.json.JSONArray(health.boostN.toList()))
    perf.best0100?.let { put("b0100", it) }; perf.best60100?.let { put("b60100", it) }; put("peakKw", perf.peakKw)
}.toString()

fun Insights.load(s: String?) {
    if (s.isNullOrBlank()) return
    try {
        val o = org.json.JSONObject(s)
        fuel.k = o.optDouble("k", FuelModel.DEFAULT_K); fuel.calibrations = o.optInt("cal")
        fuel.estLiters = o.optDouble("estL", 0.0); fuel.km = o.optDouble("km", 0.0)
        health.idleRpm = o.optDouble("idleRpm", 0.0); health.idleLoad = o.optDouble("idleLoad", 0.0); health.idleN = o.optInt("idleN")
        health.dpfCount = o.optInt("dpf")
        if (o.has("speedo")) health.speedoError = o.getDouble("speedo"); health.speedoN = o.optInt("speedoN")
        o.optJSONArray("boostBase")?.let { a -> for (i in 0 until minOf(a.length(), 11)) health.boostBase[i] = a.getDouble(i) }
        o.optJSONArray("boostN")?.let { a -> for (i in 0 until minOf(a.length(), 11)) health.boostN[i] = a.getInt(i) }
        if (o.has("b0100")) perf.best0100 = o.getDouble("b0100")
        if (o.has("b60100")) perf.best60100 = o.getDouble("b60100")
        perf.peakKw = o.optDouble("peakKw", 0.0)
    } catch (_: Exception) {}
}
