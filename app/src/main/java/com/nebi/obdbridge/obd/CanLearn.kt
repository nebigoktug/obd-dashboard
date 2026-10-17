package com.nebi.obdbridge.obd

/**
 * Öğrenme modu çekirdeği: kullanıcı bir tuşa basarken hatta hangi bitlerin değiştiğini bulur.
 *
 * Yöntem (elle yaptığımız elemenin aynısı):
 *  - Dinlerken, deneme penceresi dışında değişen her bit "gürültü" sayılır (sayaçlar, sıcaklıklar, devir).
 *    Başta [BASELINE_MS] boyunca yalnız gürültü öğrenilir; denemeler arasında da öğrenme sürer.
 *  - Deneme penceresinde gürültü olmayan bir bit değişirse aday olur.
 *  - Deneme bitince [GRACE_MS] boyunca gürültü öğrenilmez: tuşu geç bırakmak bitin kendisini gürültüye atmasın.
 * Birden çok denemenin birleştirilmesi [combine] ile yapılır.
 *
 * Yalnız dinler; hatta hiçbir şey göndermez. [CanRecorder] ham akışı [feed] ile verir.
 */
object CanLearn {
    const val BASELINE_MS = 10_000L
    const val TRIAL_MS = 6_000L
    const val GRACE_MS = 2_500L

    enum class Phase { OFF, BASELINE, READY, TRIAL }

    /** Bir baytın bir ya da birkaç biti. [bus] 0 motor hattı, 1 konfor hattı. */
    data class Bits(val bus: Int, val id: String, val byte: Int, val mask: Int) {
        val busName get() = if (bus == 1) "konfor" else "motor"
        override fun toString() = "%s %s b%d 0x%02X".format(busName, id, byte, mask)
    }

    /** Denemede değişen bitler ve değerler: önce / sonra (gösterim için). */
    data class Change(val bits: Bits, val before: Int, val after: Int)

    private class Track(val last: IntArray = IntArray(8) { -1 }, val noise: IntArray = IntArray(8), val hit: IntArray = IntArray(8),
                        val first: IntArray = IntArray(8) { -1 })

    private val lock = Any()
    private val tracks = HashMap<String, Track>()
    private val partial = StringBuilder()
    @Volatile var phase = Phase.OFF
        private set
    private var phaseEnd = 0L
    private var graceUntil = 0L
    @Volatile var frames = 0L
        private set
    @Volatile var lastFrameAt = 0L
        private set

    /** Canlı izleme ([watch]): her değişimde artar */
    private var watched: Bits? = null
    @Volatile var watchHits = 0
        private set

    /** Hata ayıklama ve testler için saat (gerçek kullanımda duvar saati) */
    var clock: () -> Long = { System.currentTimeMillis() }

    fun begin() = synchronized(lock) {
        tracks.clear(); partial.setLength(0); frames = 0; watched = null; watchHits = 0
        phase = Phase.BASELINE; phaseEnd = clock() + BASELINE_MS; graceUntil = 0
    }

    fun end() = synchronized(lock) { phase = Phase.OFF; tracks.clear(); watched = null }

    /** Kalan süre (ms); taban öğrenme ya da deneme bitince faz kendiliğinden ilerler */
    fun remaining(): Long = synchronized(lock) {
        advance(clock())
        if (phase == Phase.BASELINE || phase == Phase.TRIAL) maxOf(0L, phaseEnd - clock()) else 0L
    }

    fun startTrial(ms: Long = TRIAL_MS) = synchronized(lock) {
        if (phase != Phase.READY) return@synchronized false
        for (t in tracks.values) { t.hit.fill(0); t.first.fill(-1) }
        phase = Phase.TRIAL; phaseEnd = clock() + ms
        true
    }

    /** Deneme bitti mi; bittiyse sonuç döner (bir kez). */
    fun trialResult(): List<Change>? = synchronized(lock) {
        if (phase != Phase.READY || pending == null) return@synchronized null
        pending.also { pending = null }
    }

    private var pending: List<Change>? = null

    private fun advance(now: Long) {
        if (phase == Phase.BASELINE && now >= phaseEnd) phase = Phase.READY
        if (phase == Phase.TRIAL && now >= phaseEnd) {
            val out = ArrayList<Change>()
            for ((k, t) in tracks) for (b in 0 until 8) {
                val m = t.hit[b] and t.noise[b].inv()
                if (m != 0) {
                    val (bus, id) = k.split(':')
                    out += Change(Bits(bus.toInt(), id, b, m), t.first[b], t.last[b])
                }
            }
            pending = out
            phase = Phase.READY; graceUntil = now + GRACE_MS
        }
    }

