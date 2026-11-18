package app.drivr.nav

import app.drivr.ar.Enu
import app.drivr.ar.LatLng
import app.drivr.ar.angleDiffDeg
import app.drivr.ar.bearingDeg
import app.drivr.ar.toEnu
import kotlin.math.abs
import kotlin.math.hypot

/** Where the car is relative to the route. */
sealed interface RouteStanding {
  data object OnRoute : RouteStanding

  /** Driving along the route, but against its direction. */
  data class WrongWay(val sinceMs: Long) : RouteStanding

  /**
   * Away from the route.
   *
   * @param distanceM straight-line distance to the nearest point of the route.
   * @param turnDeg which way the route lies relative to where the car is heading: 0 straight on,
   *   -90 to the left, +90 to the right, 180 behind.
   */
  data class OffRoute(val distanceM: Double, val turnDeg: Double) : RouteStanding
}

/** One raw GPS fix: not snapped to the route, so it can disagree with it. */
data class RawFix(
    val position: LatLng,
    val courseDeg: Double?,
    val speedMps: Double,
    val accuracyM: Double,
    val timeMs: Long,
)

internal data class NearestOnRoute(val distanceM: Double, val point: Enu, val bearingDeg: Double, val segment: Int)

/** Closest point on [route] (local metres around the car, so the car is the origin). */
internal fun nearestOnRoute(route: List<Enu>): NearestOnRoute? {
  if (route.size < 2) return null
  var best: NearestOnRoute? = null
  for (i in 0 until route.size - 1) {
    val a = route[i]
    val b = route[i + 1]
    val abx = b.x - a.x
    val aby = b.y - a.y
    val len2 = abx * abx + aby * aby
    if (len2 < 1e-6) continue
    val t = ((-a.x) * abx + (-a.y) * aby) / len2
    val tc = t.coerceIn(0.0, 1.0)
    val p = Enu(a.x + abx * tc, a.y + aby * tc)
    val d = hypot(p.x, p.y)
    if (best == null || d < best.distanceM) best = NearestOnRoute(d, p, bearingDeg(a, b), i)
  }
  return best
}

/**
 * Watches raw GPS against the route and says when the driver is going the wrong way or has left
 * the route. Wrong way needs a few consecutive seconds of driving against the route on the route
 * itself, so a GPS glitch or a U-shaped slip road doesn't raise it; it clears as soon as the car
 * points the right way again.
 */
class RouteGuard(
    private val wrongWayAfterMs: Long = 2_500,
    private val offRouteAfterMs: Long = 3_000,
) {
  var standing: RouteStanding = RouteStanding.OnRoute
    private set

  private var againstSinceMs: Long? = null
  private var alignedSinceMs: Long? = null
  private var awaySinceMs: Long? = null

  fun reset() {
    standing = RouteStanding.OnRoute
    againstSinceMs = null
    alignedSinceMs = null
    awaySinceMs = null
  }

  /** Feeds one fix; returns the new standing. [route] is the full route geometry. */
  fun update(fix: RawFix, route: List<LatLng>): RouteStanding {
    if (route.size < 2) {
      reset()
      return standing
    }
    val local = route.map { it.toEnu(fix.position) }
    val near = nearestOnRoute(local) ?: return standing
    val now = fix.timeMs
    val course = fix.courseDeg
    val moving = fix.speedMps >= 2.0 && course != null

    // ---- Off the route: further away than GPS noise can explain. ----------------------------
    val awayLimit = maxOf(35.0, 2.0 * fix.accuracyM)
    if (near.distanceM > awayLimit) {
      val since = awaySinceMs ?: now.also { awaySinceMs = it }
      againstSinceMs = null
      if (now - since >= offRouteAfterMs) {
        // Aim at a point a little further along the route than the nearest one, so the arrow
        // shows how to get back on, not just the shortest line to it.
        val target = pointAlong(local, near, 60.0)
        val bearing = bearingDeg(Enu(0.0, 0.0), target)
        val turn = if (course != null) angleDiffDeg(bearing, course) else 0.0
        standing = RouteStanding.OffRoute(hypot(near.point.x, near.point.y), turn)
      }
      return standing
    }
    awaySinceMs = null
    if (standing is RouteStanding.OffRoute) standing = RouteStanding.OnRoute

    // ---- On the route's road: which way along it? ---------------------------------------------
    if (!moving) return standing
    val diff = abs(angleDiffDeg(course!!, near.bearingDeg))
    when {
      diff > 135.0 -> {
        alignedSinceMs = null
        val since = againstSinceMs ?: now.also { againstSinceMs = it }
        if (standing !is RouteStanding.WrongWay && now - since >= wrongWayAfterMs) {
          standing = RouteStanding.WrongWay(now)
        }
      }
      diff < 60.0 -> {
        againstSinceMs = null
        if (standing is RouteStanding.WrongWay) {
          val since = alignedSinceMs ?: now.also { alignedSinceMs = it }
          if (now - since >= 1_000) standing = RouteStanding.OnRoute
        }
      }
      else -> {
        // Turning (e.g. half way through a U-turn): keep what we had.
        alignedSinceMs = null
      }
    }
    return standing
  }

  private fun pointAlong(route: List<Enu>, from: NearestOnRoute, metres: Double): Enu {
    var cur = from.point
    var left = metres
    var i = from.segment + 1
    while (i < route.size) {
      val next = route[i]
      val d = hypot(next.x - cur.x, next.y - cur.y)
      if (d >= left) {
        val t = left / d
        return Enu(cur.x + (next.x - cur.x) * t, cur.y + (next.y - cur.y) * t)
      }
      left -= d
      cur = next
      i++
    }
    return cur
  }
}
