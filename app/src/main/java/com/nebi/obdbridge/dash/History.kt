package com.nebi.obdbridge.dash

import com.nebi.obdbridge.obd.Sample
import java.io.File

// Otomatik sürüş kaydı ve geçmiş (HEADUNIT.md / DESIGN.md §0 "her sürüşte otomatik kayıt, 30 gün").
// Android'siz: servis [DriveLog]'u her yarım saniyede çağırır; geçmiş ekranı [TripSummary.of] ile dosyaları özetler.

/**
 * Motor canlıyken 2 Hz'de bir satır yazar: files/logs/drive_YYYYMMDD_HHMMSS.csv.
 * [GAP_MS]'den kısa kesintide aynı dosyaya devam eder (BT kopması, köprünün yeniden başlaması yolculuğu bölmesin).
 * Açılışta [KEEP_DAYS] günden eski kayıtları siler.
 */
class DriveLog(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private var out: java.io.Writer? = null
    var file: File? = null; private set
    private var lastWrite = 0L
    private var lastRow = 0L
    private var lastFlush = 0L

    /** @param latest servisin son değerleri; [live] OBD motoru veri alıyor mu */
    fun tick(latest: Map<String, Sample>, live: Boolean) {
        val now = clock()
        val rpm = latest["rpm"]?.takeIf { now - it.ts < 3000 }
        if (!live || rpm == null) {
            if (out != null && now - lastWrite > GAP_MS) close()
            return
        }
        if (now - lastRow < PERIOD_MS) return
        lastRow = now
        val w = out ?: open(now)
        val sb = StringBuilder().append(now)
        for (k in KEYS) {
            sb.append(',')
            latest[k]?.takeIf { now - it.ts < 3000 }?.let { sb.append("%.2f".format(java.util.Locale.US, it.value).trimEnd('0').trimEnd('.')) }
        }
        w.write(sb.append('\n').toString())
        lastWrite = now
        if (now - lastFlush > 10_000) { w.flush(); lastFlush = now }   // çökme/kapanmada en çok 10 sn kayıp
    }

    private fun open(now: Long): java.io.Writer {
        dir.mkdirs()
        prune(now)
        // Kısa kesintiden sonra son dosyaya devam
        val prev = file?.takeIf { it.exists() && now - lastWrite <= GAP_MS }
        val f = prev ?: File(dir, "drive_%1\$tY%1\$tm%1\$td_%1\$tH%1\$tM%1\$tS.csv".format(java.util.Date(now)))
        val w = java.io.FileWriter(f, true).buffered()
        if (prev == null) w.write("ts,${KEYS.joinToString(",")}\n")
        file = f; out = w
        return w
    }

    fun close() {
        try { out?.close() } catch (_: Exception) {}
        out = null
    }

    private fun prune(now: Long) {
        dir.listFiles { f -> f.name.startsWith("drive_") && now - f.lastModified() > KEEP_DAYS * 86_400_000L }
            ?.forEach { it.delete() }
    }

    companion object {
        const val PERIOD_MS = 500L
        const val GAP_MS = 10 * 60_000L
        const val KEEP_DAYS = 30
        val KEYS = listOf("rpm", "speed", "load", "map", "rail", "coolant", "iat", "battery", "maf", "fuel_rate",
            // Ekranın gösterdikleri (2026-10-11): vites, GPS hızı, tahmini yakıt (L/sa), yakıt kesik / boşta (0/1),
            // uyarılar (AlertKind sıra numaralarının bit maskesi)
            "gear", "gps_speed", "fuel_lph", "fuel_cut", "coast", "alerts",
            // Telefon sensörleri (v1.1): yön (°), rakım (m), eğim (%), hava basıncı (hPa), ileri/yanal ivme (g)
            "heading", "altitude", "grade", "baro", "g_long", "g_lat")
    }
}

