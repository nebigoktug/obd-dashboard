package com.nebi.obdbridge.dash

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.nebi.obdbridge.BridgeService
import com.nebi.obdbridge.link.LinkType
import com.nebi.obdbridge.link.TcpLink

// Ayarlar ve ilk açılış sihirbazı için ortak küçük arayüz takımı (2026-10-10, forum sürümü):
// referans temanın renkleri, büyük dokunma alanları, her seçeneğin altında tek cümlelik açıklama.

object Pal {
    const val BG = 0xFF0E1013.toInt()
    const val CARD = 0xFF161B21.toInt()
    const val CARD_ON = 0xFF1F2A33.toInt()
    const val EDGE = 0x26FFFFFF
    const val TEXT = 0xFFF2F5F8.toInt()
    const val DIM = 0xFF9AA2A9.toInt()
    const val ICE = 0xFF7FB5DD.toInt()
    const val AMBER = 0xFFE08B36.toInt()
    const val RED = 0xFFE0603A.toInt()
}

class Ui(private val ctx: Context) {
    private val d = ctx.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()

    fun bg(color: Int, radius: Int = 14, stroke: Int = Pal.EDGE) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius * d; setStroke(maxOf(1, (1 * d).toInt()), stroke)
    }

    fun text(s: CharSequence, size: Float = 16f, color: Int = Pal.TEXT, light: Boolean = false, bold: Boolean = false) =
        TextView(ctx).apply {
            text = s; textSize = size; setTextColor(color)
            typeface = Typeface.create(if (light) "sans-serif-light" else "sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

    fun caps(s: String, color: Int = Pal.ICE) = text(s.uppercase(), 12f, color).apply { letterSpacing = 0.15f }

    fun column(pad: Int = 0) = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(pad), dp(pad), dp(pad), dp(pad)) }
    fun row() = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    /** Büyük düğme: birincil (dolu buz mavisi) ya da ikincil (çerçeve). */
    fun button(s: String, primary: Boolean = false, onClick: () -> Unit) = text(s, 16f, if (primary) Pal.BG else Pal.TEXT, bold = primary).apply {
        gravity = Gravity.CENTER
        minHeight = dp(52); setPadding(dp(22), dp(10), dp(22), dp(10))
        background = if (primary) bg(Pal.ICE, 26, Pal.ICE) else bg(Pal.CARD, 26)
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    /** Seçenek grubu (segment): tek seçim. */
    fun <T> chips(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit): LinearLayout {
        val r = row()
        val views = ArrayList<TextView>()
        fun paint(sel: T) = options.forEachIndexed { i, (v, _) ->
            val on = v == sel
            views[i].background = bg(if (on) Pal.CARD_ON else Pal.CARD, 22, if (on) Pal.ICE else Pal.EDGE)
            views[i].setTextColor(if (on) Pal.TEXT else Pal.DIM)
        }
        for ((v, label) in options) {
            val t = text(label, 15f).apply {
                gravity = Gravity.CENTER; minHeight = dp(46); setPadding(dp(16), 0, dp(16), 0)
                setOnClickListener { paint(v); onSelect(v) }
            }
            views += t
            r.addView(t, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        paint(selected)
        return r
    }

    /**
     * Ayar satırı: başlık + açıklama solda, değer (ya da anahtar) sağda. Tümü dokunulabilir (≥ 64 dp).
     * [value] her yenilemede yeniden okunur.
     */
    fun item(title: String, summary: String?, value: (() -> String?)? = null, danger: Boolean = false, onClick: (() -> Unit)?): SettingRow =
        SettingRow(ctx, this, title, summary, value, null, danger, onClick)

    fun toggle(title: String, summary: String?, get: () -> Boolean, set: (Boolean) -> Unit): SettingRow =
        SettingRow(ctx, this, title, summary, null, get, false) { set(!get()) }
}

/** Ayarlar listesindeki tek satır; [refresh] ile değer/anahtar güncellenir. */
@SuppressLint("ViewConstructor")
class SettingRow(
    ctx: Context, private val ui: Ui, title: String, private val summary: String?,
    private val value: (() -> String?)?, private val sw: (() -> Boolean)?, danger: Boolean, onClick: (() -> Unit)?,
) : LinearLayout(ctx) {
    private val valueView = ui.text("", 15f, Pal.ICE).apply {
        maxLines = 2; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.END
    }
    private val knob = View(ctx)
    private val track = LinearLayout(ctx)

    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ui.dp(64); setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(10))
        background = ui.bg(Pal.CARD, 14)
        val left = ui.column()
        left.addView(ui.text(title, 16f, if (danger) Pal.RED else Pal.TEXT))
        if (summary != null) left.addView(ui.text(summary, 13f, Pal.DIM).apply { setPadding(0, ui.dp(2), 0, 0) })
        addView(left, LayoutParams(0, -2, 1f))
        if (sw != null) {
            track.addView(knob, LayoutParams(ui.dp(22), ui.dp(22)))
            track.setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3))
            addView(track, LayoutParams(ui.dp(50), ui.dp(28)).apply { marginStart = ui.dp(12) })
        } else if (value != null) {
            addView(valueView, LayoutParams(-2, -2).apply { marginStart = ui.dp(12) })
        }
        if (onClick != null) { isClickable = true; isFocusable = true; setOnClickListener { onClick(); refresh() } }
        refresh()
    }

    fun refresh() {
        sw?.let { on ->
            val o = on()
            track.gravity = if (o) Gravity.END else Gravity.START
            track.background = ui.bg(if (o) Pal.ICE else 0xFF2E343B.toInt(), 14, Color.TRANSPARENT)
            knob.background = ui.bg(if (o) Pal.BG else Pal.DIM, 11, Color.TRANSPARENT)
        }
        value?.let { valueView.text = it() ?: "" }
    }
}

