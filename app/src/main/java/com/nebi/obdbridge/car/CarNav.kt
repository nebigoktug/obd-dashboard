package com.nebi.obdbridge.car

import androidx.car.app.CarContext
import androidx.car.app.model.CarIcon
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import androidx.core.graphics.drawable.IconCompat
import com.nebi.obdbridge.L
import com.nebi.obdbridge.dash.Guidance
import com.nebi.obdbridge.dash.ManeuverIcon
import com.nebi.obdbridge.dash.Nav
import com.nebi.obdbridge.dash.NavRunner
import com.nebi.obdbridge.dash.Turn
import com.nebi.obdbridge.dash.Units
import com.nebi.obdbridge.dash.VoicePlanner
import com.nebi.obdbridge.t
import java.util.TimeZone

/**
 * Navigasyonu Android Auto'ya bildirir (İş 3 adım 2c, 2026-11-27): başladı/bitti, sıradaki manevra, varış.
 * AA bunları kendi arayüzünde (başka uygulamaya geçince kart, gösterge paneli) gösterir; aracın "navigasyonu durdur"
 * komutu rotayı bitirir. Rota ve konum telefondaki NavRunner/NavService'ten; burada yalnız okunur.
 */
class CarNav(private val ctx: CarContext) {
    private val nm = ctx.getCarService(NavigationManager::class.java)
    private var started = false

    init {
        nm.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() { started = false; NavRunner.stop(ctx) }
            override fun onAutoDriveEnabled() {}
        })
    }

    /** Saniyede bir */
    fun tick() {
        if (!Nav.active) { end(); return }
        if (!started) { nm.navigationStarted(); started = true }
        val g = Nav.guidance
        // AA sık güncellemeyi kısıyor ("Rate limiting turn event"): yalnız ekrandaki yazı değişince, en sık 2 sn'de bir
        val key = if (g == null) "loading" else "${g.nextIndex}|${g.nextDistM?.let(Units::navDist)}|${Units.navDist(g.remainingM)}"
        val now = System.currentTimeMillis()
        if (key == lastKey || now - lastSent < 2000) return
        lastKey = key; lastSent = now
        nm.updateTrip(if (g == null) Trip.Builder().setLoading(true).build() else trip(g))
    }
    private var lastKey = ""
    private var lastSent = 0L

    private fun end() { if (started) { nm.navigationEnded(); started = false; lastKey = "" } }

    fun close() {
        end()
        nm.clearNavigationManagerCallback()
    }

    private fun trip(g: Guidance): Trip {
        val now = System.currentTimeMillis()
        val tz = TimeZone.getDefault()
        val remM = g.remainingM
        val remS = g.remainingS
        val b = Trip.Builder()
        g.next?.let { m ->
            val d = g.nextDistM ?: 0.0
            val maneuver = Maneuver.Builder(type(m.turn, m.exit)).apply {
                if (m.turn == Turn.ROUNDABOUT && m.exit != null) setRoundaboutExitNumber(m.exit)
                ManeuverIcon.get(m.turn)?.let { setIcon(CarIcon.Builder(IconCompat.createWithBitmap(it)).build()) }
            }.build()
            val cue = VoicePlanner.phrase(m).replaceFirstChar { it.uppercase(L.locale) }
            val step = Step.Builder(cue).setManeuver(maneuver).apply { if (m.street.isNotBlank()) setRoad(m.street) }.build()
            val stepS = if (remS != null && remM > 0) remS * d / remM else null
            b.addStep(step, estimate(d, stepS, now, tz))
        }
        val name = Nav.destName.ifEmpty { t("Varış") }
        b.addDestination(Destination.Builder().setName(name).build(), estimate(remM, remS, now, tz))
        return b.build()
    }

    private fun estimate(m: Double, s: Double?, now: Long, tz: TimeZone): TravelEstimate {
        val arrive = DateTimeWithZone.create(now + ((s ?: 0.0) * 1000).toLong(), tz)
        return TravelEstimate.Builder(distance(m), arrive).apply { if (s != null) setRemainingTimeSeconds(s.toLong()) }.build()
    }

    companion object {
        /** Ekrandaki yazımla aynı birim ve yuvarlama (Units.navDist) */
        fun distance(m: Double): Distance {
            if (Units.miles) {
                val mi = m / 1000 / Units.KM_PER_MI
                return if (mi < 0.1) Distance.create(Math.round(m / Units.M_PER_FT / 50) * 50.0, Distance.UNIT_FEET)
                else Distance.create(if (mi < 10) Math.round(mi * 10) / 10.0 else Math.round(mi).toDouble(), Distance.UNIT_MILES)
            }
            return when {
                m < 1000 -> Distance.create(Math.round(m / 10) * 10.0, Distance.UNIT_METERS)
                m < 10_000 -> Distance.create(Math.round(m / 100) / 10.0, Distance.UNIT_KILOMETERS_P1)
                else -> Distance.create(Math.round(m / 1000).toDouble(), Distance.UNIT_KILOMETERS)
            }
        }

        /** Sağdan akan trafik (Türkiye): göbekler saat yönünün tersine */
        fun type(turn: Turn, exit: Int?): Int = when (turn) {
            Turn.STRAIGHT -> Maneuver.TYPE_STRAIGHT
            Turn.SLIGHT_LEFT -> Maneuver.TYPE_TURN_SLIGHT_LEFT
            Turn.SLIGHT_RIGHT -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
            Turn.LEFT -> Maneuver.TYPE_TURN_NORMAL_LEFT
            Turn.RIGHT -> Maneuver.TYPE_TURN_NORMAL_RIGHT
            Turn.HARD_LEFT -> Maneuver.TYPE_TURN_SHARP_LEFT
            Turn.HARD_RIGHT -> Maneuver.TYPE_TURN_SHARP_RIGHT
            Turn.FORK_LEFT -> Maneuver.TYPE_FORK_LEFT
            Turn.FORK_RIGHT -> Maneuver.TYPE_FORK_RIGHT
            Turn.UTURN_LEFT -> Maneuver.TYPE_U_TURN_LEFT
            Turn.UTURN_RIGHT -> Maneuver.TYPE_U_TURN_RIGHT
            Turn.ROUNDABOUT -> if (exit != null) Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW else Maneuver.TYPE_ROUNDABOUT_ENTER_CCW
            Turn.LEAVE_ROUNDABOUT -> Maneuver.TYPE_ROUNDABOUT_EXIT_CCW
            Turn.EXIT_LEFT -> Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT
            Turn.EXIT_RIGHT -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
            Turn.FINISH -> Maneuver.TYPE_DESTINATION
        }
    }
}
