package com.nebi.obdbridge.obd

import com.nebi.obdbridge.t

/**
 * Mode 06: motor beyninin kendi teşhis testlerinin değerleri ve sınırları (2026-11-27, YAPILACAKLAR §2).
 * Yalnız CAN (ISO 15765) biçimi: "46 MID TID UASID değer(2) en az(2) en çok(2)", her kayıt 9 bayt, bir izleyicide birden çok test.
 * Ölçekler SAE J1979 ek E (Unit and Scaling ID). Sayısal değerler python-OBD (brendan-w) tablosuyla karşılaştırıldı.
 * Yalnız okur; eğilim saklama ikinci adım (bu işe dahil değil).
 */
data class Mode06Test(
    val mid: Int, val tid: Int, val uasid: Int,
    val value: Double, val min: Double, val max: Double, val unit: String,
) {
    enum class Verdict { PASS, NEAR, FAIL }

    /**
     * Sınırların dışı: FAIL. Bir sınırın kendi büyüklüğünün %10'u içindeyse NEAR. Gerçek ECU'larda sınırlar çoğu zaman tek yönlü
     * (diğeri 0 ya da 0xFFFF): aralığın yüzdesiyle ölçmek geniş aralıkta her değeri "yakın" gösteriyordu (emülatördeki gerçek araç
     * kaydı, O2 0,169–19,9 V). 0 sınır "sınır yok" sayılır.
     */
    val verdict: Verdict get() {
        if (value < min || value > max) return Verdict.FAIL
        if (max <= min) return Verdict.PASS   // tek değerli sınır (en az = en çok = değer)
        val nearLow = min != 0.0 && value - min < kotlin.math.abs(min) * NEAR
        val nearHigh = max != 0.0 && max - value < kotlin.math.abs(max) * NEAR
        return if (nearLow || nearHigh) Verdict.NEAR else Verdict.PASS
    }

    companion object { const val NEAR = 0.1 }
}

data class Mode06Report(
    /** Desteklenen izleyiciler (maske kayıtları hariç) */
    val supported: List<Int>,
    val tests: List<Mode06Test>,
    /** Ham istek → cevap satırları (aracın neyi desteklediğini görmek için kaydedilir) */
    val raw: String,
    val error: String? = null,
    val ts: Long = 0,
)

object Mode06 {
    private class Uas(val signed: Boolean, val scale: Double, val unit: String, val offset: Double = 0.0)