/** Bağlantı işleri: eşleşmiş cihazlar, köprü servisini başlatma/durdurma. Ayarlar ve sihirbaz ortak kullanır. */
object Conn {
    fun hasBt(ctx: Context) = Build.VERSION.SDK_INT < 31 ||
        ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun btOn(ctx: Context) = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun devices(ctx: Context): List<BluetoothDevice> {
        val a = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return emptyList()
        if (!a.isEnabled || !hasBt(ctx)) return emptyList()
        // OBD adaptörüne benzeyenler başta
        val obd = Regex("OBD|ELM|V-?LINK|VGATE|KONNWEI|VEEPEAK|CAR", RegexOption.IGNORE_CASE)
        return a.bondedDevices.sortedWith(compareBy({ !obd.containsMatchIn(it.name ?: "") }, { it.name ?: "" }))
    }

    /** Cihaz adı; [devices] yalnız izin varken liste döndürdüğü için güvenli */
    @SuppressLint("MissingPermission")
    fun label(d: BluetoothDevice): String = d.name ?: "?"

    @SuppressLint("MissingPermission")
    fun name(ctx: Context, addr: String?): String? = addr?.let { a -> devices(ctx).firstOrNull { it.address == a }?.name ?: a }

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(BridgeService.PREFS, Context.MODE_PRIVATE)
    fun address(ctx: Context): String? = prefs(ctx).getString(BridgeService.EXTRA_ADDR, null)
    fun setAddress(ctx: Context, a: String) = prefs(ctx).edit().putString(BridgeService.EXTRA_ADDR, a).apply()

    fun type(ctx: Context): LinkType = try { LinkType.valueOf(prefs(ctx).getString(BridgeService.PREF_LINK_TYPE, null) ?: "SPP") }
        catch (_: Exception) { LinkType.SPP }
    fun setType(ctx: Context, t: LinkType) = prefs(ctx).edit().putString(BridgeService.PREF_LINK_TYPE, t.name).apply()
    fun wifiHost(ctx: Context): String = prefs(ctx).getString(BridgeService.PREF_WIFI_HOST, null) ?: TcpLink.DEFAULT
    fun setWifiHost(ctx: Context, h: String) = prefs(ctx).edit().putString(BridgeService.PREF_WIFI_HOST, h).apply()
    /** Kullanıcıya iki tür gösterilir: Bluetooth (klasik ya da BLE, otomatik) ve Wi-Fi */
    fun isWifi(ctx: Context) = type(ctx) == LinkType.WIFI

