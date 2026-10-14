package com.nebi.obdbridge.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

// Adaptör bağlantı türleri (2026-10-11, forum sürümü): klasik Bluetooth (SPP), Bluetooth LE (GATT) ve Wi-Fi (TCP).
// OBD motoru yalnız bayt akışı görür; hangi türle bağlanıldığını bilmez.

enum class LinkType { SPP, BLE, WIFI }

/** Kayıtlı adaptör: tür + adres (SPP/BLE için MAC, Wi-Fi için "host:port"). */
data class AdapterSpec(val type: LinkType, val address: String) {
    /** Protokol önbelleği anahtarı (adaptör başına) */
    val key get() = "${type.name}_$address"
}

/** Bağlı adaptörle çift yönlü bayt akışı. [read] bloklar; bağlantı kapanınca -1. */
interface Link : Closeable {
    fun write(b: ByteArray, off: Int = 0, len: Int = b.size)
    fun read(buf: ByteArray): Int
}

class SppLink(private val sock: BluetoothSocket) : Link {
    private val out = sock.outputStream
    private val inp = sock.inputStream
    override fun write(b: ByteArray, off: Int, len: Int) = synchronized(out) { out.write(b, off, len); out.flush() }
    override fun read(buf: ByteArray) = inp.read(buf)
    override fun close() = sock.close()
}

class TcpLink(private val sock: Socket) : Link {
    private val out = sock.getOutputStream()
    private val inp = sock.getInputStream()
    override fun write(b: ByteArray, off: Int, len: Int) = synchronized(out) { out.write(b, off, len); out.flush() }
    override fun read(buf: ByteArray) = inp.read(buf)
    override fun close() = sock.close()

    companion object {
        const val DEFAULT = "192.168.0.10:35000"   // ELM327 Wi-Fi klonlarının çoğu
        /** Kendi ESP32 adaptörümüz (firmware/elm_bridge): adres ağda mDNS ile aranır */
        const val ESP32 = "obd-esp32:35000"
        const val ESP32_HOST = "obd-esp32"
        private const val ESP32_AP = "192.168.0.10"

        /** Son bağlanılan ESP32'nin IP'si (ham kayıt akışı aynı adrese, 35001'e bağlanır) */
        @Volatile var espHost: String? = null
            private set

        /**
         * ESP32 telefonun erişim noktasına bağlıysa adresi her seferinde değişir: _elm327._tcp servisini arar.
         * Bulamazsa ESP32'nin kendi ağındaki sabit adres (telefon OBD-ESP32 ağına bağlıysa).
         */
        fun findEsp32(ctx: Context, timeoutMs: Long = 4000): String {
            val nsd = ctx.getSystemService(NsdManager::class.java) ?: return ESP32_AP
            val found = LinkedBlockingQueue<String>()
            val resolving = java.util.concurrent.atomic.AtomicBoolean(false)
            val disc = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) {
                    if (!info.serviceName.startsWith(ESP32_HOST) || !resolving.compareAndSet(false, true)) return
                    @Suppress("DEPRECATION")  // resolveService: API 34'te yerine geçen çağrı minSdk 26'da yok
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onServiceResolved(r: NsdServiceInfo) {
                            @Suppress("DEPRECATION") r.host?.hostAddress?.let { found.offer(it) }
                        }
                        override fun onResolveFailed(r: NsdServiceInfo, code: Int) { resolving.set(false) }
                    })
                }
                override fun onServiceLost(info: NsdServiceInfo) {}
                override fun onDiscoveryStarted(type: String) {}
                override fun onDiscoveryStopped(type: String) {}
                override fun onStartDiscoveryFailed(type: String, code: Int) { found.offer("") }
                override fun onStopDiscoveryFailed(type: String, code: Int) {}
            }
            val viaNsd = try {
                nsd.discoverServices("_elm327._tcp.", NsdManager.PROTOCOL_DNS_SD, disc)
                found.poll(timeoutMs, TimeUnit.MILLISECONDS)?.takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                null
            } finally {
                try { nsd.stopServiceDiscovery(disc) } catch (_: Exception) {}
            }
            return viaNsd ?: scanLocal() ?: ESP32_AP
        }

        /**
         * mDNS erişim noktası arayüzünde her telefonda çalışmıyor: telefonun özel IPv4 ağlarında (/24) 35000 portu
         * açık cihazı arar. Erişim noktasında birkaç cihaz olur, 254 adres paralel ~1 sn.
         */
        private fun scanLocal(): String? {
            val subnets = try {
                java.net.NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback && !it.name.startsWith("rmnet") && !it.name.startsWith("tun") }
                    .flatMap { it.inetAddresses.toList() }
                    .filterIsInstance<java.net.Inet4Address>()
                    .filter { it.isSiteLocalAddress }
                    .map { it.address }
            } catch (e: Exception) { emptyList() }
            if (subnets.isEmpty()) return null
            val pool = java.util.concurrent.Executors.newFixedThreadPool(48)
            val hit = LinkedBlockingQueue<String>()
            try {
                for (me in subnets) for (last in 1..254) {
                    if (last == (me[3].toInt() and 0xFF)) continue
                    val ip = "${me[0].toInt() and 0xFF}.${me[1].toInt() and 0xFF}.${me[2].toInt() and 0xFF}.$last"
                    pool.execute {
                        try { Socket().use { it.connect(InetSocketAddress(ip, 35001), 400); hit.offer(ip) } } catch (_: IOException) {}
                    }
                }
                return hit.poll(3, TimeUnit.SECONDS)
            } finally { pool.shutdownNow() }
        }

        /**
         * Adaptörün Wi-Fi ağı internetsiz olduğu için Android trafiği mobil veriye yollayabilir: soket Wi-Fi ağına
         * bağlanarak açılır. Wi-Fi ağı yoksa varsayılan ağ denenir (ör. adaptör ev ağına bağlı test kurulumu).
         */
        fun connect(ctx: Context, hostPort: String, timeoutMs: Int = 6000, track: (Closeable?) -> Unit): TcpLink {
            val named = hostPort.substringBeforeLast(':').trim()
            val esp = named.equals(ESP32_HOST, ignoreCase = true)
            val host = if (esp) findEsp32(ctx) else named
            val port = hostPort.substringAfterLast(':', "35000").trim().toIntOrNull() ?: 35000
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")  // allNetworks: callback'siz tek seferlik arama için yeterli
            val wifi = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            // Wi-Fi ağına bağlı soket: internetsiz adaptör ağında şart. Ama VPN (ör. Tailscale) açıkken Android buna izin
            // vermiyor (EPERM); telefonun erişim noktasındaki ESP32'ye de düz soket gider. Sırayla ikisi de denenir.
            val bound = { wifi?.socketFactory?.createSocket() }
            val plain = { Socket() }
            val order = if (esp) listOf(plain, bound) else listOf(bound, plain)
            var last: IOException? = null
            var s: Socket? = null
            for (make in order) {
                val c = try { make() } catch (e: IOException) { last = e; null } ?: continue
                track(c)
                try {
                    c.tcpNoDelay = true
                    c.connect(InetSocketAddress(host, port), timeoutMs)
                    s = c; break
                } catch (e: IOException) {
                    last = e
                    try { c.close() } catch (_: IOException) {}
                } finally { track(null) }
            }
            if (s == null) throw IOException(if (wifi == null && !esp) "Wi-Fi'a bağlı değil" else "$host:$port cevap vermedi", last)
            espHost = if (esp) host else null
            return TcpLink(s)
        }
    }
}

