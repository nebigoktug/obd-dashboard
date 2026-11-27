package com.nebi.obdbridge.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Mode06ParseTest {
    @Test fun scales() {
        assertEquals(1.0, Mode06.scale(0x0B, 1000).first, 1e-9)                 // 0,001 V
        assertEquals("V", Mode06.scale(0x0B, 1000).second)
        assertEquals(60.0, Mode06.scale(0x16, 1000).first, 1e-9)                // 0,1 °C − 40
        assertEquals(-3.0, Mode06.scale(0x99, 0xFFE2).first, 1e-9)             // işaretli: −30 × 0,1 kPa
        assertEquals(1234.0, Mode06.scale(0x77, 1234).first, 1e-9)             // bilinmeyen: ham
    }

    @Test fun parsesMultipleTests() {
        // 0621 (katalizör B1): iki test; sonda klonun eklediği artık bayt
        val f = ElmParser.frames("46 21 80 24 01 9C 00 00 03 E8 21 81 0B 03 16 01 F4 03 20 55")
        val t = Mode06.parse(f, 0x21)
        assertEquals(2, t.size)
        assertEquals(412.0, t[0].value, 1e-9); assertEquals(1000.0, t[0].max, 1e-9)
        assertEquals(0.79, t[1].value, 1e-9); assertEquals(0.5, t[1].min, 1e-9); assertEquals(0.8, t[1].max, 1e-9)
        assertEquals(Mode06Test.Verdict.PASS, t[0].verdict)
        assertEquals(Mode06Test.Verdict.NEAR, t[1].verdict)                    // 0,79: üst sınıra 0,01 (aralık 0,3)
    }

    @Test fun verdicts() {
        assertEquals(Mode06Test.Verdict.FAIL, Mode06Test(1, 1, 1, 11.0, 0.0, 10.0, "").verdict)
        assertEquals(Mode06Test.Verdict.PASS, Mode06Test(1, 1, 1, 5.0, 0.0, 10.0, "").verdict)
        assertEquals(Mode06Test.Verdict.NEAR, Mode06Test(1, 1, 1, 0.5, 0.0, 10.0, "").verdict)
        assertEquals(Mode06Test.Verdict.PASS, Mode06Test(1, 1, 1, 3.0, 3.0, 3.0, "").verdict)   // sınırsız
    }

    @Test fun readerWalksMasks() {
        val sent = ArrayList<String>()
        val answers = mapOf(
            "0600" to "4600C0000001",      // 01 02 + 0620 maskesi var
            "0620" to "46208000 0001".replace(" ", ""),   // 21 + 0640 var
            "0640" to "46400000 0000".replace(" ", ""),
            "0601" to "46 01 01 0A 00 C8 00 00 03 E8",
            "0602" to "NO DATA",
            "0621" to "46 21 80 24 01 9C 00 00 03 E8",
        )
        val r = Mode06Reader({ c, _ -> sent += c; answers[c] ?: "NO DATA" }, true, { 5L }).run()
        assertNull(r.error)
        assertEquals(listOf(0x01, 0x02, 0x21), r.supported)
        assertEquals(2, r.tests.size)
        assertEquals(24.4, r.tests[0].value, 1e-9)                              // 200 × 0,122 mV
        assertTrue(r.raw.contains("0621 → "))
        assertEquals(listOf("0600", "0620", "0640", "0601", "0602", "0621"), sent)
    }

    @Test fun readerNoSupport() {
        val r = Mode06Reader({ _, _ -> "NO DATA" }, true, { 0L }).run()
        assertNotNull(r.error)
        val k = Mode06Reader({ _, _ -> "NO DATA" }, false, { 0L }).run()
        assertNotNull(k.error)                                                 // CAN değil
    }

    @Test fun names() {
        assertEquals("Oksijen sensörü B2S3", Mode06.midName(0x07))
        assertEquals("Partikül filtresi (DPF) banka 1", Mode06.midName(0xB0))
        assertEquals("Ateşleme kaçırma · 4. silindir", Mode06.midName(0xA5))
        assertEquals("Üretici testi \$85", Mode06.tidName(0x85))
    }
}