    /** Bu bitleri canlı izle: kullanıcıya "kutucuk yandı mı" diye sormak için */
    fun watch(b: Bits?) = synchronized(lock) { watched = b; watchHits = 0 }

    fun feed(buf: ByteArray, n: Int) {
        if (phase == Phase.OFF) return
        synchronized(lock) {
            for (i in 0 until n) {
                val c = buf[i].toInt().toChar()
                if (c == '\n') { line(partial); partial.setLength(0) }
                else if (c != '\r' && partial.length < 80) partial.append(c)
            }
        }
    }

    /** "<ms> <kimlik> <veri> [m]" */
    private fun line(s: CharSequence) {
        if (s.isEmpty() || s[0] == '#') return
        val p = s.split(' ')
        if (p.size < 3) return
        val id = p[1]
        if (id.length != 8 && id.length != 3) return
        if (id.startsWith("18DA")) return                       // OBD istek/cevapları: uygulamanın kendi sorguları
        val data = p[2]
        if (data.length % 2 != 0 || data.length > 16) return
        val bus = if (p.size > 3 && p[3] == "m") 1 else 0
        val now = clock()
        advance(now)
        frames++; lastFrameAt = now
        val t = tracks.getOrPut("$bus:$id") { Track() }
        val learnNoise = phase == Phase.BASELINE || (phase == Phase.READY && now >= graceUntil)
        val w = watched
        for (b in 0 until data.length / 2) {
            val v = Character.digit(data[2 * b], 16) * 16 + Character.digit(data[2 * b + 1], 16)
            if (v < 0) return
            val old = t.last[b]
            t.last[b] = v
            if (old < 0 || old == v) continue
            val diff = old xor v
            if (learnNoise) t.noise[b] = t.noise[b] or diff
            if (phase == Phase.TRIAL) {
                if (t.hit[b] and diff == 0 && t.first[b] < 0) t.first[b] = old
                t.hit[b] = t.hit[b] or diff
            }
            if (w != null && w.bus == bus && w.id == id && w.byte == b && diff and w.mask != 0) watchHits++
        }
    }

    /**
     * Denemeleri birleştir: tüm denemelerde değişen bitler kesin; bazılarında değişen bitler belirsiz.
     * Sonradan gürültü olduğu anlaşılan bitler ([isNoise]) elenir.
     */
    data class Verdict(val sure: List<Bits>, val maybe: List<Bits>)

    fun combine(trials: List<List<Change>>): Verdict {
        val count = HashMap<Triple<Int, String, Int>, IntArray>()       // (bus, id, bayt) → bit başına deneme sayısı
        for (tr in trials) {
            val seen = HashSet<Triple<Int, String, Int>>()
            for (c in tr) {
                val k = Triple(c.bits.bus, c.bits.id, c.bits.byte)
                val arr = count.getOrPut(k) { IntArray(8) }
                for (bit in 0 until 8) if (c.bits.mask shr bit and 1 == 1) arr[bit]++
                seen += k
            }
        }
        val sure = ArrayList<Bits>(); val maybe = ArrayList<Bits>()
        for ((k, arr) in count) {
            var s = 0; var m = 0
            for (bit in 0 until 8) {
                if (arr[bit] == trials.size) s = s or (1 shl bit) else if (arr[bit] * 2 >= trials.size) m = m or (1 shl bit)
            }
            val noise = isNoise(k.first, k.second, k.third)
            s = s and noise.inv(); m = m and noise.inv()
            if (s != 0) sure += Bits(k.first, k.second, k.third, s)
            if (m != 0) maybe += Bits(k.first, k.second, k.third, m)
        }
        return Verdict(sure.sortedWith(compareBy({ it.bus }, { it.id }, { it.byte })), maybe.sortedWith(compareBy({ it.bus }, { it.id }, { it.byte })))
    }

    private fun isNoise(bus: Int, id: String, byte: Int): Int = synchronized(lock) { tracks["$bus:$id"]?.noise?.get(byte) ?: 0 }

    /** Şu anki değer (canlı gösterim için); yoksa -1 */
    fun value(b: Bits): Int = synchronized(lock) { tracks["${b.bus}:${b.id}"]?.last?.get(b.byte) ?: -1 }
}
