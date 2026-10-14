package com.nebi.obdbridge.dash

import android.content.Context
import android.util.Log
import com.nebi.obdbridge.BridgeService
import com.nebi.obdbridge.BuildConfig
import com.nebi.obdbridge.dash.RouteTracker.LatLon
import com.yandex.mapkit.RequestPoint
import com.yandex.mapkit.RequestPointType
import com.yandex.mapkit.directions.DirectionsFactory
import com.yandex.mapkit.directions.driving.Action
import com.yandex.mapkit.directions.driving.DrivingOptions
import com.yandex.mapkit.directions.driving.DrivingRoute
import com.yandex.mapkit.directions.driving.DrivingRouter
import com.yandex.mapkit.directions.driving.DrivingRouterType
import com.yandex.mapkit.directions.driving.DrivingSession
import com.yandex.mapkit.directions.driving.VehicleOptions
import com.yandex.mapkit.geometry.Point

/**
 * Yandex MapKit (ücretsiz sürüm): yol tarifi, manevralar ve adres arama. Anahtar yoksa ([available] false)
 * navigasyon kapalıdır. MapKit ana iş parçacığından çağrılmalı; oturumlar tutulmazsa çöp toplayıcı iptal eder.
 */
object YandexNav {
    val available get() = BuildConfig.YANDEX_MAPKIT_KEY.isNotEmpty()
    private var inited = false
    private var router: DrivingRouter? = null
    private var session: DrivingSession? = null

    fun init(ctx: Context) {
        if (inited || !available) return
        com.yandex.mapkit.MapKitFactory.setApiKey(BuildConfig.YANDEX_MAPKIT_KEY)
        com.yandex.mapkit.MapKitFactory.setLocale("tr_TR")
        com.yandex.mapkit.MapKitFactory.initialize(ctx.applicationContext)
        inited = true
    }

    fun requestRoute(ctx: Context, from: LatLon, to: LatLon, azimuth: Double?, done: (NavRoute?) -> Unit) {
        if (!available) { done(null); return }
        init(ctx)
        val r = router ?: DirectionsFactory.getInstance().createDrivingRouter(DrivingRouterType.ONLINE).also { router = it }
        val pts = listOf(
            RequestPoint(Point(from.lat, from.lon), RequestPointType.WAYPOINT, null, null, null),
            RequestPoint(Point(to.lat, to.lon), RequestPointType.WAYPOINT, null, null, null))
        val opts = DrivingOptions().setRoutesCount(1).setInitialAzimuth(azimuth)
            .setAnnotationLanguage(com.yandex.mapkit.annotations.AnnotationLanguage.TURKISH)
        session?.cancel()
        session = r.requestRoutes(pts, opts, VehicleOptions(), object : DrivingSession.DrivingRouteListener {
            override fun onDrivingRoutes(routes: MutableList<DrivingRoute>) {
                session = null
                done(routes.firstOrNull()?.let { runCatching { convert(it) }.onFailure { e -> Log.w(BridgeService.TAG, "rota çevrilemedi", e) }.getOrNull() })
            }
            override fun onDrivingRoutesError(e: com.yandex.runtime.Error) {
                session = null
                Log.w(BridgeService.TAG, "Yandex rota hatası: ${e.javaClass.simpleName}")
                done(null)
            }
        })
    }

    private fun convert(r: DrivingRoute): NavRoute {
        val pts = r.geometry.points.map { LatLon(it.latitude, it.longitude) }
        val cum = Guidance.cumulative(pts)
        val out = ArrayList<Maneuver>()
        val secs = r.sections
        for ((i, s) in secs.withIndex()) {
            val ann = s.metadata.annotation
            val act = ann.action ?: continue
            val b = s.geometry.begin
            val si = b.segmentIndex.coerceIn(0, pts.size - 1)
            val at = cum[si] + if (si + 1 < pts.size) b.segmentPosition * (cum[si + 1] - cum[si]) else 0.0
            val turn = when (act) {
                Action.STRAIGHT -> Turn.STRAIGHT
                Action.SLIGHT_LEFT -> Turn.SLIGHT_LEFT
                Action.SLIGHT_RIGHT -> Turn.SLIGHT_RIGHT
                Action.LEFT -> Turn.LEFT
                Action.RIGHT -> Turn.RIGHT
                Action.HARD_LEFT -> Turn.HARD_LEFT
                Action.HARD_RIGHT -> Turn.HARD_RIGHT
                Action.FORK_LEFT -> Turn.FORK_LEFT
                Action.FORK_RIGHT -> Turn.FORK_RIGHT
                Action.UTURN_LEFT -> Turn.UTURN_LEFT
                Action.UTURN_RIGHT -> Turn.UTURN_RIGHT
                Action.ENTER_ROUNDABOUT -> Turn.ROUNDABOUT
                Action.LEAVE_ROUNDABOUT -> Turn.LEAVE_ROUNDABOUT
                Action.EXIT_LEFT -> Turn.EXIT_LEFT
                Action.EXIT_RIGHT -> Turn.EXIT_RIGHT
                Action.FINISH -> Turn.FINISH
                else -> null   // feribot, ara nokta
            } ?: continue
            // Başlangıçtaki "ilerleyin" ve düz geçişler ok göstermez
            if (turn == Turn.STRAIGHT || at < 5 && turn != Turn.FINISH) continue
            // Göbeğe girerken çıkış numarası, çıkış bölümünün bilgisinde
            val exit = if (turn == Turn.ROUNDABOUT) secs.drop(i + 1).firstOrNull { it.metadata.annotation.action == Action.LEAVE_ROUNDABOUT }
                ?.metadata?.annotation?.actionMetadata?.leaveRoundaboutMetadata?.exitNumber else null
            out += Maneuver(at, turn, ann.toponym.orEmpty(), exit)
        }
        // Göbekten çıkış ayrıca söylenmesin (girişte "üçüncü çıkış" dendi)
        val ms = out.filterIndexed { i, m -> !(m.turn == Turn.LEAVE_ROUNDABOUT && out.getOrNull(i - 1)?.turn == Turn.ROUNDABOUT) }.toMutableList()
        if (ms.lastOrNull()?.turn != Turn.FINISH) ms += Maneuver(cum.last(), Turn.FINISH, "")
        return NavRoute(pts, ms, r.metadata.weight.timeWithTraffic.value)
    }
}
