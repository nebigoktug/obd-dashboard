package com.nebi.obdbridge.obd

import com.nebi.obdbridge.dash.DtcCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcReaderTest {
    private fun reader(answers: Map<String, String>, sent: MutableList<String> = ArrayList()) =
        DtcReader({ cmd, _ -> sent += cmd; answers[cmd] ?: "NO DATA" }, isCan = true, clock = { 1000L })

    /** S40 (2026-10-08 araç testi): 03 → P0341 + U300B, klon sonuna çöp bayt ekliyor; 0A desteklenmiyor. */
    @Test fun readsS40Codes() {
        val r = reader(mapOf(
            "03" to "43020341F00B39",  // gerçek araç cevabı (probe-reports)
            "07" to "4300",
            "0A" to "7F0A11",
            "0101" to "4101020765A100",
        )).run(clear = false)
        assertEquals(listOf("P0341", "U300B"), r.stored)
        assertEquals(emptyList<String>(), r.pending)
        assertNull("kalıcı kod servisi yok", r.permanent)
        assertEquals(false, r.mil)
        assertNull(r.cleared)
        assertNull(r.error)
    }

    @Test fun clearRefusedWhileEngineRuns() {
        val sent = ArrayList<String>()
        val r = reader(mapOf("04" to "7F0422", "03" to "430100100"), sent).run(clear = true)
        assertEquals("04", sent.first())
        assertEquals(false, r.cleared)
    }

    @Test fun clearAccepted() {
        val r = reader(mapOf("04" to "44", "03" to "4300", "0101" to "4101000765A100")).run(clear = true)
        assertEquals(true, r.cleared)
        assertTrue(r.stored.isEmpty())
    }

    @Test fun noAnswerIsError() {
        val r = DtcReader({ _, _ -> ObdEngine.TIMEOUT }, true, { 0L }).run(false)
        assertTrue(r.error != null)
    }

    /** B=07 (3 sürekli test destekli, hepsi tamam), C=65 (4 test), D=A1 → 0x01 ve 0x20 tamamlanmadı. */
    @Test fun readinessCounts() {
        assertEquals(5 to 7, DtcReader.readiness(byteArrayOf(0x02, 0x07, 0x65, 0xA1.toByte())))
        assertEquals(0 to 0, DtcReader.readiness(byteArrayOf(0, 0, 0, 0)))
    }

    @Test fun catalogDescribes() {
        val t = DtcCatalog.parse(listOf("P0341\tEksantrik", "bozuk satır", "p2002\tDPF"))
        assertEquals(setOf("P0341", "P2002"), t.keys)
        DtcCatalog.describe("P0341", t).let { assertTrue(it.known); assertTrue(it.generic) }
        DtcCatalog.describe("U300B", t).let { assertFalse(it.known); assertFalse(it.generic); assertEquals("modüller arası iletişim", it.area) }
        DtcCatalog.describe("P1234", t).let { assertFalse(it.generic); assertEquals("yakıt ve hava ölçümü, enjektörler", it.area) }
        DtcCatalog.describe("P0420", t).let { assertFalse(it.known); assertTrue(it.generic) }
    }

    /** Dağıtılan tablo: bozuk satır yok, kodlar biçimli, kritik kodlar doğru başlıkta. */
    @Test fun assetTableIsSane() {
        val f = java.io.File("src/main/assets/dtc_tr.tsv")
        val lines = f.readLines().filter { it.isNotBlank() }
        val t = DtcCatalog.parse(lines)
        assertEquals(lines.size, t.size)
        assertTrue(t.keys.all { Regex("[PU][0-3][0-9A-F]{3}").matches(it) })
        assertTrue(t["P0420"]!!.contains("Katalizör"))
        assertTrue(t["P0300"]!!.contains("tekleme"))
        assertTrue(t["P2002"]!!.contains("DPF"))
    }
}
