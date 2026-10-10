package com.nebi.obdbridge.dash

// Araç profili (2026-10-10): uygulama forumda her OBD'li araç için paylaşılacak. S40 1.6D'ye özel
// varsayımlar (dizel uyarıları, 5 ileri vites, 0–5000 devir ölçeği, VOLVO logosu) buradan gelir.
// İlk açılış sihirbazında sorulur, Ayarlar > Araç'tan değiştirilir.

enum class Fuel { DIESEL, PETROL }
enum class Gearbox { MANUAL, AUTO }

data class Vehicle(
    val fuel: Fuel = Fuel.DIESEL,
    val gearbox: Gearbox = Gearbox.MANUAL,
    val gears: Int = 5,
    val turbo: Boolean = true,
    /** Kırmızı bölge başlangıcı (rpm) */
    val redline: Int = 4500,
    /** Hız göstergesi üst sınırı (km/h) */
    val speedMax: Int = 200,
    /** Referans görseldeki VOLVO yazısı */
    val logo: Boolean = true,
) {
    /** Devir ölçeği: kırmızı bölgenin üstündeki ilk tam bin (4500 → 5000, 6500 → 7000, 7000 → 8000) */
    val rpmMax get() = (redline / 1000 + 1) * 1000

    /** Tipik vites oranları (rpm per km/h); yalnız numaralandırma için, gerçek oranlar öğrenilir */
    fun gearPrior(): DoubleArray = when (gears) {
        4 -> doubleArrayOf(118.0, 63.0, 40.0, 29.0)
        6 -> doubleArrayOf(118.0, 66.0, 44.0, 33.0, 27.0, 22.5)
        else -> doubleArrayOf(118.0, 63.0, 42.0, 32.0, 25.5)
    }

    val manual get() = gearbox == Gearbox.MANUAL

    fun toJson(): String = org.json.JSONObject().put("fuel", fuel.name).put("gearbox", gearbox.name).put("gears", gears)
        .put("turbo", turbo).put("redline", redline).put("speedMax", speedMax).put("logo", logo).toString()

    companion object {
        /** Uygulamanın her yerinde geçerli profil (Activity açılışta ayarlardan yükler) */
        @Volatile var current = Vehicle()

        fun fromJson(s: String?): Vehicle? = if (s == null) null else try {
            val o = org.json.JSONObject(s)
            Vehicle(Fuel.valueOf(o.getString("fuel")), Gearbox.valueOf(o.getString("gearbox")), o.getInt("gears"),
                o.getBoolean("turbo"), o.getInt("redline"), o.optInt("speedMax", 200), o.optBoolean("logo", false))
        } catch (_: Exception) { null }
    }
}
