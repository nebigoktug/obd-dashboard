package com.nebi.obdbridge.dash

import org.json.JSONArray
import org.json.JSONObject

/**
 * Kilometreye bağlı bakım hatırlatıcı. Km aracın kendi sayacından gelir (ESP32 kutusu, Volvo hattı);
 * kutu yokken son bilinen değer kullanılır. Aralıklar ve son bakım km'si kullanıcının girdiği değerlerdir.
 */
class Maintenance {
    data class Item(val id: String, val name: String, var intervalKm: Int, var lastKm: Int? = null) {
        fun remaining(odo: Int): Int? = lastKm?.let { it + intervalKm - odo }
    }

    /** Örnek aralıklar: araç kitapçığına ya da ustanın söylediğine göre değiştirilir */
    val items = listOf(
        Item("oil", "Yağ ve yağ filtresi", 15_000),
        Item("air", "Hava filtresi", 30_000),
        Item("cabin", "Polen filtresi", 30_000),
        Item("fuel", "Yakıt filtresi", 60_000),
        Item("belt", "Triger seti", 120_000),
    )

    /** Son bilinen kilometre (aracın sayacından) */
    var odoKm: Int? = null

    fun byId(id: String) = items.firstOrNull { it.id == id }

    /** Yaklaşan ya da geçen bakımlar: (bakım, kalan km), en acil önce */
    fun due(warnKm: Int = WARN_KM): List<Pair<Item, Int>> {
        val odo = odoKm ?: return emptyList()
        return items.mapNotNull { i -> i.remaining(odo)?.takeIf { it <= warnKm }?.let { i to it } }.sortedBy { it.second }
    }

    fun save(): String = JSONObject().apply {
        odoKm?.let { put("odo", it) }
        put("items", JSONArray().apply {
            for (i in items) put(JSONObject().put("id", i.id).put("interval", i.intervalKm).apply { i.lastKm?.let { put("last", it) } })
        })
    }.toString()

    fun load(s: String?) {
        if (s.isNullOrBlank()) return
        try {
            val o = JSONObject(s)
            if (o.has("odo")) odoKm = o.getInt("odo")
            val a = o.optJSONArray("items") ?: return
            for (k in 0 until a.length()) {
                val j = a.getJSONObject(k)
                val i = byId(j.getString("id")) ?: continue
                i.intervalKm = j.optInt("interval", i.intervalKm)
                i.lastKm = if (j.has("last")) j.getInt("last") else null
            }
        } catch (_: org.json.JSONException) {}
    }

    companion object {
        const val WARN_KM = 1000
        const val PREF = "maintenance"

        fun km(n: Int) = "%,d".format(n).replace(',', '.')

        /** "3.200 km kaldı" / "800 km geçti" */
        fun remainingText(r: Int) = if (r >= 0) "${km(r)} km kaldı" else "${km(-r)} km geçti"
    }
}
