package com.nebi.obdbridge.obd

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPOutputStream

/**
 * ESP32 adaptörünün ham CAN akışını (port 35001, satır başına "ms kimlik veri") telefona yazar.
 * Kayıt: files/can/can_<tarih>.txt.gz, bağlantı başına bir dosya. Klasör [MAX_BYTES]'ı aşınca en eskiler silinir.
 * Kapanan her kaydın kopyası İndirilenler/OBD-CAN'a da yazılır: bilgisayarsız inceleme (Termux) oradan okur.
 * OBD bağlantısından bağımsız ikinci soket: gösterge sorguları sürerken hattın tamamı kaydedilir.
 */
object CanRecorder {
    private const val TAG = "CanRecorder"
    private const val PORT = 35001
    private const val MAX_BYTES = 800L * 1024 * 1024

    @Volatile private var thread: Thread? = null
    @Volatile private var sock: Socket? = null
    @Volatile var bytes = 0L
        private set

    fun dir(ctx: Context) = File(ctx.filesDir, "can")

    @Synchronized
    fun start(ctx: Context, host: String) {
        if (thread?.isAlive == true) return
        val app = ctx.applicationContext
        LiveRelay.ensureStarted()
        thread = Thread({ run(app, host) }, "can-rec").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        val t = thread ?: return
        thread = null
        try { sock?.close() } catch (_: IOException) {}
        t.interrupt()
    }

    private fun run(ctx: Context, host: String) {
        val me = Thread.currentThread()
        while (thread === me) {
            try {
                record(ctx, host)
            } catch (e: IOException) {
                Log.w(TAG, "kayıt koptu: ${e.message}")
            }
            try { Thread.sleep(3000) } catch (_: InterruptedException) { break }
        }
    }

    private fun record(ctx: Context, host: String) {
        // Önce düz soket (telefonun erişim noktası, VPN açıkken de çalışır), olmazsa Wi-Fi'a bağlı soket (ESP32'nin ağı)
        val s = try {
            Socket().apply { sock = this; connect(InetSocketAddress(host, PORT), 4000) }
        } catch (e: IOException) {
            try { sock?.close() } catch (_: IOException) {}
            openSocket(ctx).apply { sock = this; connect(InetSocketAddress(host, PORT), 4000) }
        }
        s.soTimeout = 15_000                     // hat sustu (kontak kapandı) → dosyayı kapat
        val d = dir(ctx).apply { mkdirs() }
        prune(d)
        val name = "can_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt.gz"
        val f = File(d, name)
        val buf = ByteArray(16 * 1024)
        var got = 0L
        try {
            GZIPOutputStream(f.outputStream().buffered(64 * 1024), 64 * 1024).use { out ->
                val inp = s.getInputStream()
                while (thread != null) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    LiveRelay.feed(buf, n)
                    got += n; bytes += n
                }
            }
        } finally {
            try { s.close() } catch (_: IOException) {}
            sock = null
            if (got < 200) f.delete()            // yalnız başlık satırı geldiyse (hat sessiz) dosya kalmasın
            else exportToDownloads(ctx, f)
            Log.i(TAG, "$name: ${got / 1024} KB ham")
        }
    }

    /** İndirilenler/OBD-CAN/<ad> kopyası (Android 10+). Telefonun kendi klasörü temizlenir; bu kopyalar kullanıcının. */
    private fun exportToDownloads(ctx: Context, f: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                put(MediaStore.Downloads.MIME_TYPE, "application/gzip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OBD-CAN")
            }
            val cr = ctx.contentResolver
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return
            cr.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
        } catch (e: Exception) {
            Log.w(TAG, "İndirilenler'e kopyalanamadı: ${e.message}")
        }
    }

    /** ESP32'nin kendi ağındaysak soket Wi-Fi'a bağlanmalı (internetsiz ağ); erişim noktasında varsayılan ağ yeter. */
    private fun openSocket(ctx: Context): Socket {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val wifi = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        return wifi?.socketFactory?.createSocket() ?: Socket()
    }

    private fun prune(d: File) {
        val files = d.listFiles { f -> f.name.startsWith("can_") }?.sortedBy { it.name } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= MAX_BYTES) break
            total -= f.length(); f.delete()
        }
    }
}
