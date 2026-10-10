package com.nebi.obdbridge.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class BleChoiceTest {
    private fun u(x: String) = UUID.fromString("0000$x-0000-1000-8000-00805f9b34fb")
    private val n = 0x10; private val w = 0x08; private val wnr = 0x04; private val r = 0x02

    @Test fun knownFff0Layout() {
        val p = BleChoice.pick(listOf(
            BleChoice.Service(u("1800"), listOf(BleChoice.Char(u("2a00"), r))),
            BleChoice.Service(u("fff0"), listOf(BleChoice.Char(u("fff1"), n), BleChoice.Char(u("fff2"), w or wnr))),
        ))!!
        assertEquals(u("fff1"), p.notify); assertEquals(u("fff2"), p.write); assertEquals(false, p.writeNoResponse)
    }

    @Test fun hm10SingleCharacteristic() {
        val p = BleChoice.pick(listOf(BleChoice.Service(u("ffe0"), listOf(BleChoice.Char(u("ffe1"), n or wnr)))))!!
        assertEquals(u("ffe1"), p.notify); assertEquals(u("ffe1"), p.write); assertTrue(p.writeNoResponse)
    }

    @Test fun unknownVendorServiceFallback() {
        val vendor = UUID.fromString("12345678-1234-1234-1234-123456789abc")
        val p = BleChoice.pick(listOf(
            BleChoice.Service(u("180a"), listOf(BleChoice.Char(u("2a29"), r or n))),   // cihaz bilgisi: atlanmalı
            BleChoice.Service(vendor, listOf(BleChoice.Char(UUID.randomUUID(), wnr), BleChoice.Char(UUID.randomUUID(), n))),
        ))!!
        assertEquals(vendor, p.service)
    }

    @Test fun nothingUsable() {
        assertNull(BleChoice.pick(listOf(BleChoice.Service(u("1800"), listOf(BleChoice.Char(u("2a00"), r))))))
    }
}
