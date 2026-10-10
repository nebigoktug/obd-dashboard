package com.nebi.obdbridge.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ElmParserTest {

    @Test fun singleFrame() {
        val r = ElmParser.mode01(ElmParser.frames("410C1AF8"), setOf(0x0C))
        assertEquals(1726.0, Pids.ALL.first { it.pid == 0x0C }.decode(r.getValue(0x0C)), 0.01)
    }

    @Test fun multiFrameIsoTp() {
        val text = "00F\n0: 410C3F7A0D1D\n1: 112A0400410400\n2: 0B63"
        val r = ElmParser.mode01(ElmParser.frames(text), setOf(0x0C, 0x0D, 0x11, 0x04, 0x0B))
        assertEquals(setOf(0x0C, 0x0D, 0x11, 0x04, 0x0B), r.keys)  // araya giren 41 atlanır
        assertEquals(0x63, r.getValue(0x0B)[0].toInt())
    }

    @Test fun searchingAndErrors() {
        assertNull(ElmParser.error("SEARCHING...\n410C1AF8"))
        assertEquals("NO DATA", ElmParser.error("NO DATA"))
        assertEquals("?", ElmParser.error("?"))
        assertEquals("UNABLE TO CONNECT", ElmParser.error("SEARCHING...\nUNABLE TO CONNECT"))
    }

    @Test fun supportedMask() {
        val s = ElmParser.supportedFromMask(0, byteArrayOf(0xBE.toByte(), 0x1F, 0xA8.toByte(), 0x13))
        assertEquals(true, 0x01 in s); assertEquals(false, 0x02 in s); assertEquals(true, 0x0C in s); assertEquals(true, 0x20 in s)
    }

    @Test fun dtcCan() {
        val f = ElmParser.frames("43020133C401")
        assertEquals(listOf("P0133", "U0401"), ElmParser.dtcs(f, 0x03, isCan = true))
    }

    @Test fun volts() = assertEquals(12.6, ElmParser.volts("12.6V")!!, 0.001)

    // ---- 2026-10-08 gerçek "ELM327 v2.1" klonu raporundan ----

    @Test fun cloneGarbageAfterSinglePid() {
        // 410C0C5C + önceki mesajdan kalan F00B39
        val r = ElmParser.mode01(ElmParser.frames("410C0C5CF00B39"), setOf(0x0C))
        assertEquals(791.0, Pids.ALL.first { it.pid == 0x0C }.decode(r.getValue(0x0C)), 0.01)
        // Çöp içinde istenen bir PID kimliği olsa da ilk doğru değer korunur
        val r2 = ElmParser.mode01(ElmParser.frames("410C0C580D000D39"), setOf(0x0C, 0x0D))
        assertEquals(0, r2.getValue(0x0D)[0].toInt())
    }

    @Test fun dtcCountIgnoresGarbage() {
        assertEquals(listOf("P0341", "U300B"), ElmParser.dtcs(ElmParser.frames("43020341F00B39"), 0x03, isCan = true))
        assertEquals(emptyList<String>(), ElmParser.dtcs(ElmParser.frames("47000341F00B39"), 0x07, isCan = true))
    }

    @Test fun reversedVin() {
        // Sahte VIN, ters gönderilmiş (gerçek araçtaki gibi)
        val vin = "YV1MS00X0A0123456"
        val hex = "490201" + vin.reversed().map { "%02X".format(it.code) }.joinToString("")
        val text = "014\n0:" + hex.substring(0, 12) + "\n1:" + hex.substring(12, 26) + "\n2:" + hex.substring(26)
        assertEquals(vin, ElmParser.vin(ElmParser.frames(text)))
    }

    @Test fun ecuAddressFromResponseHeader() {
        // 29-bit: cevap 18DAF1xx → istek DAxxF1 (birden fazla ECU olabilir)
        assertEquals(listOf("DA7AF1", "DA18F1"),
            ElmParser.ecuRequestHeaders("SEARCHING...\n18DAF17A064100983A0011\n18DAF118064100800000", "7"))
        // 11-bit: 7E8 → 7E0, boşluklu biçim de olur
        assertEquals(listOf("7E0"), ElmParser.ecuRequestHeaders("7E8 06 41 00 BE 3F A8 13", "6"))
        assertEquals(emptyList<String>(), ElmParser.ecuRequestHeaders("NO DATA", "7"))
        // 3 PID (8 bayt) → 2 çerçeve; 6 PID (14 bayt) → 3 çerçeve (klonu kilitliyor)
        assertEquals(2, ElmParser.responseFrames(listOf(0x0C, 0x0D, 0x04)))
        assertEquals(3, ElmParser.responseFrames(listOf(0x0C, 0x0D, 0x04, 0x0B, 0x05, 0x0F)))
    }

    @Test fun headersAndSuffix() {
        assertEquals("DA10F1" to "DB33F1", ElmParser.ecmHeaders("7"))
        assertEquals("7E0" to "7DF", ElmParser.ecmHeaders("6"))
        assertNull(ElmParser.ecmHeaders("3"))
        assertEquals("1", ElmParser.countSuffix(listOf(0x0C, 0x0D)))      // 1+3+2 = 6 bayt, tek çerçeve
        assertEquals("", ElmParser.countSuffix(listOf(0x0C, 0x0D, 0x04)))  // 8 bayt, çok çerçeve: ek yok
    }
}
