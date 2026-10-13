package com.nebi.obdbridge.dash

import java.io.File

/**
 * Ham sensör kaydı (teşhis): G-ölçer gerçek sürüşte öğrenemeyince (2026-10-13) arabadaki sinyali
 * bilgisayarda birebir yeniden oynatmak için. files/sensors/sens_YYYYMMDD_HHMMSS.csv, satır türleri:
 *   a,ts,x,y,z   ivmeölçer (m/s², her olay ~50 Hz)
 *   g,ts,x,y,z   yerçekimi sensörü (10 Hz'e seyreltilmiş)
 *   s,ts,kmh     G-ölçere verilen hız
 *   st,ts,durum,learnN,agree   G-ölçer durumu (değişince ve 10 sn'de bir)
 * Yalnız araç hareket ederken ya da OBD canlıyken yazar; 5 dk sessizlikte dosyayı kapatır. [KEEP_DAYS] gün tutulur.
 */
class SensorLog(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private var out: java.io.Writer? = null
    private var lastActive = 0L
    private var lastFlush = 0L
    private var lastGrav = 0L
    private var lastSt = 0L
    private var lastState: GMeter.State? = null

    /** Her karede: [active] araç bağlı ya da hareket ediyor */
    fun tick(active: Boolean, g: GMeter) {
        val now = clock()
        if (active) lastActive = now
        else if (out != null && now - lastActive > GAP_MS) close()
        val w = out ?: return
        if (g.state != lastState || now - lastSt > 10_000) {
            w.write("st,$now,${g.state},${g.learnN},${"%.2f".format(java.util.Locale.US, g.agree)}\n")
            lastState = g.state; lastSt = now
        }
        if (now - lastFlush > 10_000) { w.flush(); lastFlush = now }
    }

    fun speed(ts: Long, kmh: Double) { writer(ts)?.write("s,$ts,${f(kmh)}\n") }

    fun accel(ts: Long, v: FloatArray) { writer(ts)?.write("a,$ts,${f(v[0])},${f(v[1])},${f(v[2])}\n") }

    fun gravity(ts: Long, v: FloatArray) {
        if (ts - lastGrav < 100) return
        lastGrav = ts
        writer(ts)?.write("g,$ts,${f(v[0])},${f(v[1])},${f(v[2])}\n")
    }

    private fun writer(now: Long): java.io.Writer? {
        if (now - lastActive > GAP_MS) return null
        out?.let { return it }
        return try {
            dir.mkdirs()
            dir.listFiles { x -> x.name.startsWith("sens_") && now - x.lastModified() > KEEP_DAYS * 86_400_000L }?.forEach { it.delete() }
            java.io.FileWriter(File(dir, "sens_%1\$tY%1\$tm%1\$td_%1\$tH%1\$tM%1\$tS.csv".format(java.util.Date(now)))).buffered(1 shl 16)
                .also { out = it; lastState = null }
        } catch (_: Exception) { null }
    }

    fun close() {
        try { out?.close() } catch (_: Exception) {}
        out = null
    }

    private fun f(x: Number) = "%.3f".format(java.util.Locale.US, x.toDouble()).trimEnd('0').trimEnd('.')

    companion object {
        const val GAP_MS = 5 * 60_000L
        const val KEEP_DAYS = 7
    }
}
