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

  /**
   * The route simplified so no point of the road is more than [toleranceM] from the result
   * (Douglas-Peucker). For narrow corridors such as speed cameras, where even sampling would cut
   * corners.
   */
  fun simplify(toleranceM: Double): List<LatLng> {
    if (pts.size < 3) return pts
    val keep = BooleanArray(pts.size).also { it[0] = true; it[pts.size - 1] = true }
    val stack = ArrayDeque<Pair<Int, Int>>().apply { add(0 to pts.size - 1) }
    while (stack.isNotEmpty()) {
      val (a, b) = stack.removeLast()
      var worst = -1.0
      var idx = -1
      for (i in a + 1 until b) {
        val d = segDist(i, a, b)
        if (d > worst) {
          worst = d
          idx = i
        }
      }
      if (idx >= 0 && worst > toleranceM) {
        keep[idx] = true
        stack.add(a to idx)
        stack.add(idx to b)
      }
    }
    return pts.filterIndexed { i, _ -> keep[i] }
  }

  private fun segDist(i: Int, a: Int, b: Int): Double {
    val dx = xs[b] - xs[a]
    val dy = ys[b] - ys[a]
    val l2 = dx * dx + dy * dy
    val t = if (l2 == 0.0) 0.0 else (((xs[i] - xs[a]) * dx + (ys[i] - ys[a]) * dy) / l2).coerceIn(0.0, 1.0)
    return dist(xs[i], ys[i], xs[a] + t * dx, ys[a] + t * dy)
  }

  /**
   * Bounding boxes covering the route from [fromM] to [toM], one per [stepM] of road, each grown
   * by [padM]. A handful of small boxes is far cheaper for Overpass than one long `around` line.
   */
  fun boxes(fromM: Double, toM: Double, stepM: Double = 10_000.0, padM: Double = 1500.0): List<BBox> {
    val end = minOf(toM, lengthM)
    val out = ArrayList<BBox>()
    var start = fromM
    while (start < end) {
      val stop = minOf(start + stepM, end)
      var s = Double.MAX_VALUE; var w = Double.MAX_VALUE; var n = -Double.MAX_VALUE; var e = -Double.MAX_VALUE
      for (i in pts.indices) {
        val inside = cum[i] in start..stop ||
            (i > 0 && cum[i - 1] < start && cum[i] > start) || (i > 0 && cum[i - 1] < stop && cum[i] > stop)
        if (!inside) continue
        s = minOf(s, pts[i].lat); n = maxOf(n, pts[i].lat); w = minOf(w, pts[i].lon); e = maxOf(e, pts[i].lon)
      }
      if (s <= n) {
        val dLat = padM / 110_574.0
        val dLon = padM / mx
        out += BBox(s - dLat, w - dLon, n + dLat, e + dLon)
      }
      start = stop
    }
    return out
  }

  /** Bearing of the route (degrees from north) at [alongM]. */
  fun bearingAt(alongM: Double): Double {
    var i = cum.indexOfFirst { it >= alongM }.coerceIn(1, pts.size - 1)
    while (i < pts.size - 1 && cum[i] == cum[i - 1]) i++
    return (Math.toDegrees(kotlin.math.atan2(xs[i] - xs[i - 1], ys[i] - ys[i - 1])) + 360) % 360
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
