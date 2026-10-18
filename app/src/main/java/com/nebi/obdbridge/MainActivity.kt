package com.nebi.obdbridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.nebi.obdbridge.obd.EngineState

class MainActivity : Activity() {

    private lateinit var startBtn: Button
    private lateinit var probeBtn: Button
    private lateinit var sniffBtn: Button
    private lateinit var driveBtn: Button
    private lateinit var scanBtn: Button
    private lateinit var blockBtn: Button
    private lateinit var rawTcpBox: android.widget.CheckBox
    private lateinit var statusText: TextView
    private lateinit var liveText: TextView
    private val ticker = object : Runnable {
        override fun run() { showLive(); liveText.postDelayed(this, 250) }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = refreshUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        startBtn = Button(this).apply { setOnClickListener { toggle() } }
        statusText = TextView(this).apply { textSize = 20f; setPadding(0, pad, 0, pad) }
        val dashBtn = Button(this).apply {
            text = "Dashboard"
            setOnClickListener { startActivity(Intent(this@MainActivity, com.nebi.obdbridge.dash.DashboardActivity::class.java)) }
        }
        probeBtn = Button(this).apply {
            text = "Araç testi"
            setOnClickListener {
                startService(Intent(this@MainActivity, BridgeService::class.java).setAction(BridgeService.ACTION_PROBE))
            }
        }
        sniffBtn = Button(this).apply {
            text = "CAN keşif kaydı (araç boşta dururken, ~3,5 dk)"
            setOnClickListener {
                startService(Intent(this@MainActivity, BridgeService::class.java).setAction(BridgeService.ACTION_SNIFF))
            }
        }
        driveBtn = Button(this).apply {
            setOnClickListener {
                val action = if (BridgeService.sniffRunning) BridgeService.ACTION_SNIFF_STOP else BridgeService.ACTION_SNIFF_DRIVE
                startService(Intent(this@MainActivity, BridgeService::class.java).setAction(action))
                postDelayed({ refreshUi() }, 300)
            }
        }
        scanBtn = Button(this).apply {
            setOnClickListener {
                val action = if (BridgeService.scanRunning) BridgeService.ACTION_SCAN_STOP else BridgeService.ACTION_SCAN
                startService(Intent(this@MainActivity, BridgeService::class.java).setAction(action))
                postDelayed({ refreshUi() }, 300)
            }
        }
        blockBtn = Button(this).apply {
            setOnClickListener {
                val action = if (BridgeService.blockFile != null) BridgeService.ACTION_BLOCK_STOP else BridgeService.ACTION_BLOCK
                startService(Intent(this@MainActivity, BridgeService::class.java).setAction(action))
                postDelayed({ refreshUi() }, 300)
            }
        }
        val prefs = getSharedPreferences(BridgeService.PREFS, MODE_PRIVATE)
        rawTcpBox = android.widget.CheckBox(this).apply {
            text = "Ham TCP köprüsü (127.0.0.1:${BridgeService.PORT})"
            isChecked = prefs.getBoolean(BridgeService.PREF_RAW_TCP, false)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(BridgeService.PREF_RAW_TCP, on).apply() }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            // Adaptör seçimi tek yerde: Ayarlar > Bağlantı (Bluetooth, BLE, Wi-Fi)
            addView(TextView(this@MainActivity).apply { text = "Adaptör: " + (com.nebi.obdbridge.dash.Conn.label(this@MainActivity) ?: "seçilmedi (Ayarlar > Bağlantı)") })
            addView(startBtn)
            addView(statusText)
            addView(dashBtn)
            addView(probeBtn)
            addView(scanBtn)
            addView(blockBtn)
            // CAN keşif kayıtları (sniffBtn, driveBtn) ekranda değil: S40'ın OBD soketinde yayın trafiği yok ve
            // v2.1 klonu dinleme modunda kilitleniyor (2026-10-09). Kod başka araç/adaptör için duruyor.
            addView(rawTcpBox)
            liveText = TextView(this@MainActivity).apply {
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(0, pad, 0, 0)
            }
            addView(liveText)
        })

        requestNeededPermissions()
    }

    @SuppressLint("InlinedApi")
    override fun onResume() {
        super.onResume()
        // Flag'li sürüm API 26'dan beri var; 33 öncesinde flag yok sayılır
        registerReceiver(statusReceiver, IntentFilter(BridgeService.ACTION_STATUS), RECEIVER_NOT_EXPORTED)
        refreshUi()
        ticker.run()
        NaviListener.rebind(this)
    }

    override fun onPause() {
        liveText.removeCallbacks(ticker)
        unregisterReceiver(statusReceiver)
        super.onPause()
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) perms += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        refreshUi()
    }

    private fun toggle() {
        if (BridgeService.running) com.nebi.obdbridge.dash.Conn.stop(this)
        else com.nebi.obdbridge.dash.Conn.start(this)
        startBtn.postDelayed({ refreshUi() }, 300)
    }

    private fun refreshUi() {
        val running = BridgeService.running
        statusText.text = "Durum: " + com.nebi.obdbridge.dash.Conn.status(this)
        startBtn.text = if (running) "Durdur" else "Başlat"
        rawTcpBox.isEnabled = !running  // servis başlarken okunur
        val st = BridgeService.engineStatus?.state
        probeBtn.isEnabled = running && (st == EngineState.LIVE || st == EngineState.NO_ECU)
        val next = getSharedPreferences(BridgeService.PREFS, MODE_PRIVATE).getInt(BridgeService.PREF_SCAN_NEXT, 0)
        val total = com.nebi.obdbridge.obd.DidScanner.DEFAULT_IDS.size
        scanBtn.text = when {
            BridgeService.scanRunning -> "Taramayı durdur (kaldığı yer saklanır)"
            next < 0 -> "Gizli değer taraması bitti (baştan için dokun)"
            next == 0 -> "Gizli değer taraması (kontak açık, ~70 dk, bölünebilir)"
            else -> "Gizli değer taramasına devam et (%${next * 100 / total})"
        }
        scanBtn.isEnabled = running && (BridgeService.scanRunning || st == EngineState.LIVE || st == EngineState.NO_ECU)
        blockBtn.text = if (BridgeService.blockFile != null) "Blok kaydını bitir" else "Blok kaydı (sürüşte 21 01 kaydı)"
        blockBtn.isEnabled = running && (BridgeService.blockFile != null || st == EngineState.LIVE)
        sniffBtn.isEnabled = running && st == EngineState.LIVE && !BridgeService.sniffRunning
        // Süren kayıt (duran ya da sürüş) bu butonla erken bitirilebilir
        driveBtn.text = if (BridgeService.sniffRunning) "Kaydı bitir" else "Sürüş keşif kaydı (yola çıkmadan başlat)"
        driveBtn.isEnabled = running && (BridgeService.sniffRunning || st == EngineState.LIVE)
    }

    /** Geçici canlı değer listesi (dashboard gelene kadar doğrulama için). */
    private fun showLive() {
        val st = BridgeService.engineStatus
        if (st == null) { liveText.text = ""; return }
        val now = System.currentTimeMillis()
        val sb = StringBuilder()
        sb.append("OBD: ${st.state}  protokol ${st.protocol ?: "-"}\n")
        sb.append("mod ${st.mode ?: "-"} ×${st.maxMulti}  ${"%.1f".format(st.reqPerSec)} istek/sn  devir ${"%.1f".format(st.rpmHz)} Hz\n")
        st.msg?.let { sb.append(it).append('\n') }
        BridgeService.lastSniffFile?.let { sb.append("Son CAN kaydı: ").append(it.substringAfterLast('/')).append('\n') }
        BridgeService.blockFile?.let { sb.append("Blok kaydı sürüyor: ").append(it.substringAfterLast('/')).append('\n') }
        BridgeService.lastProbeFile?.let { sb.append("Son test raporu: ").append(it.substringAfterLast('/')).append('\n') }
        sb.append('\n')
        for (x in BridgeService.latest.values.sortedBy { it.key }) {
            sb.append("%-10s %9.1f   %5d ms önce\n".format(x.key, x.value, now - x.ts))
        }
        liveText.text = sb
    }
}
