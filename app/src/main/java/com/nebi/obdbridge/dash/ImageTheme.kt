package com.nebi.obdbridge.dash

import com.nebi.obdbridge.R

/**
 * Resim katmanlı temalar (2026-10-10): referans + Gemini ile üretilmiş 6 tema. Hepsi aynı çerçevede, halka merkezleri
 * referansla aynı (ölçüldü, < 3 px); bant yarıçapları temaya göre (tools/dash_assets/make_theme.py, 2400 ölçeği).
 * Eski vektör temalar (gece/gündüz/premium) kullanıcı isteğiyle seçimden kaldırıldı.
 */
data class ImageTheme(
    val id: String,
    val name: String,
    val base: Int,
    val lit: Int,
    val bandIn: Float,
    val bandOut: Float,
    /** Kadran rakamı: ışıklı dilimin üstünde / sönük bantta */
    val litLabel: Int,
    val dimLabel: Int,
    /** Hız kadranında ışıklı dilim üstü (referansta açık renk); null = [litLabel] */
    val speedLitLabel: Int? = null,
    /** Açık zemin (gündüz): kartlar açık cam, yazılar koyu */
    val light: Boolean = false,
    val accent: Int,
    /** Kadran çentikleri (resimlerden silindi, canlı çiziliyor); null = rakam renkleri */
    val tickLit: Int? = null,
    val tickDim: Int? = null,
) {
    /** Üst çubuk ve göstergelerin kullandığı renk takımı */
    fun dash() = DashTheme.IMAGE.copy(
        text = if (light) 0xFF15181C.toInt() else 0xFFFFFFFF.toInt(),
        dim = if (light) 0xFF5F6670.toInt() else 0xFF9AA2A9.toInt(),
        track = if (light) 0xFFC5CAD0.toInt() else 0xFF2E343B.toInt(),
        bg = if (light) 0xFFE6E8EB.toInt() else 0xFF15181C.toInt(),
        accent = accent,
    )

    companion object {
        private const val DARK_TXT = 0xFF20252B
        val ALL = listOf(
            ImageTheme("image", "Görsel (referans)", R.drawable.dash_base, R.drawable.dash_lit, 211f, 355f,
                0xFF2A2F36.toInt(), 0xFFB4BCC4.toInt(), speedLitLabel = 0xFFC4CBD2.toInt(), accent = 0xFF7FB5DD.toInt()),
            ImageTheme("sport", "Spor", R.drawable.theme_sport_base, R.drawable.theme_sport_lit, 206f, 347f,
                0xFFFFF2EC.toInt(), 0xFFB0A8A4.toInt(), accent = 0xFFFF6A45.toInt()),
            ImageTheme("oled", "OLED", R.drawable.theme_oled_base, R.drawable.theme_oled_lit, 217f, 347f,
                0xFF15181C.toInt(), 0xFFC8CCD0.toInt(), accent = 0xFF9CC8F0.toInt()),
            ImageTheme("synthwave", "Synthwave", R.drawable.theme_synthwave_base, R.drawable.theme_synthwave_lit, 202f, 347f,
                0xFF1A0F2E.toInt(), 0xFFD8C8F0.toInt(), accent = 0xFF3FE0F0.toInt()),
            ImageTheme("classic", "Klasik", R.drawable.theme_classic_base, R.drawable.theme_classic_lit, 210f, 374f,
                0xFF2A2420.toInt(), 0xFFE0C49C.toInt(), accent = 0xFFE8A050.toInt()),
            ImageTheme("glacier", "Buzul", R.drawable.theme_glacier_base, R.drawable.theme_glacier_lit, 199f, 380f,
                0xFF0F1E30.toInt(), 0xFFB8CCE0.toInt(), accent = 0xFF8CCBF0.toInt()),
            ImageTheme("day", "Gündüz", R.drawable.theme_day_base, R.drawable.theme_day_lit, 311f, 369f,
                DARK_TXT.toInt(), 0xFF7A828A.toInt(), light = true, accent = 0xFF1F6FD1.toInt(),
                tickLit = 0xFFE8EAED.toInt(), tickDim = 0xFF9AA2A9.toInt()),   // çentikler koyu bandın üstünde
        )

        /** Eski kayıtlar (auto/night/day/premium vektör temaları) referans temaya düşer */
        fun byId(id: String?): ImageTheme = ALL.firstOrNull { it.id == id } ?: ALL[0]
        val DAY get() = ALL.first { it.id == "day" }
    }
}
