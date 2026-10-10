package com.nebi.obdbridge.obd

/**
 * "Araç testi": adaptörün ve aracın neyi desteklediğini, ne kadar hızlı cevap verdiğini ölçer.
 * Araca yalnız OKUMA istekleri gönderir (mode 01, 03, 07, 09, 22). Arıza kodu silme (04) YOK.
 * Sonuç JSON metni olarak döner; motor bunu dosyaya yazdırır.
 */
class VehicleProbe(
    private val request: (String, Long) -> String,
    private val clock: () -> Long,
    private val progress: (String) -> Unit,
    /** Kilitlenen komutu kesmek için (motorun interrupt'ı) */
    private val interrupt: () -> Unit = {},
    private val reps: Int = 30,
) {
    private val rep = LinkedHashMap<String, Any?>()
    private val log = ArrayList<Any?>()
    private var timeoutsInRow = 0

    private class Abort(msg: String) : Exception(msg)

    private fun cmd(c: String, timeoutMs: Long = 3000): Pair<String, Long> {
        val t0 = clock()
        val r = request(c, timeoutMs)
        val ms = clock() - t0
        if (log.size < 2000) log.add(listOf(c, r, ms))
        if (r == ObdEngine.TIMEOUT) {
            interrupt()
            if (++timeoutsInRow >= 3) throw Abort("adaptör 3 kez cevap vermedi")
        } else timeoutsInRow = 0
        return r to ms
    }

    fun run(): String {
        rep["time_ms"] = clock()
        try {
            body()
        } catch (e: Abort) {
            rep["fatal"] = e.message
        }
        rep["log"] = log
        return Json.write(rep)
    }

    private fun body() {
        progress("Araç testi: adaptör")
        cmd("ATZ", 5000)
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0")) cmd(c)
        for (c in listOf("ATI", "AT@1", "ATRV")) rep[c] = cmd(c).first

        progress("Araç testi: protokol aranıyor")
        val (first, firstMs) = cmd("0100", 15000)
        rep["first_0100"] = mapOf("text" to first, "ms" to firstMs)
        val mask0 = ElmParser.mode01(ElmParser.frames(first), setOf(0x00))[0x00]
        if (ElmParser.error(first) != null || mask0 == null) {
            rep["fatal"] = "0100 başarısız: $first (kontak açık mı?)"
            return
        }
        rep["protocol"] = cmd("ATDP").first
        val protoNum = cmd("ATDPN").first.trim().removePrefix("A")
        rep["protocol_num"] = protoNum
        val isCan = protoNum in setOf("6", "7", "8", "9")

        progress("Araç testi: desteklenen PID'ler")
        val supported = HashSet(ElmParser.supportedFromMask(0, mask0))
        var base = 0x20
        while (base < 0xE0 && base in supported) {
            val m = ElmParser.mode01(ElmParser.frames(cmd("01%02X".format(base)).first), setOf(base))[base] ?: break
            supported += ElmParser.supportedFromMask(base, m)
            base += 0x20
        }
        rep["supported_pids"] = supported.sorted().map { "%02X".format(it) }

        progress("Araç testi: anlık değerler")
        val values = LinkedHashMap<String, Any?>()
        for (p in Pids.ALL.distinctBy { it.pid }.filter { it.pid in supported }) {
            val b = ElmParser.mode01(ElmParser.frames(cmd("01%02X".format(p.pid)).first), setOf(p.pid))[p.pid]
            if (b != null) values["%02X %s".format(p.pid, p.key)] = Math.round(p.decode(b) * 100) / 100.0
        }
        rep["values"] = values

        progress("Araç testi: araç bilgisi ve arıza kodları")
        val vinRaw = cmd("0902", 5000).first
        rep["raw_0902"] = vinRaw
        rep["vin"] = ElmParser.vin(ElmParser.frames(vinRaw))
        rep["raw_0101"] = cmd("0101").first
        for (svc in listOf(0x03, 0x07)) {
            val r = cmd("%02X".format(svc), 5000).first
            rep["raw_%02X".format(svc)] = r
            rep["dtc_%02X".format(svc)] = ElmParser.dtcs(ElmParser.frames(r), svc, isCan)
        }

        progress("Araç testi: hız ölçümü")
        val fast = listOf(0x0C, 0x0D, 0x49, 0x11, 0x04, 0x0B, 0x05, 0x0F, 0x23).filter { it in supported }.ifEmpty { listOf(0x0C) }
        val tests = ArrayList<Pair<String, List<Int>>>()
        tests += "010C" to listOf(0x0C)
        tests += "010C1" to listOf(0x0C)
        for (n in sortedSetOf(2, 3, minOf(6, fast.size))) {
            if (n < 2 || fast.size < n) continue
            val ids = fast.take(n)
            if (ElmParser.responseFrames(ids) > 2) continue  // v2.1 klonu 3+ çerçevede kilitleniyor
            val body = "01" + ids.joinToString("") { "%02X".format(it) }
            tests += body to ids
            // Cevap sayısı eki yalnız tek çerçeveli cevapta (çok çerçevelide v2.1 klonu kilitlendi)
            ElmParser.countSuffix(ids).takeIf { it.isNotEmpty() }?.let { tests += body + it to ids }
        }
        val timing = ArrayList<Any?>()
        for ((c, ids) in tests) timing += timeIt(c, c, ids)
        val multi = fast.take(minOf(3, fast.size))
        val multiCmd = "01" + multi.joinToString("") { "%02X".format(it) }
        cmd("ATAT2")
        timing += timeIt("ATAT2 010C1", "010C1", listOf(0x0C))
        timing += timeIt("ATAT2 $multiCmd", multiCmd, multi)
        cmd("ATAT1")
        // Cevap veren ECU adresleri (ATH1 ile başlıktan okunur)
        var physicalEcm: String? = null
        ElmParser.ecmHeaders(protoNum)?.let { (guess, _) ->
            cmd("ATH1")
            val hr = cmd("0100", 3000).first
            cmd("ATH0")
            val found = ElmParser.ecuRequestHeaders(hr, protoNum)
            rep["raw_ath1_0100"] = hr
            rep["ecu_headers"] = found
            physicalEcm = found.firstOrNull() ?: guess
        }
        // Fonksiyonel adres yerine doğrudan motor ECU'su: başka ECU beklenmediği için daha hızlı olabilir.
        // v2.1 klonunda "ATSHDA10F1" tek başına NO DATA verdi (adres doğru olduğu halde, 2026-10-09):
        // birkaç kurulum biçimi denenir, çalışan ilk biçim zamanlama ve Volvo DID'leri için kullanılır.
        var working: List<String>? = null
        var functionalHdr: String? = null
        ElmParser.ecmHeaders(protoNum)?.let { (_, functional) ->
            functionalHdr = functional
            val physical = physicalEcm!!
            val is29 = physical.length == 6
            val reply = if (is29) "18DAF1" + physical.substring(2, 4) else "%03X".format(physical.toInt(16) + 8)
            val variants = linkedMapOf("ATSH$physical" to listOf("ATSH$physical"))
            if (is29) {
                variants["ATCP18 ATSH$physical"] = listOf("ATCP18", "ATSH$physical")
                variants["ATSH18$physical"] = listOf("ATSH18$physical")
            }
            variants["ATSH$physical ATCRA$reply"] = listOf("ATSH$physical", "ATCRA$reply")
            val results = LinkedHashMap<String, Any?>()
            for ((label, setup) in variants) {
                val replies = setup.map { cmd(it).first }
                var ok = 0
                var sample = ""
                repeat(3) {
                    val r = cmd("010C", 2000).first
                    sample = r
                    if (ElmParser.mode01(ElmParser.frames(r), setOf(0x0C)).isNotEmpty()) ok++
                }
                results[label] = mapOf("setup_replies" to replies, "ok" to ok, "sample" to sample)
                // Varsayılana dön: öncelik 18, otomatik alıcı filtresi, genel adres
                for (c in listOf("ATCP18", "ATAR", "ATSH$functional")) cmd(c)
                if (ok > 0 && working == null) working = setup
            }
            rep["header_variants"] = results
            working?.let { setup ->
                setup.forEach { cmd(it) }
                val name = setup.joinToString(" ")
                timing += timeIt("$name 010C1", "010C1", listOf(0x0C))
                timing += timeIt("$name $multiCmd", multiCmd, multi)
                cmd("ATAT2")
                timing += timeIt("$name ATAT2 $multiCmd", multiCmd, multi)
                cmd("ATAT1")
                for (c in listOf("ATCP18", "ATAR", "ATSH$functional")) cmd(c)
            }
        }
        rep["timing"] = timing

        // Volvo DID'leri: çalışan fiziksel adresle, yoksa genel (fonksiyonel) adresle
        val didSetup = working ?: functionalHdr?.let { listOf("ATSH$it") }
        didSetup?.let { setup ->
            progress("Araç testi: Volvo motor değerleri")
            setup.forEach { cmd(it) }
            val physical = setup.joinToString(" ")
            cmd("ATST32")  // cevapsız DID'de bekleme ~200 ms ile sınırlı
            val dids = LinkedHashMap<String, Any?>()
            for ((did, name) in VOLVO_DIDS) {
                val (r, ms) = cmd("22$did")
                val f = ElmParser.frames(r).firstOrNull()
                val ok = f != null && f.size >= 3 && hex(f.copyOf(3)) == "62$did"
                dids[did] = mapOf("name" to name, "ok" to ok, "raw" to r, "ms" to ms,
                    "data" to if (ok) hex(f!!.copyOfRange(3, f.size)) else null)
            }
            rep["volvo_dids"] = dids
            rep["volvo_header"] = physical
        }
        cmd("ATD")  // varsayılanlara dön; motor ardından kendini yeniden kurar
    }

    /** Komutu [reps] kez gönderir; süre istatistiği ve doğru cevap sayısı. */
    private fun timeIt(label: String, c: String, ids: List<Int>): Map<String, Any?> {
        val times = ArrayList<Long>()
        var ok = 0
        var sample = ""
        repeat(reps) {
            val (r, ms) = cmd(c)
            sample = r
            if (r.trim() == "?") return mapOf("cmd" to label, "supported" to false, "sample" to r)
            if (r == ObdEngine.TIMEOUT) return mapOf("cmd" to label, "supported" to false, "timeout" to true, "ok" to ok)
            times += ms
            if (ElmParser.error(r) == null && ElmParser.mode01(ElmParser.frames(r), ids.toSet()).size == ids.size) ok++
        }
        times.sort()
        return mapOf(
            "cmd" to label, "npid" to ids.size, "supported" to (ok > 0), "ok" to ok, "reps" to reps,
            "median_ms" to times[times.size / 2], "p95_ms" to times[(times.size * 95 / 100).coerceAtMost(times.size - 1)],
            "min_ms" to times.first(), "sample" to sample,
        )
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }

    companion object {
        /** OBDb/Volvo'dan (CC BY-SA 4.0) dizel için ilginç motor ECM DID'leri. S40 1.6D'de çalışıp çalışmadığı bilinmiyor. */
        val VOLVO_DIDS = linkedMapOf(
            "033E" to "Turbo sonrası basınç", "0341" to "Turbo hava sıcaklığı", "D916" to "Hava akışı",
            "D91F" to "İstenen hava kütlesi", "D941" to "Turbo basıncı", "D96C" to "EGR doluluk",
            "D99C" to "Yakıt sıcaklığı", "D9A0" to "Kızdırma bujileri", "D9DC" to "Yağ sıcaklığı",
            "D9E4" to "İstenen turbo basıncı", "D9EE" to "Yakıt basınç valfi", "D9F2" to "DPF is miktarı",
            "D9F3" to "DPF fark basıncı", "D9F9" to "DPF sıcaklığı", "D9FF" to "Nominal yakıt basıncı",
            "DA40" to "Vites", "DAC8" to "DPF basıncı", "DC00" to "Turbo kontrol valfi", "DC01" to "EGR valf komutu",
            "DC10" to "Wastegate", "DD01" to "Kilometre sayacı", "EF4E" to "Yağ basıncı", "F423" to "Yakıt basıncı",
            "F42F" to "Yakıt seviyesi", "F449" to "Gaz pedalı",
        )
    }
}

/** Bağımlılıksız küçük JSON yazıcı (org.json birim testlerinde çalışmıyor). */
object Json {
    fun write(v: Any?): String = StringBuilder().also { append(it, v, 0) }.toString()

    private fun append(sb: StringBuilder, v: Any?, depth: Int) {
        when (v) {
            null -> sb.append("null")
            is String -> str(sb, v)
            is Number, is Boolean -> sb.append(v.toString())
            is Map<*, *> -> {
                sb.append('{')
                v.entries.forEachIndexed { i, (k, x) ->
                    if (i > 0) sb.append(',')
                    if (depth == 0) sb.append("\n ")
                    str(sb, k.toString()); sb.append(':'); append(sb, x, depth + 1)
                }
                if (depth == 0) sb.append('\n')
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                v.forEachIndexed { i, x ->
                    if (i > 0) sb.append(',')
                    if (depth == 0) sb.append("\n ")
                    append(sb, x, depth + 1)
                }
                sb.append(']')
            }
            else -> str(sb, v.toString())
        }
    }

    private fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c < ' ' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
        sb.append('"')
    }
}
