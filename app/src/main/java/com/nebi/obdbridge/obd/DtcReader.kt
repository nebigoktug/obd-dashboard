package com.nebi.obdbridge.obd

import com.nebi.obdbridge.t
/**
 * Arıza kodu ekranı için tek seferlik okuma (ve isteğe bağlı silme). Canlı sorgunun arasında,
 * motorun kurduğu ELM ayarlarıyla (ATH0, motor ECU başlığı) çalışır; ayar değiştirmez.
 *
 * Servisler: 03 kayıtlı, 07 bekleyen, 0A kalıcı (silinemez; her araç desteklemez), 0101 MIL + hazırlık testleri,
 * 02 freeze frame (arıza lambasını yakan kodun oluştuğu andaki değerler), 04 silme (yalnız [clear] true ise, kullanıcı onayından sonra).
 */

/** Freeze frame (Mode 02, kare 0). [dtc] null: kayıtlı kare yok. [values]: anahtar → metrik değer (rpm, km/h, °C, %, kPa) */
data class FreezeFrame(val dtc: String?, val values: Map<String, Double>)

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
    /** null: okunamadı / desteklenmiyor */
    val freeze: FreezeFrame? = null,
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
            if (r == ObdEngine.TIMEOUT) return DtcReport(emptyList(), emptyList(), null, null, null, null, false, t("Adaptör cevap vermedi"), clock())
            Thread.sleep(500)  // bazı ECU'lar silmeden hemen sonra meşgul cevap veriyor
        }
        val stored = read(0x03) ?: return DtcReport(emptyList(), emptyList(), null, null, null, null, cleared, t("Motor beyni cevap vermedi"), clock())
        val pending = read(0x07) ?: emptyList()
        val permanent = read(0x0A)
        val m = ElmParser.mode01(ElmParser.frames(request("0101", 1000)), setOf(0x01))[0x01]
        val (done, total) = m?.let { readiness(it) } ?: (null to null)
        val freeze = try { freezeFrame() } catch (e: InterruptedException) { throw e } catch (_: Exception) { null }
        return DtcReport(stored, pending, permanent, m?.let { it[0].toInt() and 0x80 != 0 }, done, total, cleared, null, clock(), freeze)
    }

    /** 0202 kareyi tetikleyen kod; varsa desteklenen PID'ler (020000 maskesi, yoksa listeden tek tek) */
    private fun freezeFrame(): FreezeFrame? {
        val r = request("020200", 2000)
        if (r == ObdEngine.TIMEOUT) return null
        val code = ElmParser.mode02(ElmParser.frames(r), 0x02) ?: return null   // servis yok / cevap yok
        val a = code[0].toInt() and 0xFF; val c = code[1].toInt() and 0xFF
        if (a == 0 && c == 0) return FreezeFrame(null, emptyMap())
        val mask = ElmParser.mode02(ElmParser.frames(request("020000", 2000)), 0x00)
        val supported = mask?.let { ElmParser.supportedFromMask(0, it) }
        val out = LinkedHashMap<String, Double>()
        for (p in FREEZE_PIDS) {
            if (p.key in out || (supported != null && p.pid !in supported)) continue
            val resp = request("02%02X00".format(p.pid), 1000)
            if (resp == ObdEngine.TIMEOUT) break
            ElmParser.mode02(ElmParser.frames(resp), p.pid)?.let { out[p.key] = p.decode(it) }
        }
        return FreezeFrame(ElmParser.dtcCode(a, c), out)
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
        private fun u8(b: ByteArray) = (b[0].toInt() and 0xFF).toDouble()
        private fun trim(b: ByteArray) = (u8(b) - 128) * 100 / 128

        /** Freeze frame'de sorulanlar (ekrandaki sırayla). Mode 01 tanımları + yakıt düzeltmeleri, gaz, yakıt basıncı */
        val FREEZE_PIDS: List<PidDef> = listOfNotNull(
            Pids.ALL.first { it.key == "rpm" }, Pids.ALL.first { it.key == "speed" }, Pids.ALL.first { it.key == "coolant" },
            Pids.ALL.first { it.key == "load" }, Pids.ALL.first { it.key == "map" }, Pids.ALL.first { it.key == "iat" },
            PidDef("stft", 0x06, 1, "%", Tier.C) { trim(it) },
            PidDef("ltft", 0x07, 1, "%", Tier.C) { trim(it) },
            PidDef("throttle", 0x11, 1, "%", Tier.C) { u8(it) * 100 / 255 },
            Pids.ALL.first { it.key == "rail" },
            Pids.ALL.first { it.key == "maf" },
        )

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
