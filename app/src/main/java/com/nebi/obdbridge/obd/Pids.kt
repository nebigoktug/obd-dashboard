package com.nebi.obdbridge.obd

/** Sorgu katmanı: hedef periyot (ms). A en sık, C en seyrek. */
enum class Tier(val periodMs: Long) { A(60), B(300), C(2000) }

/**
 * Mode 01 PID tanımı. [bytes]: cevaptaki veri baytı sayısı (çoklu PID ayrıştırması buna dayanır).
 * [key]: dışarıya verilen ad. Aynı key'e birden fazla PID adayı olabilir (ör. pedal: 49 → 5A → 11),
 * desteklenen ilk aday kullanılır.
 */
class PidDef(
    val key: String,
    val pid: Int,
    val bytes: Int,
    val unit: String,
    val tier: Tier,
    val decode: (ByteArray) -> Double,
)

private fun u8(b: ByteArray) = (b[0].toInt() and 0xFF).toDouble()
private fun u16(b: ByteArray) = ((b[0].toInt() and 0xFF) * 256 + (b[1].toInt() and 0xFF)).toDouble()

object Pids {
    /** Öncelik sırasıyla. Aynı key'li adaylardan desteklenen ilki seçilir. */
    val ALL = listOf(
        PidDef("rpm", 0x0C, 2, "rpm", Tier.A) { u16(it) / 4 },
        PidDef("speed", 0x0D, 1, "km/h", Tier.A) { u8(it) },
        // Dizelde gaz kelebeği yerine pedal konumu anlamlı
        PidDef("pedal", 0x49, 1, "%", Tier.A) { u8(it) * 100 / 255 },
        PidDef("pedal", 0x5A, 1, "%", Tier.A) { u8(it) * 100 / 255 },
        PidDef("pedal", 0x11, 1, "%", Tier.A) { u8(it) * 100 / 255 },
        PidDef("load", 0x04, 1, "%", Tier.B) { u8(it) * 100 / 255 },
        PidDef("map", 0x0B, 1, "kPa", Tier.B) { u8(it) },
        PidDef("maf", 0x10, 2, "g/s", Tier.B) { u16(it) / 100 },
        PidDef("rail", 0x23, 2, "kPa", Tier.B) { u16(it) * 10 },
        PidDef("fuel_rate", 0x5E, 2, "L/h", Tier.B) { u16(it) / 20 },
        PidDef("coolant", 0x05, 1, "°C", Tier.C) { u8(it) - 40 },
        PidDef("iat", 0x0F, 1, "°C", Tier.C) { u8(it) - 40 },
        PidDef("baro", 0x33, 1, "kPa", Tier.C) { u8(it) },
        PidDef("fuel", 0x2F, 1, "%", Tier.C) { u8(it) * 100 / 255 },
        PidDef("ambient", 0x46, 1, "°C", Tier.C) { u8(it) - 40 },
        PidDef("oil", 0x5C, 1, "°C", Tier.C) { u8(it) - 40 },
    )

    /** Ayrıştırma için bilinen tüm PID uzunlukları (istenmeyen PID gelse bile kayma olmasın). */
    private val LEN = HashMap<Int, Int>().apply {
        for (p in ALL) put(p.pid, p.bytes)
        for (b in 0x00..0xE0 step 0x20) put(b, 4)  // desteklenen-PID bit maskeleri
        put(0x01, 4); put(0x03, 2); put(0x1F, 2); put(0x21, 2); put(0x31, 2); put(0x42, 2)
    }

    fun len(pid: Int): Int = LEN[pid] ?: 4

    /** Desteklenen PID kümesine göre her key için bir PidDef seçer. */
    fun select(supported: Set<Int>): List<PidDef> {
        val seen = HashSet<String>()
        return ALL.filter { it.pid in supported && seen.add(it.key) }
    }
}
