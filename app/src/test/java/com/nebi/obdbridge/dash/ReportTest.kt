package com.nebi.obdbridge.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportTest {
    @Test fun masksVin() {
        val j = """{"vin":"YV1MS7650A2123456","raw_0902":"49020159563140","x":1}"""
        val m = ProblemReport.maskVin(j)
        assertTrue(m.contains("\"vin\":\"YV1**************\""))
        assertFalse(m.contains("2123456"))
        assertFalse(m.contains("4902015956"))
        assertTrue(m.contains("\"x\":1"))
    }

    /** Gerçek araç testi raporlarında (yerelde varsa) VIN kalmamalı. */
    @Test fun masksRealProbeReports() {
        val dir = java.io.File("../probe-reports/probe")
        val files = dir.listFiles { f -> f.name.endsWith(".json") } ?: return
        for (f in files) {
            val t = f.readText()
            val vin = Regex("\"vin\"\\s*:\\s*\"([^\"]+)\"").find(t)?.groupValues?.get(1) ?: continue
            if (vin.length != 17) continue
            assertFalse(f.name, ProblemReport.maskVin(t).contains(vin.substring(3)))
        }
    }

    @Test fun masksMac() {
        assertEquals("00:11:22:**:**:**", ProblemReport.maskMac("00:11:22:33:44:55"))
        assertEquals("192.168.0.10:35000", ProblemReport.maskMac("192.168.0.10:35000"))
    }
}
