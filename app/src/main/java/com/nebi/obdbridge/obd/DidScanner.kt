package com.nebi.obdbridge.obd

/**
 * "Gizli değer taraması": motor ECU'sunun üreticiye özel değerlerini (UDS 0x22 DID, KWP 0x21 yerel kimlik) bulur.
 *
 * S40 1.6D ECU'su genel adresten (DB33F1) 0x22'ye "7F 22 31" (bu numara yok) diyor: servis var, numaralar
 * bilinmiyor (2026-10-09). Burada aday aralıklar taranır, veri dönen numaralar kaydedilir; anlamları sonra
 * bilinen değerlerle (devir, sıcaklık, yük) karşılaştırılarak çözülür. Hedef: enjeksiyon miktarı → yakıt.
 *
 * Yalnız okuma servisleri ve oturum değişimi (0x10, yalnız 03/C0 = genişletilmiş tanı) kullanılır.
 * v1 bir istekte 3 DID soruyordu; bu ECU çoklu DID isteğine hiç cevap vermiyor (2026-10-10, tüm üçlüler
 * SILENT), tek DID'e ise "7F 22 31" ile hızlı cevap veriyor. v2 her DID'i tek tek sorar (~170 ms).
 * Kaldığı yerden devam eder: [startIndex] (DID sırası) ve dönen "sonraki" ayarlarda saklanır.
 */
