package com.nebi.obdbridge.obd

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Laptoptaki ELM327 emülatörüne (127.0.0.1:35000) gecikme proxy'si üzerinden bağlanır.
 * Emülatör çalışmıyorsa testler atlanır.  Başlatmak için: elm -n 35000 -s car -d
 */
class ObdEngineIT {
    private val procs = mutableListOf<Process>()

    @After fun tearDown() = procs.forEach { it.destroy() }

    private fun proxy(port: Int, vararg extra: String): Int {
        assumeTrue("emülatör yok", runCatching { Socket("127.0.0.1", 35000).close() }.isSuccess)
        val script = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "tools/latency_proxy.py") }.first { it.exists() }
        procs += ProcessBuilder("python3", script.path, "--listen", "$port", *extra).redirectErrorStream(true).start()
        repeat(50) { if (runCatching { Socket("127.0.0.1", port).close() }.isSuccess) return port; Thread.sleep(100) }
        error("proxy açılmadı")
    }

    private class Run(port: Int) : EngineListener {
        val sock = Socket("127.0.0.1", port).apply { tcpNoDelay = true }
        val latest = ConcurrentHashMap<String, Sample>()
        val counts = ConcurrentHashMap<String, Int>()
        val statuses = CopyOnWriteArrayList<EngineStatus>()
        val trace = CopyOnWriteArrayList<String>()
        val engine = ObdEngine({ sock.getOutputStream().write(it) }, this, trace = { c, r, ms -> trace += "$c -> ${r.replace("\n", "|")} ($ms ms)" })
        var error: Throwable? = null
        val thread = Thread {
            try { engine.run(null) } catch (e: Throwable) { error = e }
        }
        init {
            Thread {
                val buf = ByteArray(1024)
                try { while (true) { val n = sock.getInputStream().read(buf); if (n < 0) break; engine.onBytes(buf, n) } } catch (_: Exception) {}
            }.apply { isDaemon = true }.start()
            thread.start()
        }
        override fun onSamples(samples: List<Sample>) = samples.forEach { latest[it.key] = it; counts.merge(it.key, 1, Int::plus) }
        override fun onStatus(status: EngineStatus) { statuses += status; println("status: $status") }
        fun stop() { engine.stop(); thread.join(3000); sock.close() }
    }

    @Test fun liveRatesThroughLatencyProxy() {
        val r = Run(proxy(35011))
        Thread.sleep(3000)  // init + mod tespiti
        r.counts.clear()
        Thread.sleep(10_000)
        r.stop()
        val rpmHz = (r.counts["rpm"] ?: 0) / 10.0
        println("10 sn: ${r.counts}  rpm ${rpmHz} Hz, son durum ${r.statuses.last { it.state == EngineState.LIVE }}")
        // Çok çerçeveli istekte sayı eki kullanılmadığı için (v2.1 klonu kilitleniyor) ~10 Hz beklenir
        assertTrue("rpm hızı düşük: $rpmHz Hz", rpmHz >= 7)
        assertTrue("soğutucu okunmadı", (r.counts["coolant"] ?: 0) >= 3)
        assertTrue("akü okunmadı", (r.counts["battery"] ?: 0) >= 3)
    }

    @Test fun worstCloneFallsBackToSingle() {
        val r = Run(proxy(35014, "--no-count", "--no-multi"))
        Thread.sleep(6000)
        r.counts.clear()
        Thread.sleep(10_000)
        r.stop()
        val live = r.statuses.last { it.state == EngineState.LIVE }
        val rpmHz = (r.counts["rpm"] ?: 0) / 10.0
        println("kötü klon: mod ${live.mode}, ${"%.1f".format(live.reqPerSec)} istek/sn, rpm $rpmHz Hz, ${r.counts}")
        assertTrue("SINGLE bekleniyordu: ${live.mode}", live.mode == QueryMode.SINGLE)
        assertTrue("rpm çok yavaş: $rpmHz", rpmHz >= 2)  // tek PID + bekleme: tasarımdaki en kötü durum 2-5 Hz
        assertTrue("soğutucu aç kaldı", (r.counts["coolant"] ?: 0) >= 4)
        assertTrue("MAF aç kaldı", (r.counts["maf"] ?: 0) >= 4)
    }

    @Test fun vehicleProbeThenBackToLive() {
        val r = Run(proxy(35015))
        Thread.sleep(3000)
        val result = java.util.concurrent.ArrayBlockingQueue<String>(1)
        r.engine.requestProbe { result.put(it) }
        val json = result.poll(90, java.util.concurrent.TimeUnit.SECONDS) ?: error("test bitmedi")
        File("build/probe-test.json").writeText(json)
        println("rapor ${json.length} bayt: " + json.lines().filter { "\"protocol_num\"" in it || "\"fatal\"" in it }.joinToString())
        assertTrue("protokol yok", "\"protocol_num\":\"6\"" in json)
        assertTrue("zamanlama yok", "\"median_ms\"" in json)
        assertTrue("fatal var", "\"fatal\"" !in json)
        // Test bitince motor kendini yeniden kurup canlı akışa dönmeli
        val before = r.counts["rpm"] ?: 0
        Thread.sleep(6000)
        r.stop()
        assertTrue("testten sonra akış dönmedi", (r.counts["rpm"] ?: 0) > before + 20)
        assertTrue(r.statuses.any { it.state == EngineState.PROBING })
    }

    /** Gerçek araçtaki "ELM327 v2.1" klonu: ~170 ms istek, kısa cevaplarda çöp bayt, çok çerçeve + ekte kilitlenme. */
    @Test fun realV21CloneLike() {
        val r = Run(proxy(35016, "--delay", "150", "--jitter", "40", "--wait", "0", "--garbage", "--hang-multiframe-count"))
        Thread.sleep(12_000)  // kurulum + mod tespiti yavaş adaptörde uzun sürer
        r.counts.clear()
        Thread.sleep(10_000)
        r.stop()
        val live = r.statuses.last { it.state == EngineState.LIVE }
        val rpmHz = (r.counts["rpm"] ?: 0) / 10.0
        println("v2.1 benzeri: mod ${live.mode} ×${live.maxMulti}, ${"%.1f".format(live.reqPerSec)} istek/sn, rpm $rpmHz Hz, ${r.counts}, hata ${r.error}")
        assertTrue("motor düştü: ${r.error}", r.error == null)
        assertTrue("rpm çok yavaş: $rpmHz", rpmHz >= 3)
        val rpm = r.latest.getValue("rpm").value
        assertTrue("çöp bayt devri bozdu: $rpm", rpm in 0.0..8000.0)
        assertTrue("soğutucu okunmadı", (r.counts["coolant"] ?: 0) >= 3)
    }

    /** CAN keşif kaydı: filtresiz patlama taşar, dilimler taşmaz, kayıttan sonra canlı akış döner. */
    @Test fun canSniffSlicesWithoutOverflow() {
        val r = Run(proxy(35017, "--can-sim", "--delay", "20", "--jitter", "5"))
        Thread.sleep(3000)
        val result = java.util.concurrent.ArrayBlockingQueue<String>(1)
        // Testte kısa adımlar: 2 adım × 26 sn (her adımda 16 dilimlik tur dönsün)
        r.engine.requestSniffForTest(result)
        val log = result.poll(150, java.util.concurrent.TimeUnit.SECONDS) ?: error("kayıt bitmedi")
        File("build/can-test.txt").writeText(log)
        val lines = log.lines()
        val burst = lines.first { it.startsWith("BURST") }
        val slices = lines.filter { it.startsWith("SLICE") }
        val full = slices.count { "BUFFER FULL" in it }
        val seenSlices = lines.filter { it.startsWith("F ") }.map { it.split(" ")[2].toInt() }.filter { it >= 0 }.toSet()
        val ids = lines.filter { it.startsWith("F ") }.map { it.split(" ")[3] }.toSet()
        println("CAN: $burst | ${slices.size} dilim, taşan $full, görülen dilim ${seenSlices.size}, kimlik ${ids.size}, refs ${lines.count { it.startsWith("REF") }}")
        assertTrue("patlama taşmalıydı: $burst", "BUFFER FULL" in burst)
        assertTrue("dilimler taşıyor: $full/${slices.size}", full * 5 < slices.size)
        assertTrue("16 dilimin hepsi görülmeli: $seenSlices", seenSlices.size == 16)
        assertTrue("kimliklerin çoğu görülmeli: ${ids.size}", ids.size >= 55)
        val before = r.counts["rpm"] ?: 0
        Thread.sleep(6000)
        r.stop()
        assertTrue("kayıttan sonra akış dönmedi", (r.counts["rpm"] ?: 0) > before + 10)
    }

    /** Sürüş modu: her dilimden önce referans, kullanıcı erken bitirince o ana kadarki kayıt döner. */
    @Test fun driveSniffCancelKeepsLog() {
        val r = Run(proxy(35018, "--can-sim", "--delay", "20", "--jitter", "5"))
        Thread.sleep(3000)
        val result = java.util.concurrent.ArrayBlockingQueue<String>(1)
        r.engine.requestSniff(CanSniffer.DRIVE, drive = true) { result.put(it) }
        Thread.sleep(15_000)
        r.engine.cancelSniff()
        val log = result.poll(20, java.util.concurrent.TimeUnit.SECONDS) ?: error("iptal sonrası kayıt dönmedi")
        val lines = log.lines()
        println("sürüş: ${lines.count { it.startsWith("REF ") }} ref, ${lines.count { it.startsWith("SLICE") }} dilim, iptal ${lines.any { it.startsWith("CANCEL") }}")
        assertTrue("CANCEL yok", lines.any { it.startsWith("CANCEL") })
        assertTrue("dilim başına referans yok", lines.count { it.startsWith("REF ") } >= 4)
        val before = r.counts["rpm"] ?: 0
        Thread.sleep(5000)
        r.stop()
        assertTrue("kayıttan sonra akış dönmedi", (r.counts["rpm"] ?: 0) > before + 10)
    }

    /** S40'taki gibi sokette yayın yoksa kayıt bir turda (16 dilim) kendiliğinden durmalı. */
    @Test fun silentBusStopsSniffEarly() {
        val r = Run(proxy(35019, "--can-sim", "--can-silent", "--delay", "20", "--jitter", "5"))
        Thread.sleep(3000)
        val result = java.util.concurrent.ArrayBlockingQueue<String>(1)
        val t0 = System.currentTimeMillis()
        r.engine.requestSniff(CanSniffer.DRIVE, drive = true) { result.put(it) }
        val log = result.poll(120, java.util.concurrent.TimeUnit.SECONDS) ?: error("sessiz hatta kayıt durmadı")
        val sec = (System.currentTimeMillis() - t0) / 1000
        println("sessiz hat: $sec sn sonra durdu, ${log.lines().firstOrNull { it.startsWith("NOTRAFFIC") }}")
        r.stop()
        assertTrue("NOTRAFFIC yok", log.lines().any { it.startsWith("NOTRAFFIC") })
        assertTrue("çok geç durdu: $sec sn", sec < 90)
    }

    /** Gizli değer taraması: DID'leri tek tek sorar, kaldığı yerden devam eder. */
    @Test fun didScanFindsAndResumes() {
        val r = Run(proxy(35020, "--uds-sim", "--delay", "10", "--jitter", "3"))
        Thread.sleep(3000)
        val ids = ((0xD100..0xD1FF) + (0x1300..0x131F)).toIntArray()
        val q = java.util.concurrent.ArrayBlockingQueue<Pair<String, Int>>(1)
        r.engine.requestScan(0, ids) { log, next -> q.put(log to next) }
        val (log, next) = q.poll(120, java.util.concurrent.TimeUnit.SECONDS) ?: error("tarama bitmedi")
        val hits = log.lines().filter { it.startsWith("D22") || it.startsWith("L21") }
        println("tarama: next=$next\n" + hits.joinToString("\n"))
        assertTrue("bitmeliydi", next == -1)
        assertTrue("D123 bulunmalı", hits.any { it.startsWith("D22") && " D123 " in it && "62D123012C" in it })
        assertTrue("1310 bulunmalı", hits.any { it.startsWith("D22") && " 1310 " in it })
        assertTrue("0x21 01 bulunmalı", hits.any { it.startsWith("L21 ") && " 01 " in it })
        assertTrue("21 B1 genişletilmiş oturumda bulunmalı", hits.any { it.startsWith("L21S03") && " B1 " in it })
        assertTrue("B1 varsayılan oturumda koşul hatası vermeli", log.lines().any { it.startsWith("N21 ") && " B1 " in it })
        // 01 her iki oturumda da bulunur: L21, L21S03 ×2 (01, B1), D22 ×2
        assertTrue("fazladan bulgu: $hits", hits.size == 5)
        // Devam: D123'ten sonra başlarsa onu bulmamalı, 1310'u bulmalı
        val startAfter = ids.indexOf(0xD123) + 1
        r.engine.requestScan(startAfter, ids) { l, n -> q.put(l to n) }
        val (log2, _) = q.poll(120, java.util.concurrent.TimeUnit.SECONDS) ?: error("ikinci tarama bitmedi")
        assertTrue(log2.lines().none { " D123 " in it && it.startsWith("D22") })
        assertTrue(log2.lines().any { " 1310 " in it && it.startsWith("D22") })
        assertTrue("devamda 0x21 tekrar taranmamalı", log2.lines().none { it.startsWith("L21") })
        val before = r.counts["rpm"] ?: 0
        Thread.sleep(5000)
        r.stop()
        assertTrue("taramadan sonra akış dönmedi", (r.counts["rpm"] ?: 0) > before + 10)
    }

    /** Blok kaydı: canlı akış sürerken 21 01 saniyede bir sorulur, ham cevap dışarı verilir. */
    @Test fun rawBlockPollDuringLive() {
        val r = Run(proxy(35021, "--uds-sim", "--delay", "10", "--jitter", "3"))
        Thread.sleep(3000)
        val got = CopyOnWriteArrayList<String>()
        r.engine.onRaw = { c, resp, _ -> got += "$c=$resp" }
        r.engine.rawPoll = listOf("2101")
        val before = r.counts["rpm"] ?: 0
        Thread.sleep(5000)
        r.engine.rawPoll = emptyList()
        r.stop()
        println("blok: ${got.size} cevap, ilk ${got.firstOrNull()}")
        assertTrue("blok cevabı yok: $got", got.size in 4..6 && got.all { it == "2101=6101AABB" })
        assertTrue("blok kaydı akışı durdurdu", (r.counts["rpm"] ?: 0) > before + 10)
    }

    @Test fun pauseAndResumeForRawClient() {
        val r = Run(proxy(35012))
        Thread.sleep(3000)
        r.engine.pause()
        val before = r.counts["rpm"] ?: 0
        Thread.sleep(1000)
        assertTrue("duraklatılmışken sorgu sürdü", (r.counts["rpm"] ?: 0) - before <= 1)
        val t0 = System.currentTimeMillis()
        r.engine.resume()
        while ((r.counts["rpm"] ?: 0) <= before && System.currentTimeMillis() - t0 < 5000) Thread.sleep(10)
        val resumeMs = System.currentTimeMillis() - t0
        println("devam sonrası ilk devir: $resumeMs ms\n" + r.trace.takeLast(10).joinToString("\n"))
        assertTrue("devam çok yavaş: $resumeMs ms", resumeMs < 1000)  // hafif kurulum: ATZ ve keşif yok
        r.stop()
    }

    @Test fun deadAdapterThrowsLinkLost() {
        val r = Run(proxy(35013, "--hang-after", "60"))
        r.thread.join(20_000)
        println("hata: ${r.error}")
        assertTrue("LinkLost beklendi, gelen: ${r.error}", r.error is LinkLostException)
        r.sock.close()
    }
}
