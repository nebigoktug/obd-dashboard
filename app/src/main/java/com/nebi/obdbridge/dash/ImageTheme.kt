package com.nebi.obdbridge.dash

import com.nebi.obdbridge.R

/**
 * Resim katmanlı temalar (2026-10-10): referans + Gemini ile üretilmiş 6 tema; 2026-10-12 kullanıcının
 * taslaklarından 16 tema daha (aynı çerçeveye yeniden üretildi, tam daire çizilenlerin alt boşluğu kesildi). Hepsi aynı çerçevede, halka merkezleri
 * referansla aynı (ölçüldü, < 3 px); bant yarıçapları temaya göre (tools/dash_assets/make_theme.py, 2400 ölçeği).
 * Eski vektör temalar (gece/gündüz/premium) kullanıcı isteğiyle seçimden kaldırıldı.
 */
data class ImageTheme(
    val id: String,
    val name: String,
    val base: Int,
    val lit: Int,
    /** Tema seçicisindeki küçük önizleme (make_theme.py --thumbs) */
    val thumb: Int,
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
    /** Kart zemininin opaklığı (0-255); çok desenli temalarda (çini, grafiti) yazı okunsun diye daha koyu */
    val cardAlpha: Int? = null,
    /** Kullanıcının kendi teması: katmanlar bu klasörde (UserThemes); yerleşik temalarda null */
    val dir: java.io.File? = null,
) {
    enum class Part(val file: String) { BASE("base.webp"), LIT("lit.webp"), THUMB("thumb.webp") }

    /** Katmanı yükle: yerleşik temada kaynaktan, kullanıcı temasında dosyadan */
    fun decode(res: android.content.res.Resources, part: Part, opts: android.graphics.BitmapFactory.Options? = null): android.graphics.Bitmap? {
        dir?.let { return android.graphics.BitmapFactory.decodeFile(java.io.File(it, part.file).path, opts) }
        val id = when (part) { Part.BASE -> base; Part.LIT -> lit; Part.THUMB -> thumb }
        return android.graphics.BitmapFactory.decodeResource(res, id, opts)
    }

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
            ImageTheme("image", "Varsayılan", R.drawable.dash_base, R.drawable.dash_lit, R.drawable.theme_image_thumb, 211f, 355f,
                0xFF2A2F36.toInt(), 0xFFB4BCC4.toInt(), speedLitLabel = 0xFFC4CBD2.toInt(), accent = 0xFF7FB5DD.toInt()),
            ImageTheme("sport", "Spor", R.drawable.theme_sport_base, R.drawable.theme_sport_lit, R.drawable.theme_sport_thumb, 206f, 347f,
                0xFFFFF2EC.toInt(), 0xFFB0A8A4.toInt(), accent = 0xFFFF6A45.toInt()),
            ImageTheme("oled", "OLED", R.drawable.theme_oled_base, R.drawable.theme_oled_lit, R.drawable.theme_oled_thumb, 217f, 347f,
                0xFF15181C.toInt(), 0xFFC8CCD0.toInt(), accent = 0xFF9CC8F0.toInt()),
            ImageTheme("synthwave", "Synthwave", R.drawable.theme_synthwave_base, R.drawable.theme_synthwave_lit, R.drawable.theme_synthwave_thumb, 202f, 347f,
                0xFF1A0F2E.toInt(), 0xFFD8C8F0.toInt(), accent = 0xFF3FE0F0.toInt()),
            ImageTheme("classic", "Klasik", R.drawable.theme_classic_base, R.drawable.theme_classic_lit, R.drawable.theme_classic_thumb, 210f, 374f,
                0xFF2A2420.toInt(), 0xFFE0C49C.toInt(), accent = 0xFFE8A050.toInt()),
            ImageTheme("glacier", "Buzul", R.drawable.theme_glacier_base, R.drawable.theme_glacier_lit, R.drawable.theme_glacier_thumb, 199f, 380f,
                0xFF0F1E30.toInt(), 0xFFB8CCE0.toInt(), accent = 0xFF8CCBF0.toInt()),
            ImageTheme("day", "Gündüz", R.drawable.theme_day_base, R.drawable.theme_day_lit, R.drawable.theme_day_thumb, 311f, 369f,
                DARK_TXT.toInt(), 0xFF7A828A.toInt(), light = true, accent = 0xFF1F6FD1.toInt(),
                tickLit = 0xFFE8EAED.toInt(), tickDim = 0xFF9AA2A9.toInt()),   // çentikler koyu bandın üstünde
            ImageTheme("cyberpunk", "Cyberpunk", R.drawable.theme_cyberpunk_base, R.drawable.theme_cyberpunk_lit, R.drawable.theme_cyberpunk_thumb, 195f, 408f,
                0xFF101010.toInt(), 0xFFC8D0A0.toInt(), accent = 0xFFE8F040.toInt()),
            ImageTheme("volcanic", "Volkanik", R.drawable.theme_volcanic_base, R.drawable.theme_volcanic_lit, R.drawable.theme_volcanic_thumb, 206f, 358f,
                0xFFFFF0E0.toInt(), 0xFFB8A8A0.toInt(), accent = 0xFFFF6A2A.toInt()),
            ImageTheme("biolum", "Biyolüminesans", R.drawable.theme_biolum_base, R.drawable.theme_biolum_lit, R.drawable.theme_biolum_thumb, 199f, 380f,
                0xFF06262C.toInt(), 0xFFA8D8DC.toInt(), accent = 0xFF3FE0E0.toInt()),
            ImageTheme("retro", "Retro-Fütüristik", R.drawable.theme_retro_base, R.drawable.theme_retro_lit, R.drawable.theme_retro_thumb, 228f, 351f,
                0xFF2A1800.toInt(), 0xFFD8B080.toInt(), accent = 0xFFFFA030.toInt()),
            ImageTheme("quantum", "Kuantum", R.drawable.theme_quantum_base, R.drawable.theme_quantum_lit, R.drawable.theme_quantum_thumb, 213f, 387f,
                0xFFFFFFFF.toInt(), 0xFFC8C0F0.toInt(), accent = 0xFF8A7CFF.toInt()),
            ImageTheme("steam", "Bakır & Buhar", R.drawable.theme_steam_base, R.drawable.theme_steam_lit, R.drawable.theme_steam_thumb, 213f, 380f,
                0xFF2A1E10.toInt(), 0xFFD8C098.toInt(), accent = 0xFFE0A050.toInt()),
            ImageTheme("marble", "Mermer & Altın", R.drawable.theme_marble_base, R.drawable.theme_marble_lit, R.drawable.theme_marble_thumb, 289f, 380f,
                0xFF2A2010.toInt(), 0xFF7A7060.toInt(), light = true, accent = 0xFFB8902A.toInt()),
            ImageTheme("matrix", "Matris", R.drawable.theme_matrix_base, R.drawable.theme_matrix_lit, R.drawable.theme_matrix_thumb, 300f, 358f,
                0xFF002A08.toInt(), 0xFF90D8A0.toInt(), accent = 0xFF30FF60.toInt()),
            ImageTheme("aurora", "Aurora", R.drawable.theme_aurora_base, R.drawable.theme_aurora_lit, R.drawable.theme_aurora_thumb, 213f, 387f,
                0xFF0A1A20.toInt(), 0xFFB8E0D8.toInt(), accent = 0xFF40E0B0.toInt()),
            ImageTheme("crystal", "Kristal", R.drawable.theme_crystal_base, R.drawable.theme_crystal_lit, R.drawable.theme_crystal_thumb, 210f, 387f,
                0xFF151820.toInt(), 0xFFD0D8E8.toInt(), accent = 0xFFB8D8FF.toInt()),
            ImageTheme("cosmic", "Kozmik", R.drawable.theme_cosmic_base, R.drawable.theme_cosmic_lit, R.drawable.theme_cosmic_thumb, 199f, 387f,
                0xFFFFFFFF.toInt(), 0xFFD8C0E8.toInt(), accent = 0xFFD070F0.toInt()),
            ImageTheme("pixel", "Dijital Piksel", R.drawable.theme_pixel_base, R.drawable.theme_pixel_lit, R.drawable.theme_pixel_thumb, 224f, 372f,
                0xFF201000.toInt(), 0xFFF0E0B0.toInt(), accent = 0xFFFFC020.toInt()),
            ImageTheme("molten", "Erimiş Metal", R.drawable.theme_molten_base, R.drawable.theme_molten_lit, R.drawable.theme_molten_thumb, 217f, 351f,
                0xFF2A1400.toInt(), 0xFFE0B890.toInt(), accent = 0xFFFF9A30.toInt()),
            ImageTheme("holo", "Holografik", R.drawable.theme_holo_base, R.drawable.theme_holo_lit, R.drawable.theme_holo_thumb, 199f, 380f,
                0xFF06262C.toInt(), 0xFFA8E0E8.toInt(), accent = 0xFF50E8F0.toInt()),
            ImageTheme("graffiti", "Grafiti", R.drawable.theme_graffiti_base, R.drawable.theme_graffiti_lit, R.drawable.theme_graffiti_thumb, 202f, 390f,
                0xFF101010.toInt(), 0xFFF0F0F0.toInt(), accent = 0xFFFF4FB0.toInt(), cardAlpha = 225),
            ImageTheme("carbon", "Geleceğin Karbonu", R.drawable.theme_carbon_base, R.drawable.theme_carbon_lit, R.drawable.theme_carbon_thumb, 304f, 369f,
                0xFF061826.toInt(), 0xFFA8C8E0.toInt(), accent = 0xFF30B8FF.toInt()),
            // Yerel temalar (2026-10-12)
            ImageTheme("nazar", "Nazar Boncuğu", R.drawable.theme_nazar_base, R.drawable.theme_nazar_lit, R.drawable.theme_nazar_thumb, 213f, 361f,
                0xFF0A1A40.toInt(), 0xFFB8C8E8.toInt(), accent = 0xFFF0C030.toInt()),
            ImageTheme("ebru", "Ebru", R.drawable.theme_ebru_base, R.drawable.theme_ebru_lit, R.drawable.theme_ebru_thumb, 213f, 380f,
                0xFF201810.toInt(), 0xFFE0D0B8.toInt(), accent = 0xFFD08050.toInt()),
            ImageTheme("gokturk", "Göktürk", R.drawable.theme_gokturk_base, R.drawable.theme_gokturk_lit, R.drawable.theme_gokturk_thumb, 202f, 376f,
                0xFFF0E8D8.toInt(), 0xFF1E1A12.toInt(), light = true, accent = 0xFF8A7A60.toInt()),   // sönük rakam koyu: taş rünlerin üstünde okunsun
            ImageTheme("iznik", "İznik Çinisi", R.drawable.theme_iznik_base, R.drawable.theme_iznik_lit, R.drawable.theme_iznik_thumb, 195f, 387f,
                0xFF0A1840.toInt(), 0xFFC8D8F0.toInt(), accent = 0xFF40C8D8.toInt(), cardAlpha = 225),
            ImageTheme("kilim", "Türk Halısı", R.drawable.theme_kilim_base, R.drawable.theme_kilim_lit, R.drawable.theme_kilim_thumb, 202f, 380f,
                0xFFFFF4E8.toInt(), 0xFFE0C8B0.toInt(), accent = 0xFFE05030.toInt()),
            ImageTheme("semazen", "Semazen", R.drawable.theme_semazen_base, R.drawable.theme_semazen_lit, R.drawable.theme_semazen_thumb, 213f, 380f,
                0xFF0A1A30.toInt(), 0xFFC0D0E8.toInt(), accent = 0xFFB8D8F8.toInt()),
            ImageTheme("copper", "Bakır İşçiliği", R.drawable.theme_copper_base, R.drawable.theme_copper_lit, R.drawable.theme_copper_thumb, 217f, 376f,
                0xFF2A1408.toInt(), 0xFFE0B898.toInt(), accent = 0xFFE08A50.toInt()),
        )

        /** Kullanıcının temaları (UserThemes.load) */
        @Volatile var user: List<ImageTheme> = emptyList()

        fun all() = ALL + user

        /** Eski kayıtlar (auto/night/day/premium vektör temaları) ve silinmiş kullanıcı temaları referans temaya düşer */
        fun byId(id: String?): ImageTheme = all().firstOrNull { it.id == id } ?: ALL[0]
        val DAY get() = ALL.first { it.id == "day" }
    }
}
