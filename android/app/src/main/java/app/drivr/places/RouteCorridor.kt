package app.drivr.places

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

data class LatLng(val lat: Double, val lon: Double)

/** Where a place sits relative to the route: how far along it, and how far off it. */
data class RoutePosition(val alongM: Double, val offsetM: Double)

/**
 * Geometry for "search along route": project places onto the remaining route, and thin the route
 * out to a polyline short enough to send to Overpass (which accepts `around` with a line).
 *
 * Flat-earth maths in a local metric frame: errors are well under 1% over a few hundred km,
 * which is plenty for ranking stops.
 */
class RouteCorridor(route: List<LatLng>) {
  private val pts = route
  private val refLat = route.firstOrNull()?.lat ?: 0.0
  private val mx = 111_320.0 * cos(refLat * PI / 180)
  private val my = 110_574.0
  private val xs = DoubleArray(pts.size) { pts[it].lon * mx }
  private val ys = DoubleArray(pts.size) { pts[it].lat * my }
  private val cum =
      DoubleArray(pts.size).also { c ->
        for (i in 1 until pts.size) c[i] = c[i - 1] + dist(xs[i - 1], ys[i - 1], xs[i], ys[i])
      }

  val lengthM: Double get() = cum.lastOrNull() ?: 0.0

  fun position(p: LatLng): RoutePosition {
    if (pts.size < 2) return RoutePosition(0.0, Double.MAX_VALUE)
    val px = p.lon * mx
    val py = p.lat * my
    var best = Double.MAX_VALUE
    var along = 0.0
    for (i in 0 until pts.size - 1) {
      val dx = xs[i + 1] - xs[i]
      val dy = ys[i + 1] - ys[i]
      val l2 = dx * dx + dy * dy
      val t = if (l2 == 0.0) 0.0 else (((px - xs[i]) * dx + (py - ys[i]) * dy) / l2).coerceIn(0.0, 1.0)
      val d = dist(px, py, xs[i] + t * dx, ys[i] + t * dy)
      if (d < best) {
        best = d
        along = cum[i] + t * sqrt(l2)
      }
    }
    return RoutePosition(along, best)
  }

  /**
   * The first [maxLengthM] of the route as at most [maxPoints] points, roughly evenly spaced, for
   * an Overpass `around` query. A corridor of radius r around this line covers the road.
   */
  fun sample(maxLengthM: Double, maxPoints: Int = 120): List<LatLng> {
    if (pts.isEmpty()) return emptyList()
    val len = minOf(maxLengthM, lengthM)
    val step = maxOf(len / (maxPoints - 1), 50.0)
    val out = mutableListOf(pts[0])
    var next = step
    for (i in 1 until pts.size) {
      if (cum[i] > len) break
      if (cum[i] >= next) {
        out += pts[i]
        next = cum[i] + step
      }
    }
    return out
  }

  companion object {
    private fun dist(x1: Double, y1: Double, x2: Double, y2: Double): Double {
      val dx = x2 - x1
      val dy = y2 - y1
      return sqrt(dx * dx + dy * dy)
    }

    /** Straight-line distance in metres. */
    fun distanceM(a: LatLng, b: LatLng): Double {
      val mx = 111_320.0 * cos((a.lat + b.lat) / 2 * PI / 180)
      return dist(a.lon * mx, a.lat * 110_574.0, b.lon * mx, b.lat * 110_574.0)
    }

    /**
     * Rough extra time for a stop [offsetM] off the route: there and back on local roads at
     * ~35 km/h, plus a minute for slowing down and pulling in. Replaced by Valhalla's real
     * number when the driver opens the place.
     */
    fun estimateDetourS(offsetM: Double): Double = 2 * offsetM / 9.7 + 60
  }
}
