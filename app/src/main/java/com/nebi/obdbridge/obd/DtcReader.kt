package com.nebi.obdbridge.obd

/**
 * Arıza kodu ekranı için tek seferlik okuma (ve isteğe bağlı silme). Canlı sorgunun arasında,
 * motorun kurduğu ELM ayarlarıyla (ATH0, motor ECU başlığı) çalışır; ayar değiştirmez.
 *
 * Servisler: 03 kayıtlı, 07 bekleyen, 0A kalıcı (silinemez; her araç desteklemez), 0101 MIL + hazırlık testleri,
 * 04 silme (yalnız [clear] true ise, kullanıcı onayından sonra).
 */
data class DtcReport(
    val stored: List<String>,
    val pending: List<String>,
    /** null: ECU kalıcı kod servisini desteklemiyor */
    val permanent: List<String>?,
    val mil: Boolean?,
    /** Tamamlanan / desteklenen emisyon hazırlık testi sayısı (0101 B-D baytları) */
    val readyDone: Int?,
    val readyTotal: Int?,
    /** Silme istendiyse: true başarılı, false ECU reddetti (çoğu araç motor çalışırken reddeder) */
    val cleared: Boolean? = null,
    val error: String? = null,
    val ts: Long = 0,
)

class DtcReader(
    private val request: (String, Long) -> String,
    private val isCan: Boolean,
    private val clock: () -> Long,
) {
    fun run(clear: Boolean): DtcReport {
        var cleared: Boolean? = null
        if (clear) {
            val r = request("04", 5000)
            cleared = ElmParser.frames(r).any { it.isNotEmpty() && (it[0].toInt() and 0xFF) == 0x44 }
            if (r == ObdEngine.TIMEOUT) return DtcReport(emptyList(), emptyList(), null, null, null, null, false, "Adaptör cevap vermedi", clock())
            Thread.sleep(500)  // bazı ECU'lar silmeden hemen sonra meşgul cevap veriyor
        }
        val stored = read(0x03) ?: return DtcReport(emptyList(), emptyList(), null, null, null, null, cleared, "Motor beyni cevap vermedi", clock())
        val pending = read(0x07) ?: emptyList()
        val permanent = read(0x0A)
        val m = ElmParser.mode01(ElmParser.frames(request("0101", 1000)), setOf(0x01))[0x01]
        val (done, total) = m?.let { readiness(it) } ?: (null to null)
        return DtcReport(stored, pending, permanent, m?.let { it[0].toInt() and 0x80 != 0 }, done, total, cleared, null, clock())
    }

    /** null: cevap yok ya da servis desteklenmiyor; boş liste: kod yok */
    private fun read(svc: Int): List<String>? {
        val r = request("%02X".format(svc), 5000)
        if (r == ObdEngine.TIMEOUT) return null
        val frames = ElmParser.frames(r)
        if (frames.any { it.isNotEmpty() && (it[0].toInt() and 0xFF) == svc + 0x40 }) return ElmParser.dtcs(frames, svc, isCan)
        // "NO DATA" bazı ECU'larda "kod yok" demek; 7F (olumsuz cevap) servis yok demek
        return if (ElmParser.error(r) == "NO DATA" && svc != 0x0A) emptyList() else null
    }

    companion object {
        /**
         * 0101 cevabından hazırlık testleri: B baytı bit 0-2 sürekli testler (destek), bit 4-6 tamamlanmadı;
         * C baytı aralıklı testler (destek), D baytı tamamlanmadı. Dizelde (B bit 3) C/D anlamı farklı ama sayım aynı.
         */
        fun readiness(b: ByteArray): Pair<Int, Int>? {
            if (b.size < 4) return null
            val bb = b[1].toInt() and 0xFF
            val c = b[2].toInt() and 0xFF
            val d = b[3].toInt() and 0xFF
            var total = 0; var done = 0
            for (i in 0..2) if (bb and (1 shl i) != 0) { total++; if (bb and (1 shl (i + 4)) == 0) done++ }
            for (i in 0..7) if (c and (1 shl i) != 0) { total++; if (d and (1 shl i) == 0) done++ }
            return done to total
        }
    }
}
