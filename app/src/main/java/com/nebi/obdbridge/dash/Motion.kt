package com.nebi.obdbridge.dash

import kotlin.math.sin

// Hava basıncı ve GPS sıklığı (Android'e bağlı değil, birim testli; ağ ve konum DashboardActivity'de).
// Telefon sensörlerine dayanan G-ölçer, geri vites, yön ve rakım/eğim 2026-10-13'te kaldırıldı:
// gerçek sürüşte güvenilir çalışmadılar (G-ölçer hiç öğrenemedi, harita rakımı sıçradı).

/**
 * Hava basıncı (hPa). Kaynak önceliği: hava durumu servisinin yerel basıncı > aracın MAP
 * sensörü (kontak açık, motor durmuşken: manifold dış basınçta). Rölantide ve yakıt kesmede MAP dizelde bile
 * 2..60 kPa yüksek okunuyor (gerçek sürüş, 2026-10-12), o anlar kullanılmaz.
 * MAP ile servis karşılaştırılarak MAP sensörünün kayması da izlenir.
 */
class Baro(private val clock: () -> Long = System::currentTimeMillis) {
    enum class Src(val label: String) { CAR("araç MAP"), WEATHER("hava servisi") }
    private var car = Double.NaN; private var carTs = 0L; private var carN = 0
    private var weather = Double.NaN; private var weatherTs = 0L

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
        !weather.isNaN() && clock() - weatherTs < 90 * 60_000 -> Src.WEATHER
        carFresh -> Src.CAR
        else -> null
    }

    val hpa: Double? get() = when (source) { Src.CAR -> car; Src.WEATHER -> weather; null -> null }

    /** MAP sensörü − hava servisi (hPa); ikisi de tazeyse */
    val mapOffset: Double? get() =
        if (carFresh && !weather.isNaN() && clock() - weatherTs < 90 * 60_000) car - weather else null

    companion object {
        /** Motor durmuşken alınan basınç bu kadar süre geçerli (hava basıncı saatte ~1 hPa değişir) */
        const val CAR_MS = 3 * 3600_000L
    }
}

/** Ekran ve kayıt için: DashboardActivity besler, Core.raw ve Detay okur. */
class Motion(clock: () -> Long = System::currentTimeMillis) {
    val baro = Baro(clock)

    /** Demo kaynağı için uydurma değer (tasarım ve ekran görüntüsü) */
    fun demo(t: Double) { baro.onWeather(1008.0 + 2 * sin(t / 60)) }
}

/**
 * GPS sıklığı (pil): araç bağlı değilken ve GPS 60 sn hareket görmediyse 10 sn'de bir; OBD bağlantısı ya da
 * GPS hızı görülünce 0,5 sn'ye döner (en geç bir yavaş aralıkta).
 */
class GpsPacer(private val clock: () -> Long = System::currentTimeMillis) {
    private var lastMove = clock()

    fun intervalMs(obdLive: Boolean, demo: Boolean, gpsKmh: Double?): Long {
        val now = clock()
        if (obdLive || demo || (gpsKmh ?: 0.0) > 5) { lastMove = now; return FAST }
        if (gpsKmh == null) return FAST     // henüz konum yok
        return if (now - lastMove > STILL_MS) SLOW else FAST
    }

    companion object {
        const val FAST = 500L
        const val SLOW = 10_000L
        const val STILL_MS = 60_000L
    }
}
