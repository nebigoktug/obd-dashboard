package com.nebi.obdbridge.obd

import android.util.Log
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Kaydedilen ham CAN akışının canlı kopyası, yalnız telefonun içinden: 127.0.0.1:35002.
 * ESP32 tek ham istemci kabul ettiği için Termux doğrudan bağlanırsa kayıt kesilir; buradan bağlanınca ikisi birlikte çalışır.
 * Yavaş okuyan istemci kaydı bekletmez: kuyruğu dolunca parçalar o istemci için düşer.
 */
object LiveRelay {
    private const val TAG = "LiveRelay"
    const val PORT = 35002

    private class Client(val sock: Socket) {
        val q = ArrayBlockingQueue<ByteArray>(512)
    }

    private val clients = CopyOnWriteArrayList<Client>()
    @Volatile private var server: ServerSocket? = null

    @Synchronized
    fun ensureStarted() {
        if (server != null) return
        val ss = try {
            ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1")).apply { reuseAddress = true }
        } catch (e: IOException) {
            Log.w(TAG, "canlı kopya açılamadı: ${e.message}"); return
        }
        server = ss
        Thread({
            while (true) {
                val s = try { ss.accept() } catch (e: IOException) { break }
                val c = Client(s)
                clients += c
                Thread({ pump(c) }, "live-relay-out").apply { isDaemon = true; start() }
            }
        }, "live-relay").apply { isDaemon = true; start() }
    }

    /** Kayıt döngüsünden: okunan her parça (satır ortasından bölünmüş olabilir). */
    fun feed(buf: ByteArray, n: Int) {
        if (clients.isEmpty()) return
        val chunk = buf.copyOf(n)
        for (c in clients) c.q.offer(chunk)
    }

    private fun pump(c: Client) {
        try {
            c.sock.tcpNoDelay = true
            c.sock.getOutputStream().write("# canli kopya (OBD Dashboard)\n".toByteArray())
            val out = c.sock.getOutputStream()
            while (true) out.write(c.q.take())
        } catch (_: Exception) {
        } finally {
            clients -= c
            try { c.sock.close() } catch (_: IOException) {}
        }
    }
}
