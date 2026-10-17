package com.nebi.obdbridge.obd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/** Öğrenme modu gerçek buton testi kaydıyla (can-dumps 2): denemeleri kullanıcının bastığı anlara koyar. */
class CanLearnTest {
    private fun dump(): File? = listOf("../can-dumps", "can-dumps").map { File(it, "2026-10-15_2_buton-testi.txt.gz") }.firstOrNull { it.exists() }

    /** Kaydı satır satır oynatır; saat = satırdaki ms. [at] anlarında deneme başlatır, sonuçları döndürür. */
    private fun replay(f: File, at: List<Long>, until: Long, trialMs: Long = CanLearn.TRIAL_MS): List<List<CanLearn.Change>> {
        var now = 0L
        CanLearn.clock = { now }
        val out = ArrayList<List<CanLearn.Change>>()
        val starts = ArrayDeque(at)
        var begun = false
        GZIPInputStream(f.inputStream()).bufferedReader().useLines { lines ->
            for (l in lines) {
                if (l.startsWith("#")) continue
                val ms = l.substringBefore(' ').toLongOrNull() ?: continue
                now = ms
                if (!begun) { CanLearn.begin(); begun = true }
                CanLearn.remaining()
                CanLearn.trialResult()?.let { out += it }
                if (starts.isNotEmpty() && ms >= starts.first()) { assertTrue("deneme başlamadı @$ms", CanLearn.startTrial(trialMs)); starts.removeFirst() }
                val b = (l + "\n").toByteArray()
                CanLearn.feed(b, b.size)
                if (ms > until) break
            }
        }
        CanLearn.end()
        CanLearn.clock = { System.currentTimeMillis() }
        return out
    }

    @Test fun readKey() {
        val f = dump(); assumeTrue("kayıt yok", f != null)
        // 19:36:57 READ (60300 = 19:35:11) → ~166300; pencere biraz önce başlar
        val r = replay(f!!, listOf(164_500), 172_000)
        val v = CanLearn.combine(r)
        assertTrue("READ bulunmalı: ${v.sure}", v.sure.any { it.id == "03C3F7FC" && it.byte == 5 && it.mask and 0x40 != 0 })
        assertFalse("sayaçlar elenmeli: ${v.sure}", v.sure.any { it.id == "05704000" && it.byte == 0 })
    }

    @Test fun volumeKnob() {
        val f = dump(); assumeTrue("kayıt yok", f != null)
        // Ses düğmesi sağa 3 (~210600) ve sola 3 (~214400): iki deneme, ikisinde de tık biti (b2 0x80)
        val r = replay(f!!, listOf(209_900, 214_000), 221_000, trialMs = 3_500)
        val v = CanLearn.combine(r)
        assertTrue("ses düğmesi: ${v.sure}", v.sure.any { it.bus == 1 && it.id == "05704000" && it.byte == 2 && it.mask and 0x80 != 0 })
    }
}
