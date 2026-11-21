package app.drivr.location

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where to draw the car on each screen frame (60 fps), between the once-a-second fixes.
 *
 * GPS says where the car *was* when the fix was taken; by the time it is drawn the car has moved
 * on. Like Google Maps, the car is drawn where it is *now*: the last fix carried forward at the
 * car's speed (dead reckoning), and any difference with a new fix is closed gradually over half a
 * second instead of jumping.
 *
 * On a route the car moves *along the route line* (it can't drift sideways off the road) and
 * points the way the road goes; off a route it moves along its own course. The car never slides
 * backwards for small corrections, and the arrow keeps its heading while standing still.
 */
class DisplayAnimator(
    /** How quickly a correction is absorbed, s. */
    private val catchUpS: Double = 0.5,
    /** How quickly the arrow turns towards a new heading, s. */
    private val turnS: Double = 0.25,
    /** Longest the car is carried forward without a new fix, s (tunnels are bridged upstream). */
    private val maxExtrapolateS: Double = 2.0,
) {
  data class Pose(val lat: Double, val lon: Double, val bearingDeg: Double?, val speedMps: Double)

  private var track: RouteTrack? = null
  private var trackKey: Any? = null

  private var fixLat = 0.0
  private var fixLon = 0.0
  private var fixSpeed = 0.0
  private var fixBearing: Double? = null
  private var fixProgress = 0.0
  private var fixAtMs = 0L
  private var hasFix = false
  private var onRoute = false

  private var shownLat = 0.0
  private var shownLon = 0.0
  private var shownProgress = 0.0
  private var shownBearing: Double? = null
  private var lastFrameMs = 0L

  /** The route the car is following, or null (browsing, off route). */
  fun setRoute(points: List<Pair<Double, Double>>?, key: Any? = points) {
    if (key == trackKey) return
    trackKey = key
    track = points?.takeIf { it.size >= 2 }?.let(::RouteTrack)
    if (track != null && hasFix) shownProgress = track!!.project(shownLat, shownLon).progressM
  }

  /** A new fix arrived at [nowMs] (same clock as [frame]). [followRoute]: the car is on the route. */
  fun onFix(lat: Double, lon: Double, speedMps: Double?, bearingDeg: Double?, nowMs: Long, followRoute: Boolean) {
    val t = track.takeIf { followRoute }
    fixLat = lat
    fixLon = lon
    fixSpeed = speedMps?.takeIf { it.isFinite() && it > 0.3 } ?: 0.0
    if (bearingDeg != null) fixBearing = bearingDeg
    fixAtMs = nowMs
    if (!hasFix) {
      hasFix = true
      shownLat = lat
      shownLon = lon
      shownBearing = bearingDeg
      lastFrameMs = nowMs
    }
    if (t != null) {
      fixProgress = t.project(lat, lon, near = if (onRoute) shownProgress else null).progressM
      if (!onRoute) shownProgress = t.project(shownLat, shownLon, near = fixProgress, back = 300.0, ahead = 300.0).progressM
    }
    onRoute = t != null
  }

  /** The pose to draw at [nowMs], or null before the first fix. */
  fun frame(nowMs: Long): Pose? {
    if (!hasFix) return null
    val dt = ((nowMs - lastFrameMs) / 1000.0).coerceIn(0.0, 0.25)
    lastFrameMs = nowMs
    val sinceFix = ((nowMs - fixAtMs) / 1000.0).coerceIn(0.0, maxExtrapolateS)
    // Fraction of the remaining difference closed this frame (frame-rate independent).
    val k = 1 - exp(-dt / catchUpS)
    // Still carrying forward from a fix: keep moving at its speed; afterwards wait for the next.
    val moving = (nowMs - fixAtMs) / 1000.0 < maxExtrapolateS
    val v = if (moving) fixSpeed else 0.0

    val t = track
    val targetBearing: Double?
    if (onRoute && t != null) {
      val target = fixProgress + fixSpeed * sinceFix
      val err = target - shownProgress
      shownProgress =
          when {
            abs(err) > 150.0 -> target
            // Behind by a little: don't drive backwards, just slow down until the fix catches up.
            err < 0 && err > -25.0 -> shownProgress + (v * dt + err * k).coerceAtLeast(0.0)
            else -> shownProgress + v * dt + err * k
          }.coerceIn(0.0, t.lengthM)
      val (la, lo) = t.positionAt(shownProgress)
      shownLat = la
      shownLon = lo
      targetBearing = t.bearingAt(shownProgress)
    } else {
      val f = LocalFrame(fixLat, fixLon)
      val b = fixBearing
      val (vx, vy) = if (b != null && fixSpeed > 1.0) {
        val r = Math.toRadians(b)
        fixSpeed * sin(r) to fixSpeed * cos(r)
      } else 0.0 to 0.0
      val tx = vx * sinceFix
      val ty = vy * sinceFix
      val sx = f.x(shownLon)
      val sy = f.y(shownLat)
      val ex = tx - sx
      val ey = ty - sy
      val nx: Double
      val ny: Double
      if (sqrt(ex * ex + ey * ey) > 200.0) {
        nx = tx; ny = ty
      } else {
        val mv = if (moving) 1.0 else 0.0
        nx = sx + vx * mv * dt + ex * k
        ny = sy + vy * mv * dt + ey * k
      }
      shownLat = f.lat(ny)
      shownLon = f.lon(nx)
      targetBearing = fixBearing
    }

    val sb = shownBearing
    shownBearing =
        when {
          targetBearing == null -> sb
          sb == null -> targetBearing
          else -> normDeg(sb + angleDelta(sb, targetBearing) * (1 - exp(-dt / turnS)))
        }
    return Pose(shownLat, shownLon, shownBearing, fixSpeed)
  }
}