class DidScanner(
    private val engine: ObdEngine,
    private val clock: () -> Long,
    private val protocol: String?,
    private val progress: (String) -> Unit,
    private val cancelled: () -> Boolean,
    private val startIndex: Int,
    private val ids: IntArray = DEFAULT_IDS,
) {
    private val out = StringBuilder()
    private val t0 = clock()
    private fun t() = clock() - t0
    private fun line(s: String) { out.append(s).append('\n') }
    private var found = 0
    private var deadInRow = 0

    private class Abort(msg: String) : Exception(msg)

    /** @return (kayıt metni, sonraki üçlü; tarama bittiyse -1) */
    fun run(): Pair<String, Int> {
        line("# OBD Köprü gizli değer taraması v2")
        line("# protocol $protocol start $startIndex total ${ids.size}")
        var next = startIndex
        try {
            setup()
            if (startIndex == 0) {
                scanLocalIds("L21")
                scanLocalIdsInSessions()
            }
            next = scanDids()
        } catch (e: Abort) {
            line("FATAL ${t()} ${e.message}")
        } catch (e: Exception) {
            line("FATAL ${t()} ${e.message}")
        }
        line("END ${t()} next=$next found=$found")
        return out.toString() to next
    }

    private fun req(c: String, timeoutMs: Long = 1500): String {
        val r = engine.request(c, timeoutMs)
        if (r == ObdEngine.TIMEOUT || ElmParser.error(r) == "NO DATA") {
            if (r == ObdEngine.TIMEOUT) engine.interrupt()
            if (++deadInRow >= 6) {
                // ECU bu aralığı sessizce yok sayıyor olabilir: hâlâ cevap veriyor mu?
                val alive = ElmParser.mode01(ElmParser.frames(engine.request("0100", 2000)), setOf(0x00)).isNotEmpty()
                if (!alive) throw Abort("ECU/adaptör cevap vermiyor (kontak kapandı mı?)")
                line("SILENT ${t()} $c sonrası 6 cevapsız, ECU canlı: devam")
                deadInRow = 0
            }
        } else deadInRow = 0
        return r
    }

    private fun setup() {
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1")) engine.request(c, 1000)
        // Fiziksel adres bu klonda çalışmıyor; genel adres çalışıyor (2026-10-09)
        ElmParser.ecmHeaders(protocol)?.let { (_, functional) -> engine.request("ATSH$functional", 1000) }
        engine.request("ATST19", 1000)  // cevapsız isteklerde ~100 ms bekle
    }

    /** KWP2000 0x21 (PSA'nın kullandığı yerel kimlikler): 256 numara, ~40 sn. */
    private fun scanLocalIds(tag: String) {
        progress("Tarama: eski tip numaralar (0x21${if (tag != "L21") ", $tag" else ""})")
        for (lid in 0..0xFF) {
            if (cancelled()) return
            val r = req("21%02X".format(lid))
            val f = ElmParser.frames(r).firstOrNull()
            when {
                f != null && f.size >= 2 && (f[0].toInt() and 0xFF) == 0x61 -> { found++; record(tag, lid, r) }
                f != null && f.size >= 3 && (f[0].toInt() and 0xFF) == 0x7F && (f[2].toInt() and 0xFF) == 0x11 -> {
                    line("NOSERVICE ${t()} 21 servisi desteklenmiyor"); return   // 0x11: servis yok, gerisini sorma
                }
                f != null && f.size >= 3 && (f[0].toInt() and 0xFF) == 0x7F && (f[2].toInt() and 0xFF) != 0x31 ->
                    line("N${tag.drop(1)} ${t()} %02X ${r.oneLine()}".format(lid))
            }
        }
    }

    /**
     * 21 B1 varsayılan oturumda "7F 21 22" (koşullar uygun değil) dedi: bazı bloklar genişletilmiş tanı
     * oturumunda açılıyor olabilir. Oturum kabul edilirse 0x21 bu oturumda yeniden taranır (~45 sn).
     * İstekler sık olduğu için oturum zaman aşımına düşmez; sonunda varsayılana dönülür.
     */
    private fun scanLocalIdsInSessions() {
        for ((sess, tag) in listOf("1003" to "L21S03", "10C0" to "L21SC0")) {
            if (cancelled()) break
            val r = req(sess)
            val f = ElmParser.frames(r).firstOrNull()
            line("SESSION ${t()} $sess ${r.oneLine()}")
            if (f == null || (f[0].toInt() and 0xFF) != 0x50) continue
            scanLocalIds(tag)
            defaultSession()
        }
    }

    private fun defaultSession() {
        req("1081"); req("1001")  // KWP ve UDS varsayılan oturumu; desteklenmeyen sessizce reddedilir
    }

    private fun scanDids(): Int {
        val total = ids.size
        var i = startIndex
        while (i < total) {
            if (cancelled()) return i
            val did = ids[i]
            val r = req("22%04X".format(did))
            val f = ElmParser.frames(r).firstOrNull()
            val head = f?.let { (it[0].toInt() and 0xFF) } ?: -1
            when {
                head == 0x62 && f!!.size >= 3 && ((f[1].toInt() and 0xFF) shl 8 or (f[2].toInt() and 0xFF)) == did -> {
                    found++
                    record("D22", did, r)
                }
                head == 0x7F && f!!.size >= 3 && (f[2].toInt() and 0xFF) != 0x31 ->
                    line("N22 ${t()} %04X nrc=%02X".format(did, f[2].toInt() and 0xFF))
            }
            i++
            if (i % 100 == 0) progress("Tarama %${i * 100 / total} · ${"%04X".format(did)} · bulunan $found")
        }
        return -1
    }

    /** Bulunanı kaydet; hangi ECU'nun cevap verdiğini görmek için başlıklı bir kez daha sor. */
    private fun record(tag: String, id: Int, r: String) {
        engine.request("ATH1", 1000)
        val h = engine.request((if (tag.startsWith("L21")) "21%02X" else "22%04X").format(id), 1500)
        engine.request("ATH0", 1000)
        line("$tag ${t()} ${if (tag.startsWith("L21")) "%02X".format(id) else "%04X".format(id)} ${r.oneLine()} | ${h.oneLine()}")
    }

    private fun String.oneLine() = replace('\n', '|')

    companion object {
        /** Olasılık sırasıyla: Volvo, Ford (P1 platformu ortak), düşük aralık, diğerleri. */
        val DEFAULT_IDS: IntArray = (
            (0xD000..0xDFFF) + (0x1000..0x1FFF) + (0x0000..0x0FFF) + (0xE000..0xEFFF) +
                (0x2000..0x2FFF) + (0xF000..0xF3FF) + (0xF500..0xFFFF)
            ).toIntArray()
    }
}
