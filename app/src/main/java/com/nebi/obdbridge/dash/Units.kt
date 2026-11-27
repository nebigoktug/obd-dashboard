package com.nebi.obdbridge.dash

import android.content.Context
import com.nebi.obdbridge.L
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Gösterim birimleri (2026-11-27, Reddit isteği): dilden bağımsız ayar. Uygulama içinde her şey metrik kalır
 * (sinyaller, kayıtlar, geçmiş, eşikler km/h, °C, L/100 km, kPa); dönüşüm yalnız ekrana/sese yazarken burada yapılır.
 * Varsayılan telefonun bölgesinden: ABD, Liberya, Myanmar tam imperial; İngiltere mil + °C + mpg (UK) + psi.
 */
object Units {
    enum class Consumption { L100, MPG_US, MPG_UK, KML }

    @Volatile var miles = false
    @Volatile var fahrenheit = false
    @Volatile var consumption = Consumption.L100
    @Volatile var psi = false

    const val KM_PER_MI = 1.609344
    const val M_PER_FT = 0.3048
    private const val MPG_US_K = 235.214583   // mpg (US) × L/100 km
    private const val MPG_UK_K = 282.480936
    const val PSI_PER_KPA = 0.1450377

    // ---------- Varsayılan, kayıt ----------

    data class Choice(val miles: Boolean, val fahrenheit: Boolean, val consumption: Consumption, val psi: Boolean)

    fun defaultFor(country: String): Choice = when (country.uppercase(Locale.ROOT)) {
        "US", "LR", "MM" -> Choice(true, true, Consumption.MPG_US, true)
        "GB" -> Choice(true, false, Consumption.MPG_UK, true)
        else -> Choice(false, false, Consumption.L100, false)
    }

    fun apply(c: Choice) { miles = c.miles; fahrenheit = c.fahrenheit; consumption = c.consumption; psi = c.psi }

    /** Ayar yoksa bölgeye göre varsayılan; kullanıcı bir kez değiştirince kayıtlı seçim geçerli */
    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("dash", Context.MODE_PRIVATE)
        val d = defaultFor(Locale.getDefault().country)
        apply(Choice(
            p.getBoolean("unit_miles", d.miles),
            p.getBoolean("unit_f", d.fahrenheit),
            p.getString("unit_fuel", null)?.let { s -> Consumption.values().firstOrNull { it.name == s } } ?: d.consumption,
            p.getBoolean("unit_psi", d.psi),
        ))
    }

    /** Seçimi kaydeder (dört ayar birlikte: biri değişince diğerleri de bölge varsayılanından kopmasın) */
    fun save(ctx: Context) {
        ctx.getSharedPreferences("dash", Context.MODE_PRIVATE).edit()
            .putBoolean("unit_miles", miles).putBoolean("unit_f", fahrenheit)
            .putString("unit_fuel", consumption.name).putBoolean("unit_psi", psi).apply()
        SignalCatalog.configure(Vehicle.current)
    }

    // ---------- Dönüşüm (metrik → gösterilen) ----------

    fun speed(kmh: Double) = if (miles) kmh / KM_PER_MI else kmh
    fun dist(km: Double) = if (miles) km / KM_PER_MI else km
    fun temp(c: Double) = if (fahrenheit) c * 9 / 5 + 32 else c
    /** Sıcaklık farkı (eşik aralığı vb.) */
    fun tempDelta(c: Double) = if (fahrenheit) c * 9 / 5 else c
    fun kpa(v: Double) = if (psi) v * PSI_PER_KPA else v
    fun bar(v: Double) = if (psi) v * 100 * PSI_PER_KPA else v

    /** L/100 km → seçilen tüketim birimi. 0 (yakıt kesik) mpg'de sonsuz olur: [MAX_ECONOMY]'de kesilir. */
    fun fuel(l100: Double): Double = when (consumption) {
        Consumption.L100 -> l100
        Consumption.MPG_US -> economy(MPG_US_K, l100)
        Consumption.MPG_UK -> economy(MPG_UK_K, l100)
        Consumption.KML -> economy(100.0, l100)
    }
    const val MAX_ECONOMY = 99.9
    private fun economy(k: Double, l100: Double) = if (l100 <= k / MAX_ECONOMY) MAX_ECONOMY else k / l100

    /** mpg / km/L: büyük değer iyi (L/100'ün tersi) */
    val economyUnit get() = consumption != Consumption.L100

    // ---------- Birim adları ----------

    val speedUnit get() = if (miles) "mph" else "km/h"
    val distUnit get() = if (miles) "mi" else "km"
    val tempUnit get() = if (fahrenheit) "°F" else "°C"
    val pressureUnit get() = if (psi) "psi" else "kPa"
    val barUnit get() = if (psi) "psi" else "bar"
    val fuelUnit get() = when (consumption) {
        Consumption.L100 -> "L/100"
        Consumption.MPG_US -> "mpg"
        Consumption.MPG_UK -> "mpg (UK)"
        Consumption.KML -> "km/L"
    }

    // ---------- Hazır metin ----------

    private fun num(v: Double, decimals: Int) = "%.${decimals}f".format(L.locale, v)

    fun speedText(kmh: Double) = "${speed(kmh).roundToInt()} $speedUnit"
    fun distText(km: Double, decimals: Int = 1) = "${num(dist(km), decimals)} $distUnit"
    fun tempText(c: Double) = "${temp(c).roundToInt()}$tempUnit"
    fun fuelText(l100: Double) = "${num(fuel(l100), 1)} $fuelUnit"

    /** Hız kadranının üst sınırı (gösterilen birimde). Mil seçiliyse 20'nin katı: rakamlar 20'şer yazılır. */
    fun speedDialMax(kmh: Int): Int = if (!miles) kmh else ((kmh / KM_PER_MI / 20).roundToInt() * 20).coerceAtLeast(60)

    /** Ayarlar'daki hız kadranı seçenekleri (km/h olarak saklanır) */
    fun speedDialOptions(): List<Int> = if (miles) listOf(120, 140, 160).map { (it * KM_PER_MI).roundToInt() } else listOf(200, 220, 240, 260)

    /** Navigasyon ekranı: "80 m", "1,2 km" ya da "300 ft", "0.4 mi" ([parseDistance] okur) */
    fun navDist(m: Double): String {
        if (!miles) return when {
            m < 1000 -> "${((m / 10).roundToInt() * 10).coerceAtLeast(0)} m"
            m < 10_000 -> "${num(m / 1000, 1)} km"
            else -> "${(m / 1000).roundToInt()} km"
        }
        val mi = m / 1000 / KM_PER_MI
        return when {
            mi < 0.1 -> "${((m / M_PER_FT / 50).roundToInt() * 50).coerceAtLeast(0)} ft"
            mi < 10 -> "${num(mi, 1)} mi"
            else -> "${mi.roundToInt()} mi"
        }
    }

    /** "300 m", "1,2 km", "1.2 km", "800 м", "500 ft", "0.4 mi" → metre; tanınmazsa null */
    fun parseDistance(s: String): Double? {
        val m = Regex("""(\d+(?:[.,]\d+)?)\s*(km|mi|ft|m|км|м)\b""", RegexOption.IGNORE_CASE).find(s.replace('\u00A0', ' ')) ?: return null
        val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        return when (m.groupValues[2].lowercase()) {
            "km", "км" -> v * 1000
            "mi" -> v * KM_PER_MI * 1000
            "ft" -> v * M_PER_FT
            else -> v
        }
    }
}
