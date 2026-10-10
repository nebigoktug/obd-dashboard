package com.nebi.obdbridge.obd

/** ELM327 cevap metnini (ATE0 ATS0 ATH0 ATL0 varsayımıyla) baytlara çeviren saf fonksiyonlar. */
object ElmParser {

    private val ERRORS = listOf(
        "NO DATA", "CAN ERROR", "UNABLE TO CONNECT", "BUS INIT", "BUS ERROR", "BUS BUSY",
        "STOPPED", "BUFFER FULL", "DATA ERROR", "FB ERROR", "LV RESET", "ERR", "?",
    )

    /** Cevaptaki ilk hata anahtar kelimesi, yoksa null. */
    fun error(text: String): String? {
        for (raw in text.lineSequence()) {
            val l = raw.trim().uppercase()
            if (l.isEmpty() || l.startsWith("SEARCHING")) continue
            ERRORS.firstOrNull { l.startsWith(it) }?.let { return it }
        }
        return null
    }

    /**
     * Cevabı ECU başına bayt dizilerine böler. ISO-TP çok çerçeveli cevap
     * ("014" / "0: ..." / "1: ...") tek dizi olarak birleştirilir.
     */
    fun frames(text: String): List<ByteArray> {
        val lines = text.lineSequence()
            .map { it.trim().replace(" ", "") }
            .filter { it.isNotEmpty() && !it.uppercase().startsWith("SEARCHING") }
            .toList()
        if (lines.any { ':' in it }) {
            var total = -1
            val sb = StringBuilder()
            for (l in lines) {
                if (':' in l) sb.append(l.substringAfter(':'))
                else if (l.length == 3) total = l.toIntOrNull(16) ?: -1
            }
            val b = hex(sb.toString()) ?: return emptyList()
            return listOf(if (total in 1..b.size) b.copyOf(total) else b)
        }
        return lines.mapNotNull { hex(it) }
    }

    /** "41 PID veri PID veri..." → {pid: veri}. Yalnızca [wanted] içindekiler döner. */
    fun mode01(frames: List<ByteArray>, wanted: Set<Int>): Map<Int, ByteArray> {
        val res = HashMap<Int, ByteArray>()
        for (b in frames) {
            if (b.isEmpty() || (b[0].toInt() and 0xFF) != 0x41) continue
            var i = 1
            while (i < b.size) {
                val pid = b[i].toInt() and 0xFF
                // Bazı ECU'lar çoklu cevapta servis baytını (41) tekrarlıyor: istenmediyse atla
                if (pid == 0x41 && pid !in wanted) { i++; continue }
                val n = Pids.len(pid)
                if (i + 1 + n > b.size) break
                if (pid in wanted && pid !in res) res[pid] = b.copyOfRange(i + 1, i + 1 + n)
                i += 1 + n
                // Ucuz klonlar kısa cevabın sonuna önceki mesajdan kalan baytları ekliyor
                // (ör. 410C0C5C F00B39): istenenler tamamlanınca gerisini okuma
                if (res.size == wanted.size) return res
            }
        }
        return res
    }

    /** 0100/0120/... bit maskelerinden desteklenen PID kümesi. */
    fun supportedFromMask(base: Int, mask: ByteArray): Set<Int> {
        val bits = mask.take(4).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        return (0 until 32).filter { bits and (1L shl (31 - it)) != 0L }.map { base + it + 1 }.toSet()
    }

    /** Mode 03/07 cevabından arıza kodları (P0123 biçiminde). CAN'da servis baytından sonra sayı baytı gelir. */
    fun dtcs(frames: List<ByteArray>, service: Int, isCan: Boolean): List<String> {
        val out = LinkedHashSet<String>()
        for (b in frames) {
            if (b.isEmpty() || (b[0].toInt() and 0xFF) != service + 0x40) continue
            var i = if (isCan) 2 else 1
            // CAN'da ikinci bayt kod sayısı; sonrası klonun eklediği çöp olabilir
            val end = if (isCan && b.size > 1) minOf(b.size, 2 + 2 * (b[1].toInt() and 0xFF)) else b.size
            while (i + 1 < end) {
                val a = b[i].toInt() and 0xFF
                val c = b[i + 1].toInt() and 0xFF
                i += 2
                if (a == 0 && c == 0) continue
                val letter = "PCBU"[a shr 6]
                out += "%c%d%X%02X".format(letter, (a shr 4) and 0x3, a and 0xF, c)
            }
        }
        return out.toList()
    }

