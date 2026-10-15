package com.nebi.obdbridge.obd

/**
 * ESP32 kutusunun ham akışından Volvo P1 (S40/V50/C30/C70) sinyalleri. Yalnız dinleme; tablo docs/can-sinyalleri.md.
 * [CanRecorder] okuduğu her parçayı [feed] ile verir; satırlar parça sınırında bölünebilir.
 * Başka marka araçta bu kimlikler gelmez, değerler boş kalır ve uygulama OBD ile devam eder.
 */
object VolvoCan {
    /** Bu süre içinde gelmeyen değer yok sayılır */
    private const val FRESH_MS = 3000L

    class State {
        @Volatile var coolant: Sample? = null
        /** Sinyal lambaları şu an yanıyor mu (gösterge oklarıyla aynı anda) */
        @Volatile var blinkLeft = false
        @Volatile var blinkRight = false
        @Volatile var blinkTs = 0L
        @Volatile var doorOpen: Boolean? = null
        @Volatile var doorTs = 0L
        @Volatile var handbrake: Boolean? = null
        @Volatile var handbrakeTs = 0L
        /** Manuel klima: ısı düğmesi (ham, tık başına 2), fan seviyesi (31 = buz çözme en yüksek), hava yönü bitleri */
        @Volatile var climTemp = -1
        @Volatile var climFan = -1
        @Volatile var climAir = -1
        @Volatile var climTs = 0L
        /** Kullanıcı klimayı en son ne zaman değiştirdi (ilk okuma sayılmaz) */
        @Volatile var climChangedAt = 0L
    }

    @Volatile var state = State()
        private set
    /** Hattan gelen hararet: köprü servisi bunu OBD değerlerinin yanına koyar (gösterge ve sürüş kaydı aynı yerden okur) */
    @Volatile var onCoolant: ((Sample) -> Unit)? = null

    private val partial = StringBuilder()

    fun reset() {
        synchronized(partial) { partial.setLength(0) }
        state = State()
    }

    fun fresh(ts: Long, now: Long = System.currentTimeMillis()) = now - ts < FRESH_MS

    fun feed(buf: ByteArray, n: Int) {
        synchronized(partial) {
            for (i in 0 until n) {
                val c = buf[i].toInt().toChar()
                if (c == '\n') { line(partial); partial.setLength(0) }
                else if (c != '\r' && partial.length < 80) partial.append(c)
            }
        }
    }

    private fun hex(s: CharSequence, at: Int): Int {
        fun d(ch: Char) = Character.digit(ch, 16)
        if (at + 1 >= s.length) return -1
        val a = d(s[at]); val b = d(s[at + 1])
        return if (a < 0 || b < 0) -1 else a * 16 + b
    }

    /** "<ms> <kimlik> <veri> [m]" */
    private fun line(s: CharSequence) {
        if (s.isEmpty() || s[0] == '#') return
        val sp1 = s.indexOf(' ').takeIf { it > 0 } ?: return
        val sp2 = (sp1 + 1 until s.length).firstOrNull { s[it] == ' ' } ?: s.length
        if (sp2 - sp1 - 1 != 8) return                      // yalnız 29 bitlik kimlikler
        val id = s.substring(sp1 + 1, sp2)
        val d = sp2 + 1                                      // veri başlangıcı
        fun b(k: Int) = hex(s, d + 2 * k)
        val now = System.currentTimeMillis()
        val st = state
        when (id) {
            "19E00006" -> b(4).takeIf { it >= 0 }?.let { val x = Sample("coolant", it - 60.0, now); st.coolant = x; onCoolant?.invoke(x) }
            "021204B8" -> b(1).takeIf { it >= 0 }?.let {
                st.blinkLeft = it and 0x08 != 0; st.blinkRight = it and 0x10 != 0; st.blinkTs = now
            }
            "13F041BA" -> b(1).takeIf { it >= 0 }?.let { st.doorOpen = it and 0x20 != 0; st.doorTs = now }
            "1760E008" -> b(2).takeIf { it >= 0 }?.let { st.handbrake = it and 0x08 != 0; st.handbrakeTs = now }
            "12404002" -> {
                val t = b(0); val f = b(3); val a = b(7)
                if (t < 0 || f < 0 || a < 0) return
                val temp = t; val fan = f and 0x1F; val air = a and 0x1C
                if (st.climTs != 0L && (temp != st.climTemp || fan != st.climFan || air != st.climAir)) st.climChangedAt = now
                st.climTemp = temp; st.climFan = fan; st.climAir = air; st.climTs = now
            }
        }
    }

    /** Hararet hattan geliyorsa OBD'den sormaya gerek yok */
    fun coolantFresh() = state.coolant?.let { fresh(it.ts) } == true

    /** Klima değişince kısa süre gösterilecek metin; yoksa null */
    fun climateText(now: Long = System.currentTimeMillis(), showMs: Long = 3500): String? {
        val st = state
        if (now - st.climChangedAt > showMs || !fresh(st.climTs, now)) return null
        val fan = if (st.climFan >= 31) "en yüksek" else st.climFan.toString()
        val air = listOf(0x10 to "yüz", 0x08 to "gövde", 0x04 to "ayak").filter { st.climAir and it.first != 0 }
            .joinToString("+") { it.second }.ifEmpty { "-" }
        return "Isı ${st.climTemp / 2} · Fan $fan · $air"
    }
}
