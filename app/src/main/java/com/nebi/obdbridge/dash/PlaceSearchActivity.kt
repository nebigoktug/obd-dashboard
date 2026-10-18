package com.nebi.obdbridge.dash

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.yandex.mapkit.geometry.BoundingBox
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.search.Response
import com.yandex.mapkit.search.SearchFactory
import com.yandex.mapkit.search.SearchManager
import com.yandex.mapkit.search.SearchManagerType
import com.yandex.mapkit.search.SearchOptions
import com.yandex.mapkit.search.SearchType
import com.yandex.mapkit.search.Session
import com.yandex.mapkit.search.SuggestItem
import com.yandex.mapkit.search.SuggestOptions
import com.yandex.mapkit.search.SuggestResponse
import com.yandex.mapkit.search.SuggestSession
import com.yandex.mapkit.search.SuggestType

/**
 * Hedef arama (Yandex arama önerileri): yazdıkça yakındaki yerler listelenir, dokununca
 * [MODE_NAV]'da navigasyon başlar, [MODE_HOME]/[MODE_WORK]'te o düğmeye kaydedilir.
 */
class PlaceSearchActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var input: EditText
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var search: SearchManager
    private lateinit var suggest: SuggestSession
    private var session: Session? = null
    private val here get() = Nav.lastFix
    private val mode get() = intent.getStringExtra(EXTRA_MODE) ?: MODE_NAV

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        YandexNav.init(this)
        search = SearchFactory.getInstance().createSearchManager(SearchManagerType.ONLINE)
        suggest = search.createSuggestSession()

        val root = ui.column(16).apply { setBackgroundColor(Pal.BG) }
        val bar = ui.row()
        bar.addView(ui.text("‹", 30f, Pal.ICE).apply { setPadding(ui.dp(4), 0, ui.dp(16), 0); setOnClickListener { finish() } })
        input = EditText(this).apply {
            hint = when (mode) { MODE_HOME -> "Ev adresi"; MODE_WORK -> "İş adresi"; else -> "Nereye? Adres ya da yer adı" }
            setTextColor(Pal.TEXT); setHintTextColor(Pal.DIM); textSize = 20f
            background = ui.bg(Pal.CARD, 12)
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query(s.toString()) }
            })
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_SEARCH || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { submit(text.toString()); true } else false
            }
        }
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        status = ui.text("", 14f, Pal.DIM).apply { setPadding(ui.dp(8), ui.dp(10), 0, ui.dp(4)) }
        root.addView(status)
        list = ui.column()
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        fillCutout(root)
        if (here == null) status.text = "Konum henüz yok: sonuçlar yakından sıralanmayabilir"
        input.requestFocus()
        input.postDelayed({ (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, 0) }, 200)
    }

    override fun onStart() { super.onStart(); com.yandex.mapkit.MapKitFactory.getInstance().onStart() }
    override fun onStop() { com.yandex.mapkit.MapKitFactory.getInstance().onStop(); super.onStop() }
    override fun onDestroy() { session?.cancel(); suggest.reset(); super.onDestroy() }

    private fun area(): BoundingBox {
        val p = here ?: RouteTracker.LatLon(39.0, 35.0)       // konum yoksa Türkiye
        val r = if (here != null) 0.6 else 6.0
        return BoundingBox(Point(p.lat - r, p.lon - r), Point(p.lat + r, p.lon + r))
    }

    private fun query(q: String) {
        if (q.isBlank()) { suggest.reset(); list.removeAllViews(); return }
        val opts = SuggestOptions().setSuggestTypes(SuggestType.GEO.value or SuggestType.BIZ.value)
        here?.let { opts.userPosition = Point(it.lat, it.lon) }
        suggest.suggest(q, area(), opts, object : SuggestSession.SuggestListener {
            override fun onResponse(r: SuggestResponse) { show(r.items) }
            override fun onError(e: com.yandex.runtime.Error) { YandexNav.onError(this@PlaceSearchActivity, e); status.text = errText(e) }
        })
    }

    private fun show(items: List<SuggestItem>) {
        status.text = if (items.isEmpty()) "Sonuç yok" else ""
        list.removeAllViews()
        for (it in items.take(8)) {
            val row = ui.column().apply {
                background = ui.bg(Pal.CARD, 12)
                setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
                setOnClickListener { _ -> pick(it) }
            }
            val top = ui.row()
            top.addView(ui.text(it.title.text, 18f), LinearLayout.LayoutParams(0, -2, 1f))
            it.distance?.text?.let { d -> top.addView(ui.text(d, 14f, Pal.DIM).apply { gravity = Gravity.END }) }
            row.addView(top)
            it.subtitle?.text?.takeIf { s -> s.isNotBlank() }?.let { s -> row.addView(ui.text(s, 14f, Pal.DIM)) }
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })
        }
    }

    private fun pick(it: SuggestItem) {
        val name = it.title.text
        val c = it.center
        when {
            it.action == SuggestItem.Action.SUBSTITUTE -> { input.setText(it.displayText ?: it.searchText); input.setSelection(input.text.length) }
            c != null -> done(c.latitude, c.longitude, name)
            it.uri != null -> resolve(name) { l -> session = search.searchByURI(it.uri!!, SearchOptions(), l) }
            else -> submit(it.searchText ?: name)
        }
    }

    /** Öneri seçmeden "Ara": ilk sonuca git */
    private fun submit(q: String) {
        if (q.isBlank()) return
        resolve(null) { l ->
            val opts = SearchOptions().setSearchTypes(SearchType.GEO.value or SearchType.BIZ.value).setResultPageSize(1)
            here?.let { opts.userPosition = Point(it.lat, it.lon) }
            session = search.submit(q, com.yandex.mapkit.geometry.Geometry.fromBoundingBox(area()), opts, l)
        }
    }

    private fun resolve(name: String?, run: (Session.SearchListener) -> Unit) {
        status.text = "Aranıyor…"
        run(object : Session.SearchListener {
            override fun onSearchResponse(r: Response) {
                val obj = r.collection.children.firstOrNull()?.obj
                val p = obj?.geometry?.firstNotNullOfOrNull { g -> g.point }
                if (obj == null || p == null) { status.text = "Yer bulunamadı"; return }
                done(p.latitude, p.longitude, name ?: obj.name.orEmpty())
            }
            override fun onSearchError(e: com.yandex.runtime.Error) { YandexNav.onError(this@PlaceSearchActivity, e); status.text = errText(e) }
        })
    }

    private fun done(lat: Double, lon: Double, name: String) {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        when (mode) {
            MODE_HOME, MODE_WORK -> getSharedPreferences("dash", Context.MODE_PRIVATE).edit()
                .putString("place_$mode", "$lat,$lon").putString("place_${mode}_name", name).apply()
            else -> Nav.start(this, lat, lon, name)
        }
        finish()
    }

    private fun errText(e: com.yandex.runtime.Error) =
        when (e) {
            is com.yandex.runtime.network.NetworkError -> "İnternet bağlantısı yok"
            is com.yandex.runtime.network.UnauthorizedError, is com.yandex.runtime.network.ForbiddenError ->
                "Yandex aramaya izin vermedi (anahtar reddedildi). Ayarlar › Navigasyon'dan kendi anahtarını girebilirsin"
            else -> "Arama yapılamadı"
        }

    override fun onResume() {
        super.onResume()
        if (android.os.Build.VERSION.SDK_INT >= 30) window.insetsController?.let {
            it.hide(android.view.WindowInsets.Type.systemBars())
            it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else @Suppress("DEPRECATION") {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_NAV = "nav"
        const val MODE_HOME = "home"
        const val MODE_WORK = "work"

        fun open(ctx: Context, mode: String) {
            YandexNav.unavailable(ctx)?.let {
                android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_LONG).show()
                return
            }
            ctx.startActivity(Intent(ctx, PlaceSearchActivity::class.java).putExtra(EXTRA_MODE, mode))
        }
    }
}
