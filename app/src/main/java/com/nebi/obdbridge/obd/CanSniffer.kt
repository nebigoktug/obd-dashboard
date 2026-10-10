package com.nebi.obdbridge.obd

/**
 * CAN keşif kaydı: araçtaki modüllerin yayınladığı ham CAN mesajlarını pasif olarak dinler.
 * Amaç kalıcı veri kaynağı değil, hangi kimliğin hangi baytının ne taşıdığını bir kez çözmek.
 *
 * Ucuz klonun seri hattı tüm trafiği taşıyamaz (taşınca "BUFFER FULL" deyip dinlemeyi bırakır).
 * Bu yüzden bilerek darboğaz kurulur: kimliğin son hanesine göre 16 dilim, her biri sırayla kısa süre
 * dinlenir; tampon taşmaz, bir turda tüm kimlikler görülür. Kaçan mesajlar keşif için önemsiz.
 *
 * Araç dururken yapılır; kullanıcıya adım adım talimat verilir. Her adımın başında ve sonunda OBD ile
 * devir/hız/yük okunur, laptopta CAN baytları bu bilinen değerlerle eşleştirilir (tools/can_analyze.py).
 * Araca yalnız AT ayar komutları ve mode 01 okuma gönderilir.
 */
class CanSniffer(
    private val engine: ObdEngine,
    private val clock: () -> Long,
    private val protocol: String?,
    private val progress: (String) -> Unit,
    private val onStep: (Int) -> Unit,
    private val steps: List<Pair<String, Long>> = STEPS,
    private val sliceMs: Long = 1500,
    private val burstMs: Long = 3000,
    /** Sürüş modu: her dilimden önce OBD referansı (değerler sürekli değiştiği için) */
    private val refEverySlice: Boolean = false,
    /** Kullanıcı kaydı erken bitirdi mi */
    private val cancelled: () -> Boolean = { false },
) {
    private val out = StringBuilder()
    private val t0 = clock()
    private fun t() = clock() - t0
    private fun line(s: String) { out.append(s).append('\n') }

    private val is29 = protocol == "7" || protocol == "9"

    fun run(): String {
        line("# OBD Köprü CAN keşif kaydı v1")
        line("# protocol $protocol")
        try {
            body()
        } catch (e: Exception) {
            line("FATAL ${t()} ${e.message}")
        }
        // Filtreleri ve biçim ayarlarını sıfırla; motor ardından yeniden kurar. BT kopmuşsa yazma hata verir:
        // kayıt yine de döndürülsün diye yutulur
        try { engine.request("ATD", 2000) } catch (e: Exception) { line("FATAL ${t()} ATD: ${e.message}") }
        line("END ${t()}")
        return out.toString()
    }

    private fun body() {
        if (protocol !in setOf("6", "7", "8", "9")) {
            line("FATAL 0 CAN protokolü değil, dinleme yapılamaz")
            return
        }
        // 1) Filtresiz kısa patlama: hattın yoğunluğunu ve adaptörün ne kadar dayandığını ölç
        progress("CAN kaydı: hat yoğunluğu ölçülüyor")
        sniffSetup()
        var n = 0
        val bt = t()
        val reason = engine.monitor("ATMA", burstMs) { l -> if (n < 400) frame(l, -1); n++ }
        line("BURST ${t()} lines=$n ms=${t() - bt} reason=$reason")
        var frames = 0          // toplam yakalanan CAN mesajı
        var silentSlices = 0    // hiç mesaj gelmeyen dilim sayısı
        var deadInRow = 0       // art arda cevapsız kalan (kilitlenmiş adaptör)
        // Not: boş satır ("\r") ELM'de son komutu (ATMA) tekrarlatır; temizlik için yalnız interrupt kullanılır
        engine.interrupt()

        // 2) Talimatlı adımlar, her adımda dilimler sırayla dinlenir
        var slice = 0
        for ((i, step) in steps.withIndex()) {
            val (text, dur) = step
            onStep(i)
            reference("REF_START", i)
            sniffSetup()
            line("STEP ${t()} $i $text")
            val stepEnd = clock() + dur
            while (clock() < stepEnd && !cancelled()) {
                val left = (stepEnd - clock()) / 1000
                progress(if (refEverySlice) "$text · ${left / 60} dk ${left % 60} sn kaldı"
                         else "Adım ${i + 1}/${steps.size}: $text · $left sn")
                if (refEverySlice) { if (!reference("REF", i)) deadInRow++; sniffSetup() }
                setSlice(slice)
                var cnt = 0
                val r = engine.monitor("ATMA", sliceMs) { l -> if (frame(l, slice)) cnt++ }
                line("SLICE ${t()} $slice $r $cnt")
                if (r == "NO_PROMPT") { engine.interrupt(); deadInRow++ } else deadInRow = 0
                frames += cnt
                if (cnt == 0) silentSlices++
                slice = (slice + 1) % 16
                // Adaptör kilitlendi: kaydı boşuna sürdürme (motor ardından BT'yi yeniden kurar)
                if (deadInRow >= 2) {
                    line("FATAL ${t()} adaptör cevap vermiyor")
                    progress("CAN kaydı durdu: adaptör cevap vermiyor")
                    return
                }
                // Bir tam turda (16 dilim) hiç mesaj yoksa sokette yayın trafiği yok demektir
                if (frames == 0 && silentSlices >= 16) {
                    line("NOTRAFFIC ${t()} 16 dilimde hiç CAN mesajı yok (OBD soketinde yayın trafiği görünmüyor)")
                    progress("CAN kaydı durdu: sokette CAN trafiği yok")
                    return
                }
            }
            reference("REF_END", i)
            if (cancelled()) { line("CANCEL ${t()}"); break }
        }
        progress("CAN kaydı bitti")
    }

    /** Başlık açık, boşluksuz, ham (PCI dahil) biçim. */
    private fun sniffSetup() {
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH1", "ATCAF0")) engine.request(c, 1000)
    }

    /** Kimliğin son hanesi [s] olanları geçir (donanım filtresi: tampondan önce süzülür). */
    private fun setSlice(s: Int) {
        val h = "%X".format(s)
        if (is29) {
            engine.request("ATCF0000000$h", 1000)
            engine.request("ATCM0000000F", 1000)
        } else {
            engine.request("ATCF00$h", 1000)
            engine.request("ATCM00F", 1000)
        }
    }

    /** Filtreleri sıfırlayıp OBD ile bilinen değerleri oku (eşleştirme için referans). */
    private fun reference(tag: String, step: Int): Boolean {
        engine.request("ATD", 2000)
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSP${protocol ?: "0"}")) engine.request(c, 1000)
        val r = engine.request("010C0D04", 3000)
        val m = ElmParser.mode01(ElmParser.frames(r), setOf(0x0C, 0x0D, 0x04))
        val rpm = m[0x0C]?.let { ((it[0].toInt() and 0xFF) * 256 + (it[1].toInt() and 0xFF)) / 4.0 }
        val speed = m[0x0D]?.let { it[0].toInt() and 0xFF }
        val load = m[0x04]?.let { (it[0].toInt() and 0xFF) * 100 / 255.0 }
        line("$tag ${t()} $step rpm=$rpm speed=$speed load=$load raw=${r.replace('\n', '|')}")
        return r != ObdEngine.TIMEOUT
    }

    /** "18FF1234" + veri → "F t dilim kimlik veri". Gerçek CAN mesajıysa true. */
    private fun frame(l: String, slice: Int): Boolean {
        val idLen = if (is29) 8 else 3
        if (l.length < idLen || l.any { it !in "0123456789ABCDEF" }) {
            line("X ${t()} $slice $l")  // beklenmeyen satır (SEARCHING, NO DATA vb.)
            return false
        }
        line("F ${t()} $slice ${l.substring(0, idLen)} ${l.substring(idLen)}")
        return true
    }

    companion object {
        /** Araç dururken (manuel vites: boşta, el freni çekili). Her adımda dilim turu en az bir kez döner. */
        val STEPS = listOf(
            "Rölanti (boşta): hiçbir şeye dokunma" to 30_000L,
            "Devri ~2500'de sabit tut" to 30_000L,
            "Gaza 3-4 kez basıp bırak" to 30_000L,
            "Debriyaja 3-4 kez basıp bırak" to 30_000L,
            "Frene 3-4 kez basıp bırak" to 30_000L,
            "Direksiyonu sağa sola çevir" to 30_000L,
        )

        /** Sürüş keşif kaydı: tek adım, normal sürüş; park edince kullanıcı bitirir (en çok 30 dk). */
        val DRIVE = listOf("Sürüş kaydı: normal sür, park edince bitir" to 30 * 60_000L)
    }
}
