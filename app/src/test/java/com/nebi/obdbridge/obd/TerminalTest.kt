package com.nebi.obdbridge.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalTest {
    @Test fun blocksWriteAndResetCommands() {
        // İş planındaki liste: kod silme, yazma, rutin, oturum, sıfırlama, KWP yazma
        for (c in listOf("04", "14", "14FFFFFF", "2EF19000", "3101FF00", "1003", "1101", "3B9A00", "2F0D0103", "2701", "3402", "08", "3D"))
            assertNotNull("engellenmeli: $c", TerminalGuard.check(c))
        // Adaptörün kalıcı ayarları
        for (c in listOf("ATPP 0C SV 23", "ATPPS", "at sd 12", "ATBRD 23", "STSBR 115200"))
            assertNotNull("engellenmeli: $c", TerminalGuard.check(c))
    }

    @Test fun allowsReads() {
        for (c in listOf("010C", "01 0C", "0902", "03", "07", "0A", "0600", "22F190", "2101", "1A80", "1902FF", "3E00", "ATDP", "ATSH DA10F1", "ATI", "STDI"))
            assertNull("izin verilmeli: $c", TerminalGuard.check(c))
    }

    @Test fun rejectsGarbage() {
        assertNotNull(TerminalGuard.check(""))
        assertNotNull(TerminalGuard.check("01G"))
        assertNotNull(TerminalGuard.check("010"))      // tek sayıda hane
    }

    @Test fun sessionSendsAndEndsOnClose() {
        val sent = ArrayList<String>()
        val answers = ArrayList<String>()
        val s = TerminalSession { c, a -> answers += "$c=$a" }
        assertNull(s.send("010C"))
        assertNotNull(s.send("04"))                    // engelli: sıraya girmez
        val th = Thread { s.run { c, _ -> sent += c; "41 0C 1A F8" } }
        th.start()
        Thread.sleep(200)
        s.close()
        th.join(2000)
        assertTrue("oturum kapanınca run döner, canlı sorgu sürer", !th.isAlive)
        assertEquals(listOf("010C"), sent)
        assertEquals(listOf("010C=41 0C 1A F8"), answers)
        assertTrue(s.closed)
    }
}
