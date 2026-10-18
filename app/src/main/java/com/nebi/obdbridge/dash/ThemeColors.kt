package com.nebi.obdbridge.dash

import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Kullanıcının tema başına renk seçimleri (2026-10-18): kart yazısı, kart zemini ve saydamlığı, vurgu, hap zemini.
 * null = temanın kendi rengi (otomatik). prefs "colors_<temaId>" altında JSON.
 */
data class ThemeColors(
    val text: Int? = null,
    val card: Int? = null,
    /** Kart opaklığı 0-255 */
    val cardAlpha: Int? = null,
    val accent: Int? = null,
    val pill: Int? = null,
) {
    val isDefault get() = this == ThemeColors()

    fun save(p: SharedPreferences, themeId: String) {
        if (isDefault) { p.edit().remove(key(themeId)).apply(); return }
        val j = JSONObject()
        text?.let { j.put("text", it) }; card?.let { j.put("card", it) }; cardAlpha?.let { j.put("cardAlpha", it) }
        accent?.let { j.put("accent", it) }; pill?.let { j.put("pill", it) }
        p.edit().putString(key(themeId), j.toString()).apply()
    }

    companion object {
        private fun key(id: String) = "colors_$id"

        fun load(p: SharedPreferences, themeId: String): ThemeColors {
            val j = p.getString(key(themeId), null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return ThemeColors()
            fun opt(k: String) = if (j.has(k)) j.getInt(k) else null
            return ThemeColors(opt("text"), opt("card"), opt("cardAlpha"), opt("accent"), opt("pill"))
        }

        /** Seçim paleti: açıktan koyuya nötrler, sonra renkler */
        val PALETTE = intArrayOf(
            0xFFFFFFFF.toInt(), 0xFFC4CAD0.toInt(), 0xFF5F6670.toInt(), 0xFF15181C.toInt(),
            0xFF7FB5DD.toInt(), 0xFF2F7DE1.toInt(), 0xFF2EC4B6.toInt(), 0xFF4CC36A.toInt(),
            0xFFF2D04B.toInt(), 0xFFE08B36.toInt(), 0xFFE0503A.toInt(), 0xFFFF5FA2.toInt(), 0xFF9B6BE0.toInt(),
        )

        /** İki rengin karışımı ([t] = b'nin payı) */
        fun mix(a: Int, b: Int, t: Float): Int {
            fun ch(s: Int) = ((a shr s and 0xFF) * (1 - t) + (b shr s and 0xFF) * t).toInt() shl s
            return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
        }

        fun luma(c: Int) = (0.2126 * (c shr 16 and 0xFF) + 0.7152 * (c shr 8 and 0xFF) + 0.0722 * (c and 0xFF)) / 255
    }
}