    /** Hızlı mod eki ("010C1"): yalnız cevap tek CAN çerçevesine sığıyorsa (≤ 7 bayt).
     *  Çok çerçeveli cevapta ek kullanmayız: v2.1 klonu "010C0D042" gibi isteklerde kilitlendi. */
    fun countSuffix(pids: List<Int>): String =
        if (1 + pids.sumOf { 1 + Pids.len(it) } <= 7) "1" else ""

    /** CAN'da motor ECU'sunun fiziksel adresi ve fonksiyonel (genel) adres; CAN değilse null.
     *  11-bit: 7E0 / 7DF.  29-bit: 18DA10F1 / 18DB33F1 (ATSH alt 3 baytı alır, öncelik 18 varsayılan). */
    fun ecmHeaders(protocol: String?): Pair<String, String>? = when (protocol) {
        "6", "8" -> "7E0" to "7DF"
        "7", "9" -> "DA10F1" to "DB33F1"
        else -> null
    }

    /** Çoklu PID cevabının kaç CAN çerçevesi tutacağı (tek: ≤7 bayt; sonra 6 + 7'şer). */
    fun responseFrames(pids: List<Int>): Int {
        val payload = 1 + pids.sumOf { 1 + Pids.len(it) }
        return if (payload <= 7) 1 else 1 + (payload - 6 + 6) / 7
    }

    /**
     * ATH1 açıkken gelen cevaplardan, cevap veren ECU'ların fiziksel istek başlıkları.
     * 29-bit: cevap 18DAF1xx → istek DAxxF1.  11-bit: cevap 7E8..7EF → istek 7E0..7E7.
     * Adresi tahmin etmek yerine aracın kendisinden öğreniriz (S40'ta DA10F1 cevapsız kaldı).
     */
    fun ecuRequestHeaders(text: String, protocol: String?): List<String> {
        val out = LinkedHashSet<String>()
        for (raw in text.lineSequence()) {
            val l = raw.trim().replace(" ", "").uppercase()
            when (protocol) {
                "7", "9" -> if (l.length >= 10 && l.startsWith("18DAF1")) out += "DA" + l.substring(6, 8) + "F1"
                "6", "8" -> l.take(3).toIntOrNull(16)?.takeIf { it in 0x7E8..0x7EF }?.let { out += "%03X".format(it - 8) }
            }
        }
        return out.toList()
    }

    /** Mode 09 PID 02 cevabından VIN. Bazı araçlar (Volvo P1) VIN'i ters gönderiyor: düzeltilir. */
    fun vin(frames: List<ByteArray>): String? {
        val b = frames.firstOrNull { it.size > 3 && (it[0].toInt() and 0xFF) == 0x49 && it[1].toInt() == 0x02 } ?: return null
        val s = String(b.copyOfRange(3, b.size).map { (it.toInt() and 0xFF).toChar() }.toCharArray())
            .filter { it.isLetterOrDigit() }
        if (s.length != 17) return s.ifEmpty { null }
        // Geçerli VIN üretici koduyla (harf) başlar ve sıra numarasıyla (rakam) biter
        return if (!s[0].isLetter() && s.last().isLetter()) s.reversed() else s
    }

    /** "13.1V" → 13.1 */
    fun volts(text: String): Double? =
        Regex("""(\d+(?:\.\d+)?)\s*V""").find(text)?.groupValues?.get(1)?.toDoubleOrNull()

    private fun hex(s: String): ByteArray? {
        if (s.length % 2 != 0 || s.isEmpty()) return null
        return try {
            ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            null
        }
    }
}