    /** J1979 ek E. Bilinmeyen kimlik: ham sayı, birimsiz */
    private val UAS: Map<Int, Uas> = mapOf(
        0x01 to Uas(false, 1.0, ""), 0x02 to Uas(false, 0.1, ""), 0x03 to Uas(false, 0.01, ""), 0x04 to Uas(false, 0.001, ""),
        0x05 to Uas(false, 0.0000305, ""), 0x06 to Uas(false, 0.000305, ""), 0x07 to Uas(false, 0.25, "rpm"),
        0x08 to Uas(false, 0.01, "km/h"), 0x09 to Uas(false, 1.0, "km/h"), 0x0A to Uas(false, 0.122, "mV"),
        0x0B to Uas(false, 0.001, "V"), 0x0C to Uas(false, 0.01, "V"), 0x0D to Uas(false, 0.00390625, "mA"),
        0x0E to Uas(false, 0.001, "A"), 0x0F to Uas(false, 0.01, "A"), 0x10 to Uas(false, 1.0, "ms"),
        0x11 to Uas(false, 100.0, "ms"), 0x12 to Uas(false, 1.0, "s"), 0x13 to Uas(false, 1.0, "mΩ"),
        0x14 to Uas(false, 1.0, "Ω"), 0x15 to Uas(false, 1.0, "kΩ"), 0x16 to Uas(false, 0.1, "°C", -40.0),
        0x17 to Uas(false, 0.01, "kPa"), 0x18 to Uas(false, 0.0117, "kPa"), 0x19 to Uas(false, 0.079, "kPa"),
        0x1A to Uas(false, 1.0, "kPa"), 0x1B to Uas(false, 10.0, "kPa"), 0x1C to Uas(false, 0.01, "°"),
        0x1D to Uas(false, 0.5, "°"), 0x1E to Uas(false, 0.0000305, "λ"), 0x1F to Uas(false, 0.05, ""),
        0x20 to Uas(false, 0.00390625, ""), 0x21 to Uas(false, 1.0, "mHz"), 0x22 to Uas(false, 1.0, "Hz"),
        0x23 to Uas(false, 1.0, "kHz"), 0x24 to Uas(false, 1.0, ""), 0x25 to Uas(false, 1.0, "km"),
        0x26 to Uas(false, 0.1, "mV/ms"), 0x27 to Uas(false, 0.01, "g/s"), 0x28 to Uas(false, 1.0, "g/s"),
        0x29 to Uas(false, 0.25, "Pa/s"), 0x2A to Uas(false, 0.001, "kg/h"), 0x2B to Uas(false, 1.0, ""),
        0x2C to Uas(false, 0.01, "g"), 0x2D to Uas(false, 0.01, "mg"), 0x2E to Uas(false, 1.0, ""),
        0x2F to Uas(false, 0.01, "%"), 0x30 to Uas(false, 0.001526, "%"), 0x31 to Uas(false, 0.001, "L"),
        0x32 to Uas(false, 0.0000305, "in"), 0x33 to Uas(false, 0.00024414, ""), 0x34 to Uas(false, 1.0, "min"),
        0x35 to Uas(false, 10.0, "ms"), 0x36 to Uas(false, 0.01, "g"), 0x37 to Uas(false, 0.1, "g"),
        0x38 to Uas(false, 1.0, "g"), 0x39 to Uas(false, 0.01, "%", -327.68), 0x3A to Uas(false, 0.001, "g"),
        0x3B to Uas(false, 0.0001, "g"), 0x3C to Uas(false, 0.1, "µs"), 0x3D to Uas(false, 0.01, "mA"),
        0x3E to Uas(false, 0.00006103516, "mm²"), 0x3F to Uas(false, 0.01, "L"), 0x40 to Uas(false, 1.0, "ppm"),
        0x41 to Uas(false, 0.01, "µA"),
        0x81 to Uas(true, 1.0, ""), 0x82 to Uas(true, 0.1, ""), 0x83 to Uas(true, 0.01, ""), 0x84 to Uas(true, 0.001, ""),
        0x85 to Uas(true, 0.0000305, ""), 0x86 to Uas(true, 0.000305, ""), 0x87 to Uas(true, 1.0, "ppm"),
        0x8A to Uas(true, 0.122, "mV"), 0x8B to Uas(true, 0.001, "V"), 0x8C to Uas(true, 0.01, "V"),
        0x8D to Uas(true, 0.00390625, "mA"), 0x8E to Uas(true, 0.001, "A"), 0x90 to Uas(true, 1.0, "ms"),
        0x96 to Uas(true, 0.1, "°C"), 0x99 to Uas(true, 0.1, "kPa"), 0x9C to Uas(true, 0.01, "°"),
        0x9D to Uas(true, 0.5, "°"), 0xA8 to Uas(true, 1.0, "g/s"), 0xA9 to Uas(true, 0.25, "Pa/s"),
        0xAD to Uas(true, 0.01, "mg"), 0xAE to Uas(true, 0.1, "mg"), 0xAF to Uas(true, 0.01, "%"),
        0xB0 to Uas(true, 0.003052, "%"), 0xB1 to Uas(true, 2.0, "mV/s"), 0xFC to Uas(true, 0.01, "kPa"),
        0xFD to Uas(true, 0.001, "kPa"), 0xFE to Uas(true, 0.25, "Pa"),
    )

    fun scale(uasid: Int, raw: Int): Pair<Double, String> {
        val u = UAS[uasid] ?: return raw.toDouble() to ""
        val v = if (u.signed && raw >= 0x8000) raw - 0x10000 else raw
        return v * u.scale + u.offset to u.unit
    }

    /** "46 MID TID UASID …" cevabından testler ([mid] dışındaki kayıtlar atlanır; klonun eklediği artık baytlar kesilir) */
    fun parse(frames: List<ByteArray>, mid: Int): List<Mode06Test> {
        val out = ArrayList<Mode06Test>()
        for (b in frames) {
            if (b.isEmpty() || (b[0].toInt() and 0xFF) != 0x46) continue
            var i = 1
            while (i + 9 <= b.size) {
                val m = b[i].toInt() and 0xFF
                if (m != mid) break
                fun u(k: Int) = b[i + k].toInt() and 0xFF
                fun w(k: Int) = u(k) shl 8 or u(k + 1)
                val tid = u(1); val uas = u(2)
                val (v, unit) = scale(uas, w(3))
                out += Mode06Test(m, tid, uas, v, scale(uas, w(5)).first, scale(uas, w(7)).first, unit)
                i += 9
            }
        }
        return out
    }

    /**
     * 0600/0620… cevabından maske: "46 00 AA BB CC DD". Bazı adaptör/emülatörler izleyici baytını atlıyor ("46 AA BB CC DD",
     * ELM327-emulator 2026-11-27): tam 5 baytlık cevap o biçimde okunur.
     */
    fun mask(frames: List<ByteArray>, base: Int): Set<Int>? {
        for (b in frames) {
            if (b.isEmpty() || (b[0].toInt() and 0xFF) != 0x46) continue
            if (b.size >= 6 && (b[1].toInt() and 0xFF) == base) return ElmParser.supportedFromMask(base, b.copyOfRange(2, 6))
            if (b.size == 5) return ElmParser.supportedFromMask(base, b.copyOfRange(1, 5))
        }
        return null
    }

    // ---------- Adlar (J1979 ek D: OBDMID, ek F: standart TID) ----------