/** Bir sürüşün özeti: kayıt dosyası Insights + Trip'ten yeniden oynatılarak çıkarılır. */
data class TripSummary(
    val name: String, val size: Long,
    val start: Long, val durationMs: Long, val km: Double, val avgKmh: Double, val maxKmh: Double,
    val liters: Double, val idleMs: Long, val coastMs: Long, val cutKm: Double,
    val harshAccel: Int, val harshBrake: Int, val score: Int, val gearPct: List<Int>,
) {
    val l100 get() = if (km >= 0.5) liters / km * 100 else null

    fun toJson() = org.json.JSONObject().apply {
        put("name", name); put("size", size); put("start", start); put("dur", durationMs); put("km", km)
        put("avg", avgKmh); put("max", maxKmh); put("l", liters); put("idle", idleMs); put("coast", coastMs)
        put("cut", cutKm); put("ha", harshAccel); put("hb", harshBrake); put("score", score)
        put("gears", org.json.JSONArray(gearPct))
    }

    companion object {
        fun fromJson(o: org.json.JSONObject) = TripSummary(
            o.getString("name"), o.getLong("size"), o.getLong("start"), o.getLong("dur"), o.getDouble("km"),
            o.getDouble("avg"), o.getDouble("max"), o.getDouble("l"), o.getLong("idle"), o.getLong("coast"),
            o.getDouble("cut"), o.getInt("ha"), o.getInt("hb"), o.getInt("score"),
            o.getJSONArray("gears").let { a -> List(a.length()) { a.getInt(it) } },
        )

        /**
         * Kayıt satırlarını yeniden oynatır. [gears] öğrenilmiş vites oranları (yalnız okunur: gear()),
         * [fuelK] güncel yakıt katsayısı (kalibrasyon değişince geçmiş tahminler de güncellenir).
         */
        fun of(name: String, size: Long, lines: Sequence<String>, gears: GearEstimator, fuelK: Double): TripSummary? {
            val ins = Insights().apply { fuel.k = fuelK }
            val trip = Trip()
            var first = 0L
            var cols: List<String>? = null
            for (line in lines) {
                if (cols == null) { cols = line.split(','); continue }
                val p = line.split(',')
                if (p.size != cols.size) continue
                val ts = p[0].toLongOrNull() ?: continue
                fun v(k: String) = cols.indexOf(k).takeIf { it > 0 }?.let { p[it].toDoubleOrNull() }
                if (first == 0L) first = ts
                val rpm = v("rpm"); val speed = v("speed")
                val gear = if (rpm != null && speed != null) gears.gear(rpm, speed) else null
                ins.update(Inputs(ts, rpm, speed, v("load"), v("map"), v("coolant"), v("battery"), v("rail"), gear, null, true, v("maf"), v("fuel_rate")))
                speed?.let { trip.update(Sample("speed", it, ts)) }
            }
            if (first == 0L || trip.durationMs < 60_000) return null   // 1 dk'dan kısa: motor çalıştırıp kapatma
            val st = ins.stats
            val gTot = (1..7).sumOf { st.gearMs[it] }
            return TripSummary(name, size, first, trip.durationMs, trip.km, trip.avgKmh, trip.maxKmh, st.liters,
                st.idleMs, st.coastMs, st.cutKm, st.harshAccel, st.harshBrake, st.score(trip),
                (1..7).map { if (gTot == 0L) 0 else (st.gearMs[it] * 100 / gTot).toInt() }.dropLastWhile { it == 0 })
        }
    }
}

/** Kayıt klasörünü özetler; özetler summaries.json'da önbelleklenir (dosya boyu değişmediyse yeniden hesaplanmaz). */
class HistoryIndex(private val dir: File) {
    fun load(gears: GearEstimator, fuelK: Double): List<TripSummary> {
        val cacheF = File(dir, "summaries.json")
        val cache = HashMap<String, TripSummary>()
        var cachedK = Double.NaN
        try {
            val o = org.json.JSONObject(cacheF.readText())
            cachedK = o.optDouble("k", Double.NaN)
            val a = o.getJSONArray("trips")
            for (i in 0 until a.length()) TripSummary.fromJson(a.getJSONObject(i)).let { cache[it.name] = it }
        } catch (_: Exception) {}
        if (cachedK != fuelK) cache.clear()   // yakıt kalibrasyonu değişti: hepsini yeniden hesapla
        val files = dir.listFiles { f -> f.name.startsWith("drive_") && f.name.endsWith(".csv") }?.sortedByDescending { it.name } ?: emptyList()
        val out = ArrayList<TripSummary>()
        for (f in files) {
            val c = cache[f.name]
            val s = if (c != null && c.size == f.length()) c
            else f.bufferedReader().useLines { TripSummary.of(f.name, f.length(), it, gears, fuelK) }
            if (s != null) out += s
        }
        try {
            cacheF.writeText(org.json.JSONObject().put("k", fuelK)
                .put("trips", org.json.JSONArray(out.map { it.toJson() })).toString())
        } catch (_: Exception) {}
        return out
    }

    companion object {
        /** Pazartesi başlayan hafta: (bu hafta, geçen hafta) toplamları */
        fun weeks(trips: List<TripSummary>, now: Long): Pair<WeekTotals, WeekTotals> {
            val cal = java.util.Calendar.getInstance().apply {
                timeInMillis = now; firstDayOfWeek = java.util.Calendar.MONDAY
                set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.MONDAY)
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            }
            val thisStart = cal.timeInMillis
            val lastStart = thisStart - 7 * 86_400_000L
            return WeekTotals.of(trips.filter { it.start >= thisStart }) to
                WeekTotals.of(trips.filter { it.start in lastStart until thisStart })
        }
    }
}

data class WeekTotals(val trips: Int, val km: Double, val ms: Long, val liters: Double, val score: Int?) {
    val l100 get() = if (km >= 1) liters / km * 100 else null

    companion object {
        fun of(t: List<TripSummary>) = WeekTotals(t.size, t.sumOf { it.km }, t.sumOf { it.durationMs }, t.sumOf { it.liters },
            if (t.isEmpty()) null else (t.sumOf { it.score * it.km } / maxOf(0.1, t.sumOf { it.km })).toInt())
    }
}
