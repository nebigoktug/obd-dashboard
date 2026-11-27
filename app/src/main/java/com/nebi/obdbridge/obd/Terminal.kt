package com.nebi.obdbridge.obd

import com.nebi.obdbridge.t
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Ham terminal güvenliği (YAPILACAKLAR §3, iş planı 2026-11-27: uygulama araca yalnız okuma yapar).
 * Araca giden komutlarda izin listesi: yalnız okuma servisleri. Kod silme (04, 14), yazma (2E, 3B, 3D), rutin (31),
 * oturum (10), sıfırlama (11), güvenlik erişimi (27), aktüatör (08, 2F, 30), yazılım yükleme (34-37) ve tanınmayan her şey engelli.
 * AT/ST komutlarında adaptörü kalıcı değiştirenler (programlanabilir parametreler, kayıtlı bayt, baud) engelli; geçici ayarlar
 * serbest, terminal kapanınca motor adaptörü yeniden kurar.
 */
object TerminalGuard {
    /** Okuma servisleri: OBD 01 02 03 05 06 07 09 0A, KWP 18 1A 21 23, UDS 19 22, bağlantıyı açık tutma 3E */
    val READ_SERVICES = setOf(0x01, 0x02, 0x03, 0x05, 0x06, 0x07, 0x09, 0x0A, 0x18, 0x19, 0x1A, 0x21, 0x22, 0x23, 0x3E)

    /** Adaptörün kalıcı belleğine yazan ya da bağlantı hızını değiştiren komutlar (boşluksuz, büyük harf, önek) */
    private val AT_BLOCKED = listOf("ATPP", "ATSD", "ATBRD", "ATBRT", "STSBR", "STWBR", "STPBR", "STSAVE", "STUFAC", "STFAC")

    /** Engellenme nedeni ya da null (gönderilebilir) */
    fun check(input: String): String? {
        val c = input.uppercase().replace(" ", "").replace("\t", "")
        if (c.isEmpty()) return t("Boş komut")
        if (c.startsWith("AT") || c.startsWith("ST")) {
            return if (AT_BLOCKED.any { c.startsWith(it) }) t("Adaptörün kalıcı ayarını değiştiren komut engelli") else null
        }
        if (!c.all { it in "0123456789ABCDEF" } || c.length % 2 != 0) return t("Komut AT/ST ya da çift sayıda onaltılık haneden oluşmalı")
        val svc = c.substring(0, 2).toInt(16)
        return if (svc in READ_SERVICES) null else t("$%02X servisi engelli: uygulama araca yalnız okuma yapar", svc)
    }
}

/**
 * Açık terminal oturumu: ekran komutları [send] ile sıraya koyar, motorun iş parçacığı [run] içinde sırayla gönderir.
 * [run] dönene kadar canlı sorgu durur; ekran kapanınca [close] (ya da [IDLE_MS] boyunca komut gelmezse) döner.
 */
class TerminalSession(private val onAnswer: (cmd: String, answer: String) -> Unit) {
    private val queue = LinkedBlockingQueue<String>()
    @Volatile var closed = false; private set

    fun send(cmd: String): String? {
        TerminalGuard.check(cmd)?.let { return it }
        queue.put(cmd.trim())
        return null
    }

    fun close() { closed = true; queue.put(CLOSE) }

    fun run(request: (String, Long) -> String) {
        while (!closed) {
            val cmd = queue.poll(IDLE_MS, TimeUnit.MILLISECONDS) ?: break   // unutulan terminal göstergeyi sonsuza kadar durdurmasın
            if (cmd === CLOSE) break
            // İkinci kontrol: sıraya başka yoldan girmiş olsa bile engelli komut araca gitmez
            val blocked = TerminalGuard.check(cmd)
            onAnswer(cmd, blocked ?: request(cmd, TIMEOUT_MS))
        }
        closed = true
    }

    companion object {
        private val CLOSE = String(charArrayOf('\u0000'))
        const val IDLE_MS = 10 * 60_000L
        const val TIMEOUT_MS = 4000L
    }
}
