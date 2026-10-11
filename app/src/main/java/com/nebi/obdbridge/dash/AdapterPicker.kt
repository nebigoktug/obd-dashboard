package com.nebi.obdbridge.dash

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import com.nebi.obdbridge.link.LinkType
import com.nebi.obdbridge.link.TcpLink

/**
 * Adaptör seçimi (Ayarlar ve sihirbaz ortak). Kullanıcıya iki tür gösterilir: Bluetooth ve Wi-Fi.
 * Bluetooth listesinde eşleşmiş cihazlar ve yakında bulunan BLE cihazları birlikte; klasik/BLE ayrımı otomatik.
 * İzin gerekirse [askPerms] çağrılır (Activity izin isteyip sonra [show]'u yeniden açar).
 */
class AdapterPicker(private val a: Activity, private val askPerms: (Array<String>) -> Unit, private val done: () -> Unit) {
    private val ui = Ui(a)
    private var stopScan: () -> Unit = {}

    fun show() {
        var wifi = Conn.isWifi(a)
        val root = ui.column(20)
        val body = ui.column()
        lateinit var dlg: AlertDialog
        fun render() {
            stopScan(); stopScan = {}
            body.removeAllViews()
            if (wifi) wifiPane(body) { dlg.dismiss() } else btPane(body) { dlg.dismiss() }
        }
        root.addView(ui.chips(listOf(false to "Bluetooth", true to "Wi-Fi (deneysel)"), wifi) { wifi = it; render() })
        root.addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
        dlg = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("OBD adaptörü")
            .setView(ScrollView(a).apply { addView(root) })
            .setNegativeButton("Kapat", null)
            .setOnDismissListener { stopScan(); done() }
            .create()
        render()
        dlg.show()
    }

    @SuppressLint("MissingPermission")
    private fun btPane(body: LinearLayout, close: () -> Unit) {
        if (!Conn.hasBt(a) || !Conn.hasScan(a)) {
            body.addView(ui.text("Yakındaki adaptörleri görmek için Bluetooth izni gerekiyor.", 15f, Pal.DIM))
            body.addView(ui.button("İzin ver", primary = true) { close(); askPerms(Conn.scanPerms()) },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(10) })
            return
        }
        if (!Conn.btOn(a)) { body.addView(ui.text("Bluetooth kapalı. Açıp tekrar dene.", 15f, Pal.AMBER)); return }
        val list = ui.column()
        val status = ui.text("Yakındaki adaptörler aranıyor…", 13f, Pal.DIM)
        body.addView(status)
        body.addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        val shown = HashSet<String>()
        val cur = Conn.address(a)
        fun row(addr: String, name: String, sub: String, leOnly: Boolean) {
            if (!shown.add(addr)) return
            val on = addr == cur && !Conn.isWifi(a)
            val t = ui.column().apply {
                setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10)); minimumHeight = ui.dp(56)
                background = ui.bg(if (on) Pal.CARD_ON else Pal.CARD, 14, if (on) Pal.ICE else Pal.EDGE)
                addView(ui.text(name, 16f))
                addView(ui.text("$sub · $addr", 12f, Pal.DIM))
                setOnClickListener { Conn.chooseBt(a, addr, name, leOnly); close() }
            }
            // OBD'ye benzeyenler üstte
            val obd = Regex("OBD|ELM|V-?LINK|VGATE|KONNWEI|VEEPEAK|ICAR|IOS-VLINK", RegexOption.IGNORE_CASE).containsMatchIn(name)
            list.addView(t, if (obd) 0 else list.childCount, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(6) })
        }
        for (d in Conn.devices(a)) row(d.address, Conn.label(d), "eşleşmiş", leOnly = false)
        stopScan = Conn.scanBle(a) { addr, name -> row(addr, name, "yakında · BLE (deneysel)", leOnly = true) }
        list.postDelayed({ status.text = if (shown.isEmpty()) "Adaptör bulunamadı. Kontak açık ve adaptör takılı mı?" else "Adaptörünü seç" }, 10_000)
        body.addView(ui.text("Listede yoksa: klasik Bluetooth adaptörleri önce telefonun Bluetooth ayarlarından eşleştir " +
            "(PIN genelde 1234 ya da 0000). BLE adaptörler eşleştirmeden burada görünür.", 13f, Pal.DIM)
            .apply { setPadding(0, ui.dp(10), 0, 0) })
        body.addView(ui.button("Bluetooth ayarlarını aç") { Conn.openBtSettings(a) },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(8) })
    }

    private fun wifiPane(body: LinearLayout, close: () -> Unit) {
        body.addView(ui.text("Deneysel: Wi-Fi adaptörlerle henüz gerçek araçta denenmedi. Sorun olursa Ayarlar > Kayıtlar > Sorun bildir.",
            13f, Pal.AMBER).apply { setPadding(0, 0, 0, ui.dp(8)) })
        body.addView(ui.text("1. Telefonu adaptörün Wi-Fi ağına bağla (ör. \"WiFi_OBDII\", \"V-LINK\").\n" +
            "2. \"İnternet yok\" uyarısı çıkarsa \"Bağlı kal\"ı seç.\n" +
            "3. Adres genelde 192.168.0.10:35000; adaptörün kutusunda farklı yazıyorsa onu gir.", 14f, Pal.DIM))
        val input = EditText(a).apply {
            setText(Conn.wifiHost(a)); setTextColor(Pal.TEXT); textSize = 18f; isSingleLine = true
            gravity = Gravity.CENTER_VERTICAL
        }
        body.addView(input, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        val row = ui.row()
        row.addView(ui.button("Varsayılan") { input.setText(TcpLink.DEFAULT) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(8) })
        row.addView(ui.button("Kaydet", primary = true) {
            val v = input.text.toString().trim()
            if (!Regex("^[\\w.-]+(:\\d{1,5})?$").matches(v)) { input.error = "ör. 192.168.0.10:35000"; return@button }
            Conn.setWifiHost(a, if (':' in v) v else "$v:35000")
            Conn.setType(a, LinkType.WIFI)
            close()
        })
        body.addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(10) })
    }
}
