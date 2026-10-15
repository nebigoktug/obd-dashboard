package com.nebi.obdbridge.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/** Gerçek kayıt (can-dumps, tekrar testi) çözücüden geçer: kullanıcının yaptıkları sırasıyla görünmeli. */
class VolvoCanTest {
    private fun dump(): File? = listOf("../can-dumps", "can-dumps").map { File(it, "2026-10-15_3_tekrar-testi.txt.gz") }.firstOrNull { it.exists() }

    @Test fun replayRetest() {
        val f = dump()
        assumeTrue("kayıt yok", f != null)
        VolvoCan.reset()
        var left = 0; var right = 0; var lastL = false; var lastR = false
        val fans = LinkedHashSet<Int>(); val airs = LinkedHashSet<Int>(); val temps = LinkedHashSet<Int>()
        var coolant: Double? = null
        GZIPInputStream(f!!.inputStream()).use { inp ->
            val buf = ByteArray(4096)
            while (true) {
                // Gerçek akış gibi rastgele parçalara bölünmüş okuma (satır ortasından kesilir)
                val n = inp.read(buf, 0, 777)
                if (n < 0) break
                VolvoCan.feed(buf, n)
                val st = VolvoCan.state
                if (st.blinkLeft && !lastL) left++
                if (st.blinkRight && !lastR) right++
                lastL = st.blinkLeft; lastR = st.blinkRight
                if (st.climFan >= 0) fans += st.climFan
                if (st.climAir >= 0) airs += st.climAir
                if (st.climTemp >= 0) temps += st.climTemp
                st.coolant?.let { coolant = it.value }
            }
        }
        // Sol sinyal başta ve sonda (~5 sn, ~10 yanış), sağ sinyal bir kez
        assertTrue("sol yanış $left", left >= 10)
        assertTrue("sağ yanış $right", right >= 4)
        assertTrue("fan $fans", fans.containsAll(listOf(31, 7, 8, 6)))
        assertTrue("hava yönü $airs", airs.containsAll(listOf(0x10, 0x18, 0x1C)))
        assertTrue("ısı $temps", temps.containsAll(listOf(0x10, 0x12, 0x14, 0x16)))
        assertTrue("hararet $coolant", coolant != null && coolant!! in 40.0..100.0)
    }

    @Test fun splitLines() {
        VolvoCan.reset()
        val s = "123 13F041BA 00FE00000000\n456 1760E0".toByteArray()
        VolvoCan.feed(s, s.size)
        val t = "08 00008C0000000000 m\n".toByteArray()
        VolvoCan.feed(t, t.size)
        assertEquals(true, VolvoCan.state.doorOpen)
        assertEquals(true, VolvoCan.state.handbrake)
    }
}
