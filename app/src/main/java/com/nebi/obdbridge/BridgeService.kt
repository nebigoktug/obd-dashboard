package com.nebi.obdbridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.nebi.obdbridge.obd.EngineListener
import com.nebi.obdbridge.obd.EngineState
import com.nebi.obdbridge.obd.EngineStatus
import com.nebi.obdbridge.obd.LinkLostException
import com.nebi.obdbridge.obd.ObdEngine
import com.nebi.obdbridge.obd.Sample
import com.nebi.obdbridge.link.AdapterSpec
import com.nebi.obdbridge.link.BleLink
import com.nebi.obdbridge.link.Link
import com.nebi.obdbridge.link.LinkType
import com.nebi.obdbridge.link.SppLink
import com.nebi.obdbridge.link.TcpLink

/**
 * Bluetooth SPP (ELM327) <-> 127.0.0.1:35000 TCP ham bayt köprüsü.
 *
 * Thread yapısı:
 *  - btThread    : BT'ye bağlanır, bağlı kaldığı sürece BT->TCP okur. Kopunca 3 sn bekleyip tekrar dener.
 *  - acceptThread: TCP istemcilerini kabul eder. Yeni istemci eskisini düşürür (ölü soket kilitlemesin).
 *  - istemci başına bir TCP->BT thread'i.
 * BT okuyucusu istemci olmasa da çalışır; böylece BT kopması anında fark edilir.
 */
class BridgeService : Service() {

    companion object {
        const val TAG = "ObdBridge"
        const val PORT = 35000
        const val EXTRA_ADDR = "addr"
        const val ACTION_STOP = "com.nebi.obdbridge.STOP"
        const val ACTION_STATUS = "com.nebi.obdbridge.STATUS"
        const val ACTION_PROBE = "com.nebi.obdbridge.PROBE"
        const val ACTION_SNIFF = "com.nebi.obdbridge.SNIFF"
        const val ACTION_SNIFF_DRIVE = "com.nebi.obdbridge.SNIFF_DRIVE"
        const val ACTION_SNIFF_STOP = "com.nebi.obdbridge.SNIFF_STOP"
        const val ACTION_SCAN = "com.nebi.obdbridge.SCAN"
        const val ACTION_SCAN_STOP = "com.nebi.obdbridge.SCAN_STOP"
        const val ACTION_BLOCK = "com.nebi.obdbridge.BLOCK"
        const val ACTION_BLOCK_STOP = "com.nebi.obdbridge.BLOCK_STOP"
        /** Taramada bulunan üretici blokları (2026-10-10: 21 01 canlı veri adayı, 21 80 ECU kimliği) */
        val BLOCK_CMDS = listOf("2101")
        @Volatile var blockFile: String? = null
        /** S40'ta desteklenen PID'ler (2026-10-08 testi) */
        val BLOCK_KEYS = listOf("rpm", "speed", "load", "map", "rail", "coolant", "iat", "battery")
        const val PREF_SCAN_NEXT = "scan_next2"  // v2: DID sırası (v1 üçlü sırasıydı)
        @Volatile var scanRunning = false
        @Volatile var sniffRunning = false
        const val PREF_RAW_TCP = "raw_tcp"
        /** Adaptör türü (LinkType adı) ve Wi-Fi adresi */
        const val PREF_LINK_TYPE = "link_type"
        const val PREF_WIFI_HOST = "wifi_host"

        fun spec(ctx: android.content.Context): AdapterSpec? {
            val p = ctx.getSharedPreferences(PREFS, MODE_PRIVATE)
            val t = try { LinkType.valueOf(p.getString(PREF_LINK_TYPE, null) ?: "SPP") } catch (_: Exception) { LinkType.SPP }
            return if (t == LinkType.WIFI) AdapterSpec(t, p.getString(PREF_WIFI_HOST, null) ?: TcpLink.DEFAULT)
            else p.getString(EXTRA_ADDR, null)?.let { AdapterSpec(t, it) }
        }
        /** Otomatik sürüş kaydı (Ayarlar > Kayıtlar), varsayılan açık */
        const val PREF_DRIVE_LOG = "drive_log"
        const val PREFS = "bridge"
        private const val CHANNEL = "bridge"
        private const val NOTIF_ID = 1
        private const val RETRY_MS = 3000L
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        // Activity bunları okuyup ekranı günceller (thread'ler değil, onlar kendi `active` bayrağına bakar)
        @Volatile var running = false
        @Volatile var status = "Bağlı değil"
        // OBD motorunun son değerleri ve durumu (ekranlar buradan okur)
        val latest = ConcurrentHashMap<String, Sample>()
        /** Ekranın hesapladıkları (gösterilen vites, GPS hızı, yakıt tahmini, notlar, uyarılar): sürüş kaydına girer */
        val derived = ConcurrentHashMap<String, Sample>()
        @Volatile var engineStatus: EngineStatus? = null
        @Volatile var lastProbeFile: String? = null
        @Volatile var lastSniffFile: String? = null
        /** Bağlı adaptörün motoru (arıza kodu ekranı gibi tek seferlik istekler için); bağlı değilse null */
        @Volatile private var current: ObdEngine? = null

        /** Bağlı motorun özeti (sorun bildirimi için); bağlı değilse null */
        fun diagnostics(): String? = current?.diag()

        /** Arıza kodlarını okur (ve [clear] ise siler). Bağlantı yoksa false, [onDone] çağrılmaz. */
        fun requestDtc(clear: Boolean, onDone: (com.nebi.obdbridge.obd.DtcReport) -> Unit): Boolean {
            val e = current ?: return false
            e.requestDtc(clear, onDone)
            return true
        }
    }