/**
 * ELM327 BLE adaptörleri: bir servis içinde bildirim (notify/indicate) ve yazma özelliği. Bilinen düzenler önce denenir,
 * yoksa standart dışı herhangi bir servisteki uygun çift seçilir. Gelen bildirimler kuyruğa, [read] oradan okur.
 */
@SuppressLint("MissingPermission")  // çağıran BLUETOOTH_CONNECT iznini denetler
class BleLink private constructor(private val gatt: BluetoothGatt, private val cb: Cb) : Link {

    override fun write(b: ByteArray, off: Int, len: Int) {
        val chunk = (cb.mtu - 3).coerceIn(20, 512)
        var i = off
        synchronized(this) {
            while (i < off + len) {
                val n = minOf(chunk, off + len - i)
                val part = b.copyOfRange(i, i + n)
                if (!cb.writeDone.tryAcquire(3, TimeUnit.SECONDS)) throw IOException("BLE yazma zaman aşımı")
                if (!cb.write(gatt, part)) { cb.writeDone.release(); throw IOException("BLE yazılamadı") }
                i += n
            }
        }
    }

    override fun read(buf: ByteArray): Int {
        while (true) {
            if (cb.closed && cb.rx.isEmpty()) return -1
            val p = cb.rx.poll(500, TimeUnit.MILLISECONDS) ?: continue
            if (p.isEmpty()) return -1   // kapanış işareti
            val n = minOf(p.size, buf.size)
            System.arraycopy(p, 0, buf, 0, n)
            if (n < p.size) cb.rx.add(p.copyOfRange(n, p.size))   // nadir: tampondan büyük bildirim
            return n
        }
    }

    override fun close() {
        cb.closed = true
        cb.rx.offer(ByteArray(0))
        try { gatt.disconnect() } catch (_: Exception) {}
        try { gatt.close() } catch (_: Exception) {}
    }

