package com.nebi.obdbridge

import android.app.Application
import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Dil desteği (2026-11-04). Kaynak dil Türkçe: metinler kodda Türkçe yazılır ve [t] ile sarılır, anahtar Türkçe metnin kendisi.
 * Diğer diller "Türkçe → çeviri" eşlemesi tutan JSON paketleri: hazırlar assets/lang/, kullanıcının içe aktardığı files/lang/user.json.
 * Çevirisi olmayan metin Türkçe kalır (kullanıcı paketinde İngilizce). Dil değişince uygulama yeniden başlar (sabit tanımlardaki etiketler de yeniden kurulsun).
 * Şablon ve eksik çeviri denetimi: tools/i18n.py
 */
object L {
    private const val TAG = "I18n"
    const val SYSTEM = ""
    const val USER = "user"
    /** Uygulamayla gelen paketler (assets/lang/<kod>.json); Türkçe kaynak dil, paketi yok */
    val BUILT_IN = listOf("tr", "en")

    /** Ayardaki seçim: SYSTEM, "tr", "en" ya da USER */
    var choice = SYSTEM; private set
    /** Etkin dilin kodu (tarih biçimi, büyük harf, ses, harita için) */
    var code = "tr"; private set
    var locale: Locale = Locale("tr"); private set
    /** Etkin paketin görünen adı (kullanıcı paketinde paketin kendi verdiği ad) */
    var name = "Türkçe"; private set
    /** Sesli yönlendirmenin dili: Türkçe cümle kurucu yalnız Türkçede, diğerlerinde İngilizce kurucu + paketin çevirileri */
    val turkish get() = code == "tr"
    private var map: Map<String, String> = emptyMap()
    /** Paketin getirdiği arıza kodu açıklamaları (isteğe bağlı "dtc" bölümü); boşsa assets/dtc_<kod>.tsv */
    var dtc: Map<String, String> = emptyMap(); private set

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        choice = app.getSharedPreferences("dash", Context.MODE_PRIVATE).getString("lang", SYSTEM) ?: SYSTEM
        val want = when (choice) {
            SYSTEM -> Locale.getDefault().language.takeIf { it in BUILT_IN } ?: "tr"
            else -> choice
        }
        load(app, want)
    }

    private fun load(ctx: Context, want: String) {
        map = emptyMap(); dtc = emptyMap(); code = "tr"; name = "Türkçe"
        if (want == "tr") { locale = Locale("tr"); return }
        val json = try {
            if (want == USER) userFile(ctx).readText() else ctx.assets.open("lang/$want.json").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "dil paketi açılamadı ($want): ${e.message}"); locale = Locale("tr"); return
        }
        val p = parse(json) ?: run { locale = Locale("tr"); return }
        // Kullanıcı paketinde eksik kalanlar Türkçe yerine İngilizce görünsün (Türkçe paketse olduğu gibi)
        val base = if (want == USER && p.code != "tr") builtIn(ctx, "en") else emptyMap()
        map = base + p.strings; dtc = p.dtc; code = p.code; name = p.name
        locale = Locale.forLanguageTag(p.code)
    }

    private fun builtIn(ctx: Context, c: String): Map<String, String> =
        try { ctx.assets.open("lang/$c.json").bufferedReader().use { parse(it.readText())?.strings } ?: emptyMap() } catch (_: Exception) { emptyMap() }

    class Pack(val code: String, val name: String, val strings: Map<String, String>, val dtc: Map<String, String>)

    /**
     * Paket biçimi: {"_meta": {"code": "en", "name": "English"}, "strings": {"Türkçe": "çeviri", ...}, "dtc": {"P0016": "...", ...}}.
     * Boş çeviriler atlanır; "dtc" isteğe bağlı.
     */
    fun parse(json: String): Pack? = try {
        val o = JSONObject(json)
        val meta = o.optJSONObject("_meta") ?: JSONObject()
        val s = o.getJSONObject("strings")
        val m = HashMap<String, String>()
        for (k in s.keys()) s.optString(k).takeIf { it.isNotBlank() }?.let { m[k] = it }
        val code = meta.optString("code").trim().ifEmpty { "en" }
        val d = HashMap<String, String>()
        o.optJSONObject("dtc")?.let { j -> for (k in j.keys()) j.optString(k).takeIf { it.isNotBlank() }?.let { d[k.uppercase()] = it } }
        Pack(code, meta.optString("name").trim().ifEmpty { code }, m, d)
    } catch (e: Exception) {
        Log.w(TAG, "dil paketi bozuk: ${e.message}"); null
    }

    fun userFile(ctx: Context) = File(ctx.filesDir, "lang/user.json")

    /** Kullanıcı paketinin adı (yüklü değilse null) */
    fun userPackName(ctx: Context): String? = userFile(ctx).takeIf { it.exists() }?.let { parse(it.readText())?.name }

    /** Seçimi kaydeder; uygulama yeniden başlatılınca geçerli olur */
    fun save(ctx: Context, choice: String) {
        ctx.getSharedPreferences("dash", Context.MODE_PRIVATE).edit().putString("lang", choice).commit()
    }

    fun t(s: String): String = map[s] ?: s
    fun t(s: String, vararg args: Any?): String = String.format(locale, t(s), *args)
    /** "tr_TR", "en_US" biçimi (Yandex MapKit) */
    val region get() = "${locale.language}_" + locale.country.ifEmpty { if (locale.language == "en") "US" else locale.language.uppercase() }
    fun upper(s: String) = s.uppercase(locale)
    fun date(pattern: String) = java.text.SimpleDateFormat(pattern, locale)
}

/** Kısa yazım: t("Bağlantı"), t("%d dolum", n) */
fun t(s: String) = L.t(s)
fun t(s: String, vararg args: Any?) = L.t(s, *args)

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        L.init(this)
    }
}
