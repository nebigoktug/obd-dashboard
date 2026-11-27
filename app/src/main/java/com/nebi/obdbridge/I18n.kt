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
        choice = fromSystem(app, choice)
        val want = when (choice) {
            // Telefon Türkçeyse Türkçe, değilse İngilizce
            SYSTEM -> if (Locale.getDefault().language == "tr") "tr" else "en"
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

    /** Seçimi kaydeder; uygulama yeniden başlatılınca geçerli olur. Android 13+ sistem "Uygulama dili" de aynı olur. */
    fun save(ctx: Context, choice: String) {
        ctx.getSharedPreferences("dash", Context.MODE_PRIVATE).edit().putString("lang", choice).commit()
        toSystem(ctx, choice)
    }

    // ---------- Android 13+ sistem "Uygulama dili" (res/xml/locales_config.xml) ----------
    // Uygulama dili kendi ayarıyla çalışır (JSON paketleri); sistem listesindeki seçim bu ayara çevrilir ve tersi.

    /** Uygulama içi seçimi sisteme yaz: tr/en o dil; telefonun dili ve kullanıcı paketi "sistem varsayılanı" */
    private fun toSystem(ctx: Context, choice: String) {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val lm = ctx.getSystemService(android.app.LocaleManager::class.java) ?: return
        val want = if (choice in BUILT_IN) android.os.LocaleList.forLanguageTags(choice) else android.os.LocaleList.getEmptyLocaleList()
        if (lm.applicationLocales != want) runCatching { lm.applicationLocales = want }
        ctx.getSharedPreferences("dash", Context.MODE_PRIVATE).edit().putBoolean(SYNCED, true).apply()
    }

    /**
     * Sistem ayarından değiştirildiyse onu al. İlk açılışta (eski sürümden gelen seçim) önce uygulama içi seçim sisteme
     * yazılır; sonra sistemde "varsayılan" seçilmesi telefonun diline dönmek demektir (kullanıcı paketi hariç).
     */
    private fun fromSystem(ctx: Context, saved: String): String {
        if (android.os.Build.VERSION.SDK_INT < 33) return saved
        val p = ctx.getSharedPreferences("dash", Context.MODE_PRIVATE)
        if (!p.getBoolean(SYNCED, false)) { toSystem(ctx, saved); return saved }
        val sys = ctx.getSystemService(android.app.LocaleManager::class.java)?.applicationLocales
            ?.takeIf { !it.isEmpty }?.get(0)?.language
        val now = systemChoice(saved, sys)
        if (now != saved) p.edit().putString("lang", now).apply()
        return now
    }

    /** Sistemdeki dil ([sys], boş = varsayılan) ile kayıtlı seçimden geçerli seçim (JVM testli) */
    fun systemChoice(saved: String, sys: String?): String = when {
        sys != null && sys in BUILT_IN -> sys
        sys == null && saved in BUILT_IN -> SYSTEM
        else -> saved
    }

    private const val SYNCED = "lang_sys_synced"

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
    /**
     * Android 13+: sistem ayarlarından uygulama dili değişince süreç yeniden başlamaz, yalnız ekranlar yeniden kurulur.
     * Ekranlar kurulmadan önce dil ve dile bağlı sabitler (gösterge etiketleri) yenilenir.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        reloadLanguage()
    }

    /** Dil sistemden değiştiyse yeniden oku; değiştiyse true. Bazı telefonlar (HyperOS) çalışan uygulamaya bildirmiyor:
     * ekrana her dönüşte de bakılır ve ekran yeni dille yeniden kurulur. */
    fun reloadLanguage(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 33) return false
        val before = L.choice
        L.init(this)
        if (L.choice == before) return false
        com.nebi.obdbridge.dash.SignalCatalog.configure(com.nebi.obdbridge.dash.Vehicle.current)
        return true
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: android.app.Activity) { if (reloadLanguage()) a.recreate() }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityStarted(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        L.init(this)
        com.nebi.obdbridge.dash.Units.load(this)
    }
}