    /** Bluetooth adaptörü seç: türü cihazdan anlaşılır (yalnız BLE ise BLE, değilse klasik; klasik olmazsa servis BLE dener) */
    @SuppressLint("MissingPermission")
    fun chooseBt(ctx: Context, address: String, name: String?, leOnly: Boolean) {
        setAddress(ctx, address)
        val dev = try { (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.getRemoteDevice(address) } catch (_: Exception) { null }
        val le = leOnly || (try { dev?.type } catch (_: SecurityException) { null }) == BluetoothDevice.DEVICE_TYPE_LE
        setType(ctx, if (le) LinkType.BLE else LinkType.SPP)
        prefs(ctx).edit().putString("bt_name", name).apply()
    }

    /** Bağlanmaya hazır mı (adaptör seçilmiş, gerekli izin var) */
    fun configured(ctx: Context) = when (type(ctx)) {
        LinkType.WIFI -> true
        else -> address(ctx) != null && hasBt(ctx)
    }

    fun start(ctx: Context): Boolean {
        if (!configured(ctx)) return false
        ctx.startForegroundService(Intent(ctx, BridgeService::class.java))
        return true
    }

    /** Ayarlarda gösterilen adaptör adı */
    fun label(ctx: Context): String? = when (type(ctx)) {
        LinkType.WIFI -> wifiHost(ctx)
        else -> address(ctx)?.let { a -> prefs(ctx).getString("bt_name", null) ?: name(ctx, a) ?: a }
    }

    fun hasScan(ctx: Context) = if (Build.VERSION.SDK_INT >= 31)
        ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    else ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun scanPerms(): Array<String> = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    /**
     * Yakındaki BLE cihazlarını [ms] boyunca tarar; her yeni cihazda [found] (adres, ad) çağrılır (ana thread).
     * Döndürülen fonksiyon taramayı durdurur. OBD adaptörlerinin çoğu adını yayınlar; adsızlar listelenmez.
     */
    @SuppressLint("MissingPermission")
    fun scanBle(ctx: Context, ms: Long = 10_000, found: (String, String) -> Unit): () -> Unit {
        val sc = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bluetoothLeScanner ?: return {}
        val seen = HashSet<String>()
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        val cb = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(type: Int, r: android.bluetooth.le.ScanResult) {
                val name = r.scanRecord?.deviceName ?: (try { r.device.name } catch (_: SecurityException) { null }) ?: return
                if (seen.add(r.device.address)) h.post { found(r.device.address, name) }
            }
        }
        val stop = { try { sc.stopScan(cb) } catch (_: Exception) {} }
        try { sc.startScan(cb) } catch (_: Exception) { return {} }
        h.postDelayed({ stop() }, ms)
        return stop
    }

    fun stop(ctx: Context) = ctx.stopService(Intent(ctx, BridgeService::class.java))

    /** Kullanıcıya gösterilecek kısa bağlantı durumu */
    fun status(ctx: Context): String = when {
        type(ctx) != LinkType.WIFI && !hasBt(ctx) -> "Bluetooth izni yok"
        type(ctx) != LinkType.WIFI && !btOn(ctx) && !BridgeService.running -> "Bluetooth kapalı"
        type(ctx) != LinkType.WIFI && address(ctx) == null -> "Adaptör seçilmedi"
        !BridgeService.running -> "Bağlı değil"
        else -> BridgeService.status
    }

    fun openBtSettings(a: Activity) = a.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
}

/** Araç profilini kaydet/yükle ve uygula (katalog ölçekleri). */
object VehicleStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("dash", Context.MODE_PRIVATE)

    fun load(ctx: Context): Vehicle {
        // Profil yoksa (eski kurulum: S40 1.6D) varsayılan; logo eski kurulumda açık
        val v = Vehicle.fromJson(prefs(ctx).getString("vehicle", null)) ?: Vehicle()
        apply(v)
        return v
    }

    fun save(ctx: Context, v: Vehicle) {
        prefs(ctx).edit().putString("vehicle", v.toJson()).apply()
        apply(v)
    }

    private fun apply(v: Vehicle) {
        Vehicle.current = v
        SignalCatalog.configure(v)
    }
}
