package com.nebi.obdbridge.obd

import java.io.IOException

data class Sample(val key: String, val value: Double, val ts: Long)

enum class EngineState { INIT, SEARCHING, LIVE, NO_ECU, PAUSED, PROBING, STOPPED }

/** Sorgu biçimi: çoklu PID mi, cevap sayısı eki ("010C1") var mı. */
enum class QueryMode { MULTI_FAST, MULTI, SINGLE_FAST, SINGLE }

data class EngineStatus(
    val state: EngineState,
    val protocol: String? = null,
    val mode: QueryMode? = null,
    val maxMulti: Int = 1,
    val reqPerSec: Double = 0.0,
    val rpmHz: Double = 0.0,
    val msg: String? = null,
)

interface EngineListener {
    fun onSamples(samples: List<Sample>)
    fun onStatus(status: EngineStatus)
}

/** Adaptör art arda cevap vermedi: BT bağlantısı ölmüş sayılır, çağıran yeniden bağlanmalı. */
class LinkLostException(msg: String) : IOException(msg)

/**
 * ELM327 üzerinden mode 01 sorgulayan, öncelik katmanlı zamanlayıcı.
 * Android'e bağımlı değil: baytları [write] ile gönderir, gelenleri [onBytes] ile alır.
 * [run] çağıran thread'i bloklar; [stop] ile biter.
 */
