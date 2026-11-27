package com.nebi.obdbridge

import org.junit.Assert.assertEquals
import org.junit.Test

class I18nTest {
    @Test fun systemLanguageSettingWins() {
        assertEquals("en", L.systemChoice(L.SYSTEM, "en"))   // sistemden İngilizce seçildi
        assertEquals("tr", L.systemChoice("en", "tr"))
        assertEquals("tr", L.systemChoice(L.USER, "tr"))      // sistemde dil seçmek paketi bırakır
    }

    @Test fun systemDefaultMeansPhoneLanguage() {
        assertEquals(L.SYSTEM, L.systemChoice("en", null))   // sistemde "varsayılan" seçildi
        assertEquals(L.SYSTEM, L.systemChoice(L.SYSTEM, null))
        assertEquals(L.USER, L.systemChoice(L.USER, null))    // kullanıcı paketi sistem listesinde yok, korunur
        assertEquals(L.USER, L.systemChoice(L.USER, "de"))
    }
}