    private class Cb : BluetoothGattCallback() {
        val rx = LinkedBlockingQueue<ByteArray>()
        val ready = CountDownLatch(1)
        val writeDone = Semaphore(1)
        @Volatile var closed = false
        @Volatile var error: String? = null
        @Volatile var mtu = 23
        var txChar: BluetoothGattCharacteristic? = null
        var writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        fun write(g: BluetoothGatt, v: ByteArray): Boolean {
            val c = txChar ?: return false
            return if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, v, writeType) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            else @Suppress("DEPRECATION") run { c.writeType = writeType; c.value = v; g.writeCharacteristic(c) }
        }

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                if (!g.requestMtu(185)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (ready.count > 0) error = "BLE bağlantısı kurulamadı (durum $status)"
                closed = true
                rx.offer(ByteArray(0))
                ready.countDown()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this.mtu = mtu
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val services = g.services.map { s ->
                BleChoice.Service(s.uuid, s.characteristics.map { BleChoice.Char(it.uuid, it.properties) })
            }
            val pick = BleChoice.pick(services)
            if (pick == null) { error = "Adaptörde OBD veri kanalı bulunamadı"; ready.countDown(); return }
            val svc = g.getService(pick.service)
            val rxc = svc.getCharacteristic(pick.notify)
            txChar = svc.getCharacteristic(pick.write)
            writeType = if (pick.writeNoResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            g.setCharacteristicNotification(rxc, true)
            val cccd = rxc.getDescriptor(CCCD)
            if (cccd == null) { ready.countDown(); return }   // bazı klonlar tanımlayıcısız da bildirir
            val v = if (rxc.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, v)
            else @Suppress("DEPRECATION") run { cccd.value = v; g.writeDescriptor(cccd) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) error = "Bildirimler açılamadı ($status)"
            ready.countDown()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeDone.release()
        }

        // API 33+: değer parametreyle gelir; eskilerde özellikten okunur
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (value.isNotEmpty()) rx.offer(value.copyOf())
        }

        @Deprecated("API 33 öncesi")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            c.value?.takeIf { it.isNotEmpty() }?.let { rx.offer(it.copyOf()) }
        }
    }

    companion object {
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun connect(ctx: Context, device: BluetoothDevice, timeoutMs: Long = 15_000, track: (Closeable?) -> Unit): BleLink {
            val cb = Cb()
            val gatt = device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
                ?: throw IOException("BLE başlatılamadı")
            val link = BleLink(gatt, cb)
            track(link)
            try {
                if (!cb.ready.await(timeoutMs, TimeUnit.MILLISECONDS)) { link.close(); throw IOException("BLE adaptörü cevap vermedi") }
                cb.error?.let { link.close(); throw IOException(it) }
                if (cb.closed) throw IOException("BLE bağlantısı kapandı")
            } catch (e: InterruptedException) {
                link.close(); throw IOException("iptal edildi")
            } finally { track(null) }
            Log.i("ObdBridge", "BLE hazır: mtu ${cb.mtu}")
            return link
        }
    }
}

/** BLE servis/özellik seçimi: Android'siz, birim testli. */
object BleChoice {
    data class Char(val uuid: UUID, val props: Int)
    data class Service(val uuid: UUID, val chars: List<Char>)
    data class Pick(val service: UUID, val notify: UUID, val write: UUID, val writeNoResponse: Boolean)

    private const val NOTIFY = 0x10
    private const val INDICATE = 0x20
    private const val WRITE = 0x08
    private const val WRITE_NR = 0x04

    private fun u16(x: String) = UUID.fromString("0000$x-0000-1000-8000-00805f9b34fb")

    /** Bilinen ELM327 BLE düzenleri: (servis, bildirim, yazma) */
    val KNOWN = listOf(
        Triple(u16("fff0"), u16("fff1"), u16("fff2")),   // Vgate iCar, Konnwei ve çoğu klon
        Triple(u16("ffe0"), u16("ffe1"), u16("ffe1")),   // HM-10 tarzı (Veepeak BLE vb.): tek özellik
        Triple(u16("18f0"), u16("2af0"), u16("2af1")),   // bazı vLinker/Vgate modelleri
        Triple(UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2"),   // vLinker / OBDLink CX
            UUID.fromString("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f"), UUID.fromString("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f")),
    )

    /** Standart servisler (genel erişim, cihaz bilgisi, pil…) OBD kanalı değildir */
    private fun standard(u: UUID) = u.toString().let { it.endsWith("-0000-1000-8000-00805f9b34fb") && it.substring(4, 8) in setOf("1800", "1801", "180a", "180f", "1805") }

    fun pick(services: List<Service>): Pick? {
        for ((s, n, w) in KNOWN) {
            val svc = services.firstOrNull { it.uuid == s } ?: continue
            val nc = svc.chars.firstOrNull { it.uuid == n && it.props and (NOTIFY or INDICATE) != 0 } ?: continue
            val wc = svc.chars.firstOrNull { it.uuid == w && it.props and (WRITE or WRITE_NR) != 0 } ?: continue
            return Pick(s, nc.uuid, wc.uuid, wc.props and WRITE == 0)
        }
        for (svc in services) {
            if (standard(svc.uuid)) continue
            val nc = svc.chars.firstOrNull { it.props and (NOTIFY or INDICATE) != 0 } ?: continue
            // Yanıtlı yazma tercih edilir (akış denetimi); yoksa yanıtsız
            val wc = svc.chars.firstOrNull { it.props and WRITE != 0 } ?: svc.chars.firstOrNull { it.props and WRITE_NR != 0 } ?: continue
            return Pick(svc.uuid, nc.uuid, wc.uuid, wc.props and WRITE == 0)
        }
        return null
    }
}