    // Bu servis örneğine ait bayrak: eski örneğin geç uyanan thread'leri yeni örneğe karışmasın
    @Volatile private var active = false
    @Volatile private var bt: Link? = null                 // bağlı adaptör (klasik BT, BLE ya da Wi-Fi)
    @Volatile private var pending: Closeable? = null        // bağlantı kurulurken; Durdur bunu kapatıp iptal eder
    @Volatile private var engine: ObdEngine? = null
    /** Her sürüşün otomatik kaydı (files/logs/), geçmiş ekranı bunları özetler */
    private var driveLog: com.nebi.obdbridge.dash.DriveLog? = null
    private var logThread: Thread? = null
    @Volatile private var client: Socket? = null
    private var server: ServerSocket? = null
    private var btThread: Thread? = null
    private var acceptThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("WakelockTimeout")  // bilinçli: köprü durdurulana kadar CPU uyanık kalmalı
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PROBE) {
            if (active) runProbe()
            return START_STICKY
        }
        if (intent?.action == ACTION_SNIFF || intent?.action == ACTION_SNIFF_DRIVE) {
            if (active && !sniffRunning) runSniff(drive = intent.action == ACTION_SNIFF_DRIVE)
            return START_STICKY
        }
        if (intent?.action == ACTION_SCAN) {
            if (active && !scanRunning) runScan()
            return START_STICKY
        }
        if (intent?.action == ACTION_SCAN_STOP) {
            engine?.cancelScan()
            return START_STICKY
        }
        if (intent?.action == ACTION_BLOCK) {
            if (active && blockFile == null) startBlockLog()
            return START_STICKY
        }
        if (intent?.action == ACTION_BLOCK_STOP) {
            stopBlockLog()
            return START_STICKY
        }
        if (intent?.action == ACTION_SNIFF_STOP) {
            engine?.cancelSniff()
            return START_STICKY
        }
        if (active) return START_STICKY

        // intent null ise sistem servisi yeniden başlatmıştır: son cihazı kayıttan al
        intent?.getStringExtra(EXTRA_ADDR)?.let { getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(EXTRA_ADDR, it).apply() }
        val spec = spec(this)
        val device = if (spec?.type == LinkType.WIFI) null else spec?.let { btDevice(it.address) }
        if (spec == null || (spec.type != LinkType.WIFI && device == null) || !startInForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Ham TCP köprüsü isteğe bağlı (Torque/RealDash denemesi, laptoptan adb ile doğrudan komut)
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_RAW_TCP, true)) {
            try {
                // Sadece loopback: telefon dışından erişilemez
                server = ServerSocket(PORT, 1, InetAddress.getByName("127.0.0.1")).apply { reuseAddress = true }
            } catch (e: IOException) {
                setStatus("Başlatılamadı · uygulamayı kapatıp yeniden aç")
                stopSelf()
                return START_NOT_STICKY
            }
        }

        active = true
        running = true
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ObdBridge::bridge")
            .apply { setReferenceCounted(false); acquire() }

        btThread = Thread({ linkLoop(spec, device) }, "link").apply { start() }
        startDriveLog()
        if (server != null) acceptThread = Thread(::acceptLoop, "accept").apply { start() }
        return START_STICKY
    }

    override fun onDestroy() {
        val wasRunning = active
        active = false
        running = false
        closeQuietly(server)   // accept() bloğunu açar
        closeQuietly(client)
        closeQuietly(pending)  // süren connect() denemesini keser
        stopBlockLog()
        logThread?.interrupt()
        engine?.stop()
        closeQuietly(bt)       // read() bloğunu açar
        btThread?.interrupt()  // yeniden deneme beklemesini keser
        wakeLock?.let { if (it.isHeld) it.release() }
        engineStatus = null
        current = null
        sniffRunning = false
        scanRunning = false
        if (wasRunning) setStatus("Bağlı değil")  // değilse hata mesajı ekranda kalsın
        else sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName))
        super.onDestroy()
    }

    // ---------- Bluetooth ----------

    private fun btDevice(addr: String): BluetoothDevice? {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return null
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return null
        return try { adapter.getRemoteDevice(addr) } catch (e: IllegalArgumentException) { null }
    }

    @Suppress("MissingPermission")
    private fun connectBt(device: BluetoothDevice): BluetoothSocket {
        // Keşif açıksa RFCOMM bağlantısı yavaşlar/başarısız olur (Android 12+'da SCAN izni ister, yoksa geç)
        try {
            (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter.cancelDiscovery()
        } catch (_: SecurityException) {}

        try {
            return connectTracked(device.createRfcommSocketToServiceRecord(SPP_UUID))
        } catch (e: IOException) {
            if (!active) throw e
            Log.w(TAG, "SDP ile bağlanılamadı, kanal 1 deneniyor: ${e.message}")
        }
        // Bazı ucuz klonlar SDP kaydı yayınlamıyor: gizli API ile doğrudan RFCOMM kanal 1
        val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
        return connectTracked(m.invoke(device, 1) as BluetoothSocket)
    }

    @SuppressLint("MissingPermission")  // izin btDevice() içinde kontrol edildi
    private fun connectTracked(sock: BluetoothSocket): BluetoothSocket {
        pending = sock
        try {
            sock.connect()
        } catch (e: IOException) {
            closeQuietly(sock)
            throw e
        } finally {
            pending = null
        }
        return sock
    }

    /**
     * Kullanıcı yalnız "Bluetooth" ya da "Wi-Fi" seçer; klasik/BLE ayrımını cihaz türünden biz yaparız.
     * Hem klasik hem BLE olan (DUAL) ya da türü bilinmeyen adaptörde önce klasik, olmazsa BLE denenir.
     */
    @SuppressLint("MissingPermission")
    private fun connect(spec: AdapterSpec, device: BluetoothDevice?): Link = when (spec.type) {
        LinkType.WIFI -> TcpLink.connect(this, spec.address) { pending = it }
        LinkType.BLE -> BleLink.connect(this, device!!) { pending = it }
        LinkType.SPP -> try {
            SppLink(connectBt(device!!))
        } catch (e: IOException) {
            val t = try { device!!.type } catch (_: SecurityException) { BluetoothDevice.DEVICE_TYPE_UNKNOWN }
            if (!active || t == BluetoothDevice.DEVICE_TYPE_CLASSIC) throw e
            Log.w(TAG, "Klasik bağlantı olmadı (${e.message}), BLE deneniyor")
            BleLink.connect(this, device!!) { pending = it }
        }
    }

    private fun linkLoop(spec: AdapterSpec, device: BluetoothDevice?) {
        val buf = ByteArray(1024)
        val what = if (spec.type == LinkType.WIFI) "Wi-Fi" else "Bluetooth"
        while (active) {
            setStatus("Adaptöre bağlanıyor…")
            try {
                val link = connect(spec, device)
                bt = link
                if (!active) break
                setStatus("$what bağlı")
                val eng = startEngine(link, spec.key)
                while (active) {
                    val n = link.read(buf)
                    if (n < 0) break
                    // Ham istemci bağlıysa veri ona, değilse OBD motoruna
                    val c = client
                    if (c == null) { eng.onBytes(buf, n); continue }
                    try {
                        c.getOutputStream().write(buf, 0, n)  // TCP_NODELAY açık: anında gider
                    } catch (e: IOException) {
                        dropClient(c)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "$what hatası: ${e.message}")
                if (active) setStatus("Adaptöre ulaşılamadı · takılı ve kontak açık mı?")
            }
            // Bağlantı koptu: motoru durdur, istemciyi de kapat ki karşı taraf durumu anlasın
            engine?.stop(); engine = null; current = null
            closeQuietly(bt); bt = null
            client?.let { dropClient(it) }
            if (!active) break
            try { Thread.sleep(RETRY_MS) } catch (_: InterruptedException) { break }
            setStatus("Adaptöre yeniden bağlanıyor…")
        }
    }

    // ---------- OBD motoru ----------

    private fun startEngine(link: Link, key: String): ObdEngine {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val eng = ObdEngine({ link.write(it) }, object : EngineListener {
            override fun onSamples(samples: List<Sample>) {
                for (x in samples) latest[x.key] = x
            }
            override fun onStatus(status: EngineStatus) {
                engineStatus = status
                engineStatusText(status)?.let { if (client == null) setStatus(it) }
            }
        })
        engine = eng
        current = eng
        if (blockOut != null) attachBlockLog(eng)
        Thread({
            try {
                // Eski kayıt (2026-10-11 öncesi) MAC'le tutuluyordu
                eng.run(prefs.getString("proto_$key", null) ?: prefs.getString("proto_${key.substringAfter('_')}", null))
            } catch (e: LinkLostException) {
                Log.w(TAG, "OBD: ${e.message}")
                closeQuietly(link)  // okuyucu düşer, yeniden bağlanma döngüsü devreye girer
            } catch (e: Exception) {
                Log.e(TAG, "OBD motoru çöktü", e)
            }
            eng.protocol?.let { prefs.edit().putString("proto_$key", it).apply() }
        }, "obd").start()
        return eng
    }

    /** Araç testini motora sıraya koyar; rapor uygulamanın dış dosya klasörüne yazılır
     *  (adb: /sdcard/Android/data/com.nebi.obdbridge/files/probe/). */
    private fun runProbe() {
        val eng = engine ?: return
        eng.requestProbe { json ->
            val dir = java.io.File(getExternalFilesDir(null), "probe").apply { mkdirs() }
            val name = "obd_probe_%1\$tY%1\$tm%1\$td_%1\$tH%1\$tM%1\$tS.json".format(java.util.Date())
            val f = java.io.File(dir, name)
            try {
                f.writeText(json)
                lastProbeFile = f.absolutePath
                Log.i(TAG, "Araç testi bitti: $f")
            } catch (e: IOException) {
                Log.e(TAG, "Rapor yazılamadı", e)
            }
            sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName))
        }
    }

    /** Gizli değer taraması; kaldığı yerden devam eder. Kayıt: files/scan/scan_*.txt */
    private fun runScan() {
        val eng = engine ?: return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val start = prefs.getInt(PREF_SCAN_NEXT, 0).let { if (it < 0) 0 else it }  // bitmişse baştan
        scanRunning = true
        eng.requestScan(start) { text, next ->
            scanRunning = false
            prefs.edit().putInt(PREF_SCAN_NEXT, next).apply()
            val dir = java.io.File(getExternalFilesDir(null), "scan").apply { mkdirs() }
            val f = java.io.File(dir, "scan_%1\$tY%1\$tm%1\$td_%1\$tH%1\$tM%1\$tS.txt".format(java.util.Date()))
            try {
                f.writeText(text)
                Log.i(TAG, "Tarama durdu/bitti: $f, sonraki $next")
            } catch (e: IOException) {
                Log.e(TAG, "Tarama kaydı yazılamadı", e)
            }
            sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName))
        }
    }

    private fun startDriveLog() {
        val log = com.nebi.obdbridge.dash.DriveLog(java.io.File(getExternalFilesDir(null), "logs"))
        driveLog = log
        logThread = Thread({
            try {
                while (active) {
                    val on = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_DRIVE_LOG, true)
                    try { log.tick(latest + derived, on && engineStatus?.state == EngineState.LIVE) }
                    catch (e: IOException) { Log.w(TAG, "Sürüş kaydı yazılamadı: ${e.message}") }
                    Thread.sleep(com.nebi.obdbridge.dash.DriveLog.PERIOD_MS / 2)
                }
            } catch (_: InterruptedException) {}
            log.close()
        }, "drivelog").apply { isDaemon = true; start() }
    }

    private var blockOut: java.io.Writer? = null

    /**
     * Blok kaydı: 21 01 gibi bilinmeyen blokların ham cevabı, o anki devir/hız/yük/sıcaklıkla birlikte CSV'ye.
     * Kayıt: files/blocks/block_*.csv. BT koparsa yeni motora da bağlanır (startEngine).
     */
    private fun startBlockLog() {
        val dir = java.io.File(getExternalFilesDir(null), "blocks").apply { mkdirs() }
        val f = java.io.File(dir, "block_%1\$tY%1\$tm%1\$td_%1\$tH%1\$tM%1\$tS.csv".format(java.util.Date()))
        try {
            blockOut = f.bufferedWriter().apply { write("ts,${BLOCK_KEYS.joinToString(",")},cmd,resp\n") }
        } catch (e: IOException) {
            Log.e(TAG, "Blok kaydı açılamadı", e); return
        }
        blockFile = f.absolutePath
        engine?.let { attachBlockLog(it) }
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName))
    }

    private fun attachBlockLog(eng: ObdEngine) {
        eng.onRaw = { cmd, resp, ts ->
            val w = blockOut
            if (w != null) synchronized(w) {
                try {
                    val vals = BLOCK_KEYS.joinToString(",") { k -> latest[k]?.let { "%.1f".format(java.util.Locale.US, it.value) } ?: "" }
                    w.write("$ts,$vals,$cmd,${resp.replace('\n', '|')}\n")
                    w.flush()
                } catch (e: IOException) { Log.w(TAG, "Blok kaydı yazılamadı: ${e.message}") }
            }
        }
        eng.rawPoll = BLOCK_CMDS
    }

    private fun stopBlockLog() {
        engine?.rawPoll = emptyList()
        engine?.onRaw = null
        blockOut?.let { w -> synchronized(w) { try { w.close() } catch (_: IOException) {} } }
        blockOut = null
        if (blockFile != null) {
            blockFile = null
            sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName))
        }
    }

    /** CAN keşif kaydı; adım değişince bip. Kayıt: /sdcard/Android/data/com.nebi.obdbridge/files/can/ */
    private fun runSniff(drive: Boolean) {
        val eng = engine ?: return
        sniffRunning = true
        val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 100)
        eng.onSniffStep = { tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 400) }
        val steps = if (drive) com.nebi.obdbridge.obd.CanSniffer.DRIVE else com.nebi.obdbridge.obd.CanSniffer.STEPS
        eng.requestSniff(steps, drive) { text ->
            sniffRunning = false
            eng.onSniffStep = null
            tone.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 600)
            val dir = java.io.File(getExternalFilesDir(null), "can").apply { mkdirs() }
            val f = java.io.File(dir, (if (drive) "drive_" else "can_") + "%1\$tY%1\$tm%1\$td_%1\$tH%1\$tM%1\$tS.txt".format(java.util.Date()))
            try {
                f.writeText(text)
                lastSniffFile = f.absolutePath
                Log.i(TAG, "CAN kaydı bitti: $f (${text.length} bayt)")
            } catch (e: IOException) {
                Log.e(TAG, "CAN kaydı yazılamadı", e)
            }
            Thread.sleep(800)
            tone.release()
            sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName))
        }
    }

    /** Bildirim/ekran metni; yalnız durum değişince güncellenir (Hz her saniye değişir, bildirim titremesin). */
    private fun engineStatusText(s: EngineStatus): String? = when (s.state) {
        EngineState.INIT -> "Adaptör bağlı · araç hazırlanıyor"
        EngineState.SEARCHING -> "Araç aranıyor"
        EngineState.LIVE -> "Canlı"
        EngineState.NO_ECU -> "Araç cevap vermiyor · kontak açık mı?"
        EngineState.PROBING -> s.msg ?: "Araç testi"
        EngineState.PAUSED, EngineState.STOPPED -> null
    }

    // ---------- TCP ----------

    private fun acceptLoop() {
        val srv = server ?: return
        while (active) {
            val s = try { srv.accept() } catch (e: IOException) { break }
            val sock = bt
            if (sock == null) {  // adaptör yokken istemciyi bekletmenin anlamı yok
                closeQuietly(s)
                continue
            }
            s.tcpNoDelay = true
            s.keepAlive = true
            client?.let { dropClient(it) }  // tek istemci: yeni gelen eskisinin yerini alır
            engine?.pause()  // süren OBD isteği bitsin, adaptör ham istemciye kalsın
            client = s
            setStatus("Başka bir uygulama adaptörü kullanıyor")
            Thread({ tcpToBt(s, sock) }, "tcp->bt").start()
        }
    }

    private fun tcpToBt(s: Socket, link: Link) {
        val buf = ByteArray(1024)
        try {
            val input = s.getInputStream()
            while (active) {
                val n = input.read(buf)
                if (n < 0) break
                link.write(buf, 0, n)
            }
        } catch (_: IOException) {
        }
        dropClient(s)
    }

    @Synchronized
    private fun dropClient(s: Socket) {
        closeQuietly(s)
        if (client === s) {
            client = null
            if (active && bt != null) {
                setStatus("Adaptör bağlı")
                engine?.resume()
            }
        }
    }

    // ---------- Bildirim / durum ----------

    private fun startInForeground(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "OBD köprüsü", NotificationManager.IMPORTANCE_LOW)
        )
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, buildNotif("Başlatılıyor"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIF_ID, buildNotif("Başlatılıyor"))
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Foreground başlatılamadı", e)  // ör. arka planda yeniden başlatma yasağı
            false
        }
    }

    private fun buildNotif(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BridgeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_obd)
            .setContentTitle("OBD köprü · 127.0.0.1:$PORT")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Durdur", stop).build())
            .build()
    }

    private fun setStatus(s: String) {
        val changed = s != status
        status = s
        if (changed) {
            Log.i(TAG, s)
            if (active) getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif(s))
        }
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName))
    }

    private fun closeQuietly(c: Closeable?) {
        try { c?.close() } catch (_: IOException) {}
    }
}