    fun midName(mid: Int): String {
        fun bs(first: Int) = (mid - first).let { (it / 4 + 1) to (it % 4 + 1) }   // banka, sensör
        return when (mid) {
            in 0x01..0x10 -> bs(0x01).let { (b, s) -> t("Oksijen sensörü B%dS%d", b, s) }
            in 0x21..0x24 -> t("Katalizör banka %d", mid - 0x20)
            in 0x31..0x34 -> t("EGR banka %d", mid - 0x30)
            in 0x35..0x38 -> t("Değişken supap zamanlaması banka %d", mid - 0x34)
            0x39 -> t("Yakıt buharı (EVAP) · kapak açık / 0,150\"")
            0x3A -> t("Yakıt buharı (EVAP) · 0,090\"")
            0x3B -> t("Yakıt buharı (EVAP) · 0,040\"")
            0x3C -> t("Yakıt buharı (EVAP) · 0,020\"")
            0x3D -> t("Buhar tahliye akışı")
            in 0x41..0x50 -> bs(0x41).let { (b, s) -> t("Oksijen sensörü ısıtıcısı B%dS%d", b, s) }
            in 0x61..0x64 -> t("Isıtmalı katalizör banka %d", mid - 0x60)
            in 0x71..0x74 -> t("İkincil hava %d", mid - 0x70)
            in 0x81..0x84 -> t("Yakıt sistemi banka %d", mid - 0x80)
            in 0x85..0x86 -> t("Turbo basınç kontrolü banka %d", mid - 0x84)
            in 0x90..0x91 -> t("NOx tutucu banka %d", mid - 0x8F)
            in 0x98..0x99 -> t("NOx katalizörü banka %d", mid - 0x97)
            0xA1 -> t("Ateşleme kaçırma · genel")
            in 0xA2..0xAD -> t("Ateşleme kaçırma · %d. silindir", mid - 0xA1)
            in 0xB0..0xB1 -> t("Partikül filtresi (DPF) banka %d", mid - 0xAF)
            else -> t("İzleyici $%02X", mid)
        }
    }

    fun tidName(tid: Int): String = when (tid) {
        0x01 -> t("Zenginden fakire eşik voltajı")
        0x02 -> t("Fakirden zengine eşik voltajı")
        0x03 -> t("Geçiş süresi için düşük voltaj")
        0x04 -> t("Geçiş süresi için yüksek voltaj")
        0x05 -> t("Zenginden fakire geçiş süresi")
        0x06 -> t("Fakirden zengine geçiş süresi")
        0x07 -> t("Test döngüsündeki en düşük voltaj")
        0x08 -> t("Test döngüsündeki en yüksek voltaj")
        0x09 -> t("Geçişler arası süre")
        0x0A -> t("Sensör periyodu")
        0x0B -> t("Son 10 sürüşte ortalama ateşleme kaçırma")
        0x0C -> t("Bu ve son sürüşte ateşleme kaçırma")
        else -> if (tid >= 0x80) t("Üretici testi $%02X", tid) else t("Test $%02X", tid)
    }
}

/**
 * Tek seferlik okuma (canlı sorgunun arasında, ObdEngine.requestJob). Desteklenen izleyici maskeleri 0600, 0620… ($x0 bit 32 bir
 * sonraki maskenin varlığını söyler), sonra her izleyici. Her istek ham kayda yazılır.
 */
class Mode06Reader(private val request: (String, Long) -> String, private val isCan: Boolean, private val clock: () -> Long) {
    fun run(): Mode06Report {
        val raw = StringBuilder()
        fun ask(cmd: String): String = request(cmd, 3000).also { raw.append(cmd).append(" → ").append(it.trim().replace("\r", " ").replace("\n", " ")).append('\n') }
        if (!isCan) return Mode06Report(emptyList(), emptyList(), raw.toString(), t("Mode 06 şimdilik yalnız CAN araçlarda okunuyor"), clock())
        val supported = ArrayList<Int>()
        var base = 0x00
        while (base <= 0xE0) {
            val r = ask("06%02X".format(base))
            if (r == ObdEngine.TIMEOUT) return Mode06Report(supported, emptyList(), raw.toString(), t("Adaptör cevap vermedi"), clock())
            val m = Mode06.mask(ElmParser.frames(r), base) ?: break
            supported += m.filter { it % 0x20 != 0 }.sorted()
            if ((base + 0x20) !in m) break
            base += 0x20
        }
        if (supported.isEmpty() && base == 0x00)
            return Mode06Report(emptyList(), emptyList(), raw.toString(), t("Motor beyni Mode 06'yı desteklemiyor ya da cevap vermedi"), clock())
        val tests = ArrayList<Mode06Test>()
        for (mid in supported) {
            val r = ask("06%02X".format(mid))
            if (r == ObdEngine.TIMEOUT) break
            tests += Mode06.parse(ElmParser.frames(r), mid)
        }
        return Mode06Report(supported, tests, raw.toString(), null, clock())
    }
}