class ObdEngine(
    private val write: (ByteArray) -> Unit,
    private val listener: EngineListener,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Hata ayıklama: (komut, cevap, ms) */
    private val trace: ((String, String, Long) -> Unit)? = null,
) {
    @Volatile private var running = true
    /** Başka kaynaktan gelen değerler sorulmaz (ör. hararet ESP32 kutusundan hattan okunuyorsa) */
    @Volatile var skip: (String) -> Boolean = { false }
    @Volatile private var pauseWanted = false
    @Volatile private var paused = false
    @Volatile private var probeWanted: ((String) -> Unit)? = null
    @Volatile private var sniffWanted: ((String) -> Unit)? = null
    /** CAN keşif kaydının adım değişimi (ses/titreşim için) */
    var onSniffStep: ((Int) -> Unit)? = null

    private val lock = Object()
    private val rx = StringBuilder()

    // Bağlantı başına belirlenen durum
    var protocol: String? = null; private set
    private var header: String? = null  // kullanılan ATSH değeri
    private var mode = QueryMode.SINGLE
    private var maxMulti = 1
    private var pids: List<PidDef> = emptyList()
    private val lastReq = HashMap<String, Long>()
    private val misses = HashMap<String, Int>()
    private val suspendedUntil = HashMap<String, Long>()

    private var timeoutsInRow = 0
    private var busErrorsInRow = 0
    private var parseFailsInRow = 0
    private var lastVolt = 0L
    private var lastMil = 0L
    private var aInRow = 0  // tek PID modunda art arda yapılan A isteği

    // Sorun bildirimi için: adaptör kimliği, desteklenen PID'ler, son durum değişimleri
    private var adapterId = "?"
    private var supportedPids: Set<Int> = emptySet()
    private val journal = ArrayDeque<String>()

    /** Sorun bildirimi paketine giren bağlantı özeti (VIN, MAC içermez). */
    fun diag(): String = buildString {
        append("Adaptör: ").append(adapterId).append('\n')
        append("Protokol: ").append(protocol ?: "-").append(" · başlık ").append(header ?: "-").append('\n')
        append("Sorgu modu: ").append(mode).append(" · çoklu ").append(maxMulti).append('\n')
        append("Durum: ").append(status.state).append(" · ").append("%.1f".format(java.util.Locale.US, status.reqPerSec)).append(" istek/sn\n")
        append("Desteklenen PID'ler: ").append(supportedPids.joinToString(" ") { "%02X".format(it) }).append('\n')
        append("Sorgulanan: ").append(pids.joinToString(" ") { it.key }).append('\n')
        append("\nDurum geçmişi:\n")
        synchronized(journal) { journal.forEach { append("  ").append(it).append('\n') } }
    }

    // Hız ölçümü (1 sn pencere)
    private var winStart = 0L
    private var winReq = 0
    private var winRpm = 0
    private var status = EngineStatus(EngineState.INIT)

    // ---------- Giriş / çıkış ----------

    /** BT okuyucusu gelen baytları buraya verir. */
    fun onBytes(buf: ByteArray, n: Int) {
        synchronized(lock) {
            for (i in 0 until n) rx.append((buf[i].toInt() and 0xFF).toChar())
            lock.notifyAll()  // request '>' bekler, monitor satır sonlarını
        }
    }

    /** Komutu gönderir, '>' istemine kadar bekler. Zaman aşımında "<TIMEOUT>" döner. */
    fun request(cmd: String, timeoutMs: Long): String {
        synchronized(lock) { rx.setLength(0) }
        val t0 = clock()
        write((cmd + "\r").toByteArray(Charsets.US_ASCII))
        val deadline = t0 + timeoutMs
        val text = synchronized(lock) {
            while (rx.indexOf(">") < 0) {
                val left = deadline - clock()
                if (left <= 0 || !running) break
                lock.wait(left)
            }
            val end = rx.indexOf(">")
            if (end < 0) TIMEOUT
            else rx.substring(0, end).replace('\r', '\n').trim().also { rx.setLength(0) }
        }
        trace?.invoke(cmd, text, clock() - t0)
        return text
    }

    /**
     * ATMA gibi akış komutları: gelen her satırı [onLine]'a verir, [durationMs] dolunca keser.
     * Bitiş nedeni: "TIME" (biz kestik), "BUFFER FULL" (adaptör taştı), "NO_PROMPT" (kesilmedi), "STOPPED".
     */
    fun monitor(cmd: String, durationMs: Long, onLine: (String) -> Unit): String {
        synchronized(lock) { rx.setLength(0) }
        write((cmd + "\r").toByteArray(Charsets.US_ASCII))
        val end = clock() + durationMs
        var stopAt = 0L
        var reason = "TIME"
        val lines = ArrayList<String>()
        while (true) {
            var prompt = false
            lines.clear()
            synchronized(lock) {
                if (rx.indexOf("\r") < 0 && rx.indexOf(">") < 0) lock.wait(20)
                var i = rx.indexOf("\r")
                while (i >= 0) {
                    lines += rx.substring(0, i).trim()
                    rx.delete(0, i + 1)
                    i = rx.indexOf("\r")
                }
                if (rx.indexOf(">") >= 0) { prompt = true; rx.setLength(0) }
            }
            for (l in lines) when {
                l.isEmpty() -> {}
                l.startsWith("BUFFER FULL") -> reason = "BUFFER FULL"
                l.startsWith("STOPPED") -> if (stopAt == 0L) reason = "STOPPED"
                else -> onLine(l)
            }
            if (prompt) return reason
            val now = clock()
            if (stopAt == 0L && (now >= end || !running)) {
                write(byteArrayOf(' '.code.toByte()))  // herhangi bir karakter izlemeyi durdurur
                stopAt = now
            }
            if (stopAt != 0L && now - stopAt > 1500) return "NO_PROMPT"
        }
    }

    /** Süren komutu keser. Boşluk: meşgulse işi durdurur, boştaysa ELM onu yok sayar
     *  (tek başına "\r" son komutu tekrarlatırdı). */
    internal fun interrupt() {
        synchronized(lock) { rx.setLength(0) }
        write(byteArrayOf(' '.code.toByte()))
        val end = clock() + 300
        synchronized(lock) {
            while (rx.indexOf(">") < 0 && clock() < end) lock.wait(maxOf(1, end - clock()))
            rx.setLength(0)
        }
    }

    fun stop() {
        running = false
        synchronized(lock) { lock.notifyAll() }
    }

    /** Harici ham istemci için adaptörü serbest bırakır; süren istek bitene kadar (en çok [waitMs]) bekler. */
    fun pause(waitMs: Long = 1500) {
        pauseWanted = true
        val end = clock() + waitMs
        while (!paused && running && clock() < end) Thread.sleep(5)
    }

    fun resume() {
        pauseWanted = false
    }

    /** Araç testini sıraya koyar; motor sıradaki turda sorgulamayı bırakıp testi yapar, sonucu [onDone]'a verir. */
    fun requestProbe(onDone: (String) -> Unit) {
        probeWanted = onDone
    }

    /** CAN keşif kaydını sıraya koyar (yalnız dinleme; araca bir şey yazılmaz). Sonuç metin kayıt. */
    fun requestSniff(steps: List<Pair<String, Long>> = CanSniffer.STEPS, drive: Boolean = false, onDone: (String) -> Unit) {
        sniffSteps = steps
        sniffDrive = drive
        sniffCancel = false
        sniffWanted = onDone
    }

    /** Gizli değer taramasını sıraya koyar; [onDone] (kayıt, sonraki DID sırası; bittiyse -1) alır. */
    fun requestScan(startIndex: Int, ids: IntArray = DidScanner.DEFAULT_IDS, onDone: (String, Int) -> Unit) {
        scanCancel = false
        scanStart = startIndex
        scanIds = ids
        scanWanted = onDone
    }

    fun cancelScan() { scanCancel = true }

    /** Arıza kodlarını okumayı (ve [clear] ise önce silmeyi) sıraya koyar; canlı sorgu bir tur bekler. */
    fun requestDtc(clear: Boolean, onDone: (DtcReport) -> Unit) {
        dtcClear = clear
        dtcWanted = onDone
    }

    @Volatile private var dtcWanted: ((DtcReport) -> Unit)? = null
    @Volatile private var dtcClear = false

    /**
     * Blok kaydı: canlı sorgunun arasında her [rawPeriodMs]'de bir bu komutlar (ör. "2101") sorulur,
     * ham cevap [onRaw]'a (komut, cevap, zaman) verilir. Anlamı bilinmeyen üretici bloklarını sürüşte
     * devir/yük/sıcaklıkla karşılaştırıp çözmek için.
     */
    @Volatile var rawPoll: List<String> = emptyList()
    @Volatile var onRaw: ((String, String, Long) -> Unit)? = null
    var rawPeriodMs = 1000L
    private var lastRaw = 0L

    @Volatile private var scanWanted: ((String, Int) -> Unit)? = null
    @Volatile private var scanCancel = false
    @Volatile private var scanStart = 0
    @Volatile private var scanIds = DidScanner.DEFAULT_IDS

    /** Süren CAN kaydını erken bitirir (kayıt o ana kadarki haliyle kaydedilir). */
    fun cancelSniff() { sniffCancel = true }

    @Volatile private var sniffDrive = false
    @Volatile private var sniffCancel = false

    /** Birim testi için: kısa adımlarla kayıt. */
    internal fun requestSniffForTest(out: java.util.concurrent.BlockingQueue<String>) =
        requestSniff(listOf("test 1" to 26_000L, "test 2" to 26_000L)) { out.put(it) }

    @Volatile private var sniffSteps: List<Pair<String, Long>> = CanSniffer.STEPS

    // ---------- Ana döngü ----------

    /** @param cachedProtocol önceki oturumda bulunan ATDPN (ör. "6"), arama süresini kısaltır */
    fun run(cachedProtocol: String?) {
        var needInit = true
        var proto = cachedProtocol
        while (running) {
            if (pauseWanted) {
                paused = true
                setStatus(EngineStatus(EngineState.PAUSED, protocol, mode, maxMulti, msg = "Harici uygulama bağlı"))
                while (pauseWanted && running) Thread.sleep(20)
                paused = false
                // Harici istemci ELM ayarlarını değiştirmiş olabilir: ayarları yeniden gönder,
                // PID/mod bilgisi geçerli kaldığı için tam kurulum gerekmez
                if (!needInit && !lightInit()) needInit = true
                continue
            }
            val scanDone = scanWanted
            if (scanDone != null) {
                scanWanted = null
                val (log, next) = DidScanner(this, clock, protocol, { setStatus(EngineStatus(EngineState.PROBING, msg = it)) },
                    { scanCancel || !running }, scanStart, scanIds).run()
                needInit = true
                scanDone(log, next)
                continue
            }
            val sniffDone = sniffWanted
            if (sniffDone != null) {
                sniffWanted = null
                val log = CanSniffer(this, clock, protocol,
                    { setStatus(EngineStatus(EngineState.PROBING, msg = it)) }, { onSniffStep?.invoke(it) }, sniffSteps,
                    refEverySlice = sniffDrive, cancelled = { sniffCancel || !running }).run()
                needInit = true
                sniffDone(log)
                continue
            }
            val done = probeWanted
            if (done != null) {
                probeWanted = null
                val json = VehicleProbe(::request, clock, { setStatus(EngineStatus(EngineState.PROBING, msg = it)) }, ::interrupt).run()
                needInit = true  // test ELM ayarlarını değiştirdi
                done(json)
                continue
            }
            val dtcDone = dtcWanted
            if (dtcDone != null) {
                dtcWanted = null
                dtcDone(if (needInit) DtcReport(emptyList(), emptyList(), null, null, null, null, error = "Araca bağlı değil", ts = clock())
                    else DtcReader(::request, protocol in setOf("6", "7", "8", "9"), clock).run(dtcClear))
                lastMil = 0L  // gösterge kutucuğundaki arıza sayısı hemen yenilensin
                continue
            }
            if (needInit) {
                if (!init(proto)) {
                    proto = null  // kayıtlı protokol tutmadıysa bir sonrakinde otomatik ara
                    sleepWhileRunning(2000)
                    continue
                }
                needInit = false
                proto = protocol  // yeniden kurulumda protokol araması atlanır
            }
            needInit = !cycle()
        }
        setStatus(EngineStatus(EngineState.STOPPED, protocol))
    }

    /** Adaptörü hazırlar, protokolü bulur, yetenekleri test eder. ECU yoksa false. */
    private fun init(cachedProtocol: String?): Boolean {
        setStatus(EngineStatus(EngineState.INIT, msg = "Adaptör hazırlanıyor"))
        adapterId = request("ATZ", 3000).lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() && it != TIMEOUT } ?: "?"
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1")) checked(c, 1000)
        checked("ATSP" + (cachedProtocol ?: "0"), 1000)

        setStatus(EngineStatus(EngineState.SEARCHING, msg = "Araç aranıyor"))
        val first = checked("0100", 15000)
        val firstFrames = ElmParser.frames(first)
        val mask0 = ElmParser.mode01(firstFrames, setOf(0x00))[0x00]
        if (ElmParser.error(first) != null || mask0 == null) {
            setStatus(EngineStatus(EngineState.NO_ECU, msg = ElmParser.error(first) ?: "Cevap yok"))
            return false
        }
        protocol = checked("ATDPN", 1000).trim().removePrefix("A")

        val supported = HashSet(ElmParser.supportedFromMask(0x00, mask0))
        var base = 0x20
        while (base < 0xE0 && (base in supported)) {
            val r = checked("01%02X".format(base), 2000)
            val m = ElmParser.mode01(ElmParser.frames(r), setOf(base))[base] ?: break
            supported += ElmParser.supportedFromMask(base, m)
            base += 0x20
        }
        pids = Pids.select(supported)
        supportedPids = supported.toSortedSet()
        lastReq.clear(); misses.clear(); suspendedUntil.clear()

        // CAN'da doğrudan motor ECU'suna sor (başka ECU'nun cevabı beklenmez); olmazsa fonksiyonel adrese dön.
        // 11-bit ve 29-bit adresler farklı: yanlış biçimdeki başlık ECU'yu tamamen susturur.
        header = null
        ElmParser.ecmHeaders(protocol)?.let { (guess, functional) ->
            // Motor ECU'sunun adresini cevap başlığından öğren (tahmin S40'ta tutmadı)
            checked("ATH1", 1000)
            val hr = checked("0100", 3000)
            checked("ATH0", 1000)
            val physical = ElmParser.ecuRequestHeaders(hr, protocol).firstOrNull() ?: guess
            checked("ATSH$physical", 1000)
            header = physical
            val r = checked("010C", 2000)
            if (r == TIMEOUT) interrupt()
            if (ElmParser.mode01(ElmParser.frames(r), setOf(0x0C)).isEmpty()) {
                checked("ATSH$functional", 1000)
                header = functional
            }
        }
        detectMode()
        timeoutsInRow = 0; busErrorsInRow = 0; parseFailsInRow = 0
        winStart = 0; winReq = 0; winRpm = 0
        setStatus(EngineStatus(EngineState.LIVE, protocol, mode, maxMulti))
        return true
    }

    /** Harici istemciden sonra: ATZ ve keşif olmadan ayarları geri yükler. Test sorgusu tutmazsa false. */
    private fun lightInit(): Boolean {
        setStatus(EngineStatus(EngineState.INIT, protocol, mode, maxMulti, msg = "Ayarlar geri yükleniyor"))
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1")) checked(c, 1000)
        checked("ATSP" + (protocol ?: "0"), 1000)
        header?.let { checked("ATSH$it", 1000) }
        val sel = pids.take(1)
        if (sel.isEmpty() || !tryQuery(sel, mode == QueryMode.MULTI_FAST || mode == QueryMode.SINGLE_FAST)) return false
        winStart = 0; winReq = 0; winRpm = 0
        setStatus(EngineStatus(EngineState.LIVE, protocol, mode, maxMulti))
        return true
    }

    /** Çoklu PID ve cevap sayısı ekini dener; en hızlı çalışan modu seçer. */
    private fun detectMode() {
        val testPids = pids.filter { it.tier != Tier.C }.take(6).ifEmpty { pids.take(6) }
        maxMulti = 1
        for (n in listOf(6, 3, 2)) {
            if (testPids.size < n) continue
            val sel = testPids.take(n)
            // 3+ çerçeveli cevap v2.1 klonunu kilitliyor (2026-10-09 raporu): hiç deneme
            if (ElmParser.responseFrames(sel.map { it.pid }) > 2) continue
            if (tryQuery(sel, fast = false)) { maxMulti = n; break }
        }
        val one = pids.take(1)
        val singleFast = one.isNotEmpty() && tryQuery(one, fast = true)
        val multiFast = maxMulti > 1 && tryQuery(testPids.take(maxMulti), fast = true)
        mode = when {
            maxMulti > 1 && multiFast -> QueryMode.MULTI_FAST
            maxMulti > 1 -> QueryMode.MULTI
            singleFast -> QueryMode.SINGLE_FAST
            else -> QueryMode.SINGLE
        }
    }

    private fun tryQuery(sel: List<PidDef>, fast: Boolean): Boolean {
        repeat(2) {  // tek seferlik NO DATA'ya kanmamak için iki deneme
            val r = request(command(sel, fast), 2000)
            if (r == TIMEOUT) { interrupt(); return false }  // klon bu biçimde kilitleniyor: tekrar deneme
            if (ElmParser.error(r) == null &&
                ElmParser.mode01(ElmParser.frames(r), sel.map { it.pid }.toSet()).size == sel.size
            ) return true
        }
        return false
    }

    /** "01" + PID'ler + (hızlı modda, cevap tek çerçeveyse) "1" cevap sayısı eki. */
    private fun command(sel: List<PidDef>, fast: Boolean): String {
        val sb = StringBuilder("01")
        for (p in sel) sb.append("%02X".format(p.pid))
        if (fast) sb.append(ElmParser.countSuffix(sel.map { it.pid }))
        return sb.toString()
    }

    /** Bir sorgu turu. ECU kaybolduysa false (yeniden init gerekir). */
    private fun cycle(): Boolean {
        val now = clock()

        // Adaptör voltajı ve MIL durumu ayrı, seyrek istekler
        if (now - lastVolt >= 2000) {
            lastVolt = now
            ElmParser.volts(request("ATRV", 1000))?.let { publish(listOf(Sample("battery", it, clock()))) }
        }
        if (now - lastMil >= 60_000) {
            lastMil = now
            val m = ElmParser.mode01(ElmParser.frames(request("0101", 1000)), setOf(0x01))[0x01]
            if (m != null) {
                val a = m[0].toInt() and 0xFF
                publish(listOf(Sample("mil", if (a and 0x80 != 0) 1.0 else 0.0, clock()), Sample("dtc_count", (a and 0x7F).toDouble(), clock())))
            }
        }

        val raws = rawPoll
        if (raws.isNotEmpty() && now - lastRaw >= rawPeriodMs) {
            lastRaw = now
            for (c in raws) {
                val r = request(c, 1500)
                if (r == TIMEOUT) interrupt()
                onRaw?.invoke(c, r, clock())
            }
        }

        val batch = pickBatch(now)
        if (batch.isEmpty()) { Thread.sleep(50); return true }
        val fast = mode == QueryMode.MULTI_FAST || mode == QueryMode.SINGLE_FAST
        val text = request(command(batch, fast), 1000)
        countRequest()
        for (p in batch) lastReq[p.key] = now

        when (val err = if (text == TIMEOUT) TIMEOUT else ElmParser.error(text)) {
            null -> {
                timeoutsInRow = 0; busErrorsInRow = 0
                val got = ElmParser.mode01(ElmParser.frames(text), batch.map { it.pid }.toSet())
                val ts = clock()
                val samples = ArrayList<Sample>(got.size)
                for (p in batch) {
                    val b = got[p.pid]
                    if (b != null) {
                        samples += Sample(p.key, p.decode(b), ts)
                        misses[p.key] = 0
                    } else miss(p, ts)
                }
                if (got.isEmpty()) {
                    // Hatasız ama ayrıştırılamayan cevap: mod adaptöre uymuyor olabilir
                    if (++parseFailsInRow >= 3) downgrade("ayrıştırılamayan cevap: ${text.take(40)}")
                } else parseFailsInRow = 0
                publish(samples)
            }
            "NO DATA" -> { timeoutsInRow = 0; batch.forEach { miss(it, now) } }
            "?" -> downgrade("adaptör komutu tanımadı")
            "BUFFER FULL" -> downgrade("BUFFER FULL")
            TIMEOUT, "STOPPED" -> {
                if (!running) return true
                interrupt()
                if (++timeoutsInRow >= 3) throw LinkLostException("adaptör 3 kez cevap vermedi")
            }
            else -> {  // CAN ERROR, UNABLE TO CONNECT, BUS INIT...: kontak kapanmış olabilir
                if (++busErrorsInRow >= 3) {
                    setStatus(EngineStatus(EngineState.NO_ECU, protocol, msg = err))
                    sleepWhileRunning(2000)
                    return false
                }
            }
        }
        return true
    }

    /**
     * Sıradaki isteğe girecek PID'leri seçer. A katmanı (devir, hız, pedal) her zaman öncelikli,
     * ama süresi dolmuş B/C PID'leri aç kalmasın diye onlara pay ayrılır:
     * tek PID modunda her 3 istekten biri, çoklu modda paketin bir yeri.
     */
    private fun pickBatch(now: Long): List<PidDef> {
        val live = pids.filter { (suspendedUntil[it.key] ?: 0) <= now && !skip(it.key) }
        if (live.isEmpty()) return emptyList()
        fun ratio(p: PidDef) = (now - (lastReq[p.key] ?: 0)).toDouble() / p.tier.periodMs
        val a = live.filter { it.tier == Tier.A }.sortedByDescending(::ratio)
        // B/C: en çok gecikmiş (son tarihi en eski) önce. Oranla sıralasak kısa periyotlu B, C'yi aç bırakırdı
        val overdue = live.filter { it.tier != Tier.A && ratio(it) >= 1 }
            .sortedBy { (lastReq[it.key] ?: 0) + it.tier.periodMs }

        if (maxMulti <= 1 || mode == QueryMode.SINGLE || mode == QueryMode.SINGLE_FAST) {
            val pick = if (overdue.isNotEmpty() && (a.isEmpty() || aInRow >= 2)) overdue[0] else a.firstOrNull()
                ?: live.maxBy(::ratio)
            aInRow = if (pick.tier == Tier.A) aInRow + 1 else 0
            return listOf(pick)
        }
        val reserve = if (overdue.isNotEmpty()) 1 else 0
        val batch = a.take((maxMulti - reserve).coerceAtLeast(1)).toMutableList()
        batch += overdue.take(maxMulti - batch.size)
        return batch
    }

    private fun miss(p: PidDef, now: Long) {
        val n = (misses[p.key] ?: 0) + 1
        misses[p.key] = n
        if (n >= 5) {  // sürekli NO DATA dönen PID'i 10 sn askıya al
            suspendedUntil[p.key] = now + 10_000
            misses[p.key] = 0
        }
    }

    private fun downgrade(why: String) {
        parseFailsInRow = 0
        val before = mode to maxMulti
        when {
            mode == QueryMode.MULTI_FAST -> mode = QueryMode.MULTI
            mode == QueryMode.MULTI && maxMulti > 2 -> maxMulti = if (maxMulti > 3) 3 else 2
            mode == QueryMode.MULTI -> { mode = QueryMode.SINGLE_FAST; maxMulti = 1 }
            mode == QueryMode.SINGLE_FAST -> mode = QueryMode.SINGLE
        }
        if (before != (mode to maxMulti)) {
            setStatus(status.copy(mode = mode, maxMulti = maxMulti, msg = "Mod düşürüldü: $why"))
        }
    }

    private fun publish(samples: List<Sample>) {
        if (samples.isEmpty()) return
        winRpm += samples.count { it.key == "rpm" }
        listener.onSamples(samples)
    }

    private fun countRequest() {
        val now = clock()
        if (winStart == 0L) winStart = now
        winReq++
        if (now - winStart >= 1000) {
            val dt = (now - winStart) / 1000.0
            setStatus(EngineStatus(EngineState.LIVE, protocol, mode, maxMulti, winReq / dt, winRpm / dt, status.msg))
            winStart = now; winReq = 0; winRpm = 0
        }
    }

    private fun setStatus(s: EngineStatus) {
        if (s.state != status.state || s.msg != status.msg || s.mode != status.mode) synchronized(journal) {
            journal.addLast("%tT %s%s".format(clock(), s.state, s.msg?.let { " · $it" } ?: ""))
            while (journal.size > 60) journal.removeFirst()
        }
        status = s
        listener.onStatus(s)
    }

    /** Hatası olsa da devam edilen kurulum komutu. */
    private fun checked(cmd: String, timeoutMs: Long): String {
        val r = request(cmd, timeoutMs)
        if (r == TIMEOUT && ++timeoutsInRow >= 3) throw LinkLostException("adaptör kurulumda cevap vermiyor")
        if (r != TIMEOUT) timeoutsInRow = 0
        return r
    }

    private fun sleepWhileRunning(ms: Long) {
        val end = clock() + ms
        while (running && !pauseWanted && clock() < end) Thread.sleep(20)
    }

    companion object {
        const val TIMEOUT = "<TIMEOUT>"
    }
}
