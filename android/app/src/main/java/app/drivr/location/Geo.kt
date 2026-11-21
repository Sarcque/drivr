package app.drivr.location

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

/** One position fix, raw or cleaned up. Plain Kotlin so it can be tested without Android. */
data class Fix(
    val lat: Double,
    val lon: Double,
    /** Horizontal accuracy (68 %), metres. */
    val accuracyM: Double,
    /** Ground speed, m/s; null when the receiver didn't say. */
    val speedMps: Double?,
    /** Course over ground, degrees from north; null when unknown. */
    val bearingDeg: Double?,
    /** When the fix was taken, on a clock that never jumps (elapsedRealtime), ms. */
    val timeMs: Long,
    /** True for positions Drivr worked out itself (tunnel), not measured. */
    val predicted: Boolean = false,
)

internal const val M_PER_DEG = 111_320.0

/** Flat-earth metres east/north of a reference point. Good to well under a metre over 50 km. */
internal class LocalFrame(val lat0: Double, val lon0: Double) {
  private val kx = M_PER_DEG * cos(Math.toRadians(lat0))

  fun x(lon: Double) = (lon - lon0) * kx
  fun y(lat: Double) = (lat - lat0) * M_PER_DEG
  fun lon(x: Double) = lon0 + x / kx
  fun lat(y: Double) = lat0 + y / M_PER_DEG
}

internal fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
  val kx = M_PER_DEG * cos(Math.toRadians((lat1 + lat2) / 2))
  val dx = (lon2 - lon1) * kx
  val dy = (lat2 - lat1) * M_PER_DEG
  return sqrt(dx * dx + dy * dy)
}

/** Compass bearing of an east/north vector, 0..360. */
internal fun bearingOf(dx: Double, dy: Double): Double = (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0

/** Signed shortest turn from [from] to [to], -180..180. */
internal fun angleDelta(from: Double, to: Double): Double {
  var d = (to - from) % 360.0
  if (d > 180) d -= 360.0
  if (d < -180) d += 360.0
  return d
}

internal fun normDeg(d: Double): Double = ((d % 360.0) + 360.0) % 360.0

/**
 * A route as a line with distances along it, for "how far along am I" and "where is X metres on".
 * Coordinates are (lat, lon) pairs.
 */
class RouteTrack(points: List<Pair<Double, Double>>) {
  private val frame = LocalFrame(points.first().first, points.first().second)
  private val xs = DoubleArray(points.size) { frame.x(points[it].second) }
  private val ys = DoubleArray(points.size) { frame.y(points[it].first) }
  private val cum = DoubleArray(points.size)
  val lengthM: Double

  init {
    for (i in 1 until points.size) {
      cum[i] = cum[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
    }
    lengthM = cum.last()
  }

  val size get() = xs.size

  data class Projection(val progressM: Double, val distanceM: Double)

  /**
   * The point of the route nearest to (lat, lon). With [near] it first looks within [-back, +ahead]
   * metres of that progress, so a route that passes the same spot twice (a loop, a flyover over
   * itself) doesn't make the car jump to the other pass.
   */
  fun project(lat: Double, lon: Double, near: Double? = null, back: Double = 150.0, ahead: Double = 3000.0): Projection {
    val px = frame.x(lon)
    val py = frame.y(lat)
    if (near != null) {
      val windowed = projectWithin(px, py, near - back, near + ahead)
      if (windowed != null && windowed.distanceM < 60.0) return windowed
    }
    return projectWithin(px, py, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)!!
  }

  private fun projectWithin(px: Double, py: Double, from: Double, to: Double): Projection? {
    var best: Projection? = null
    for (i in 0 until xs.size - 1) {
      if (cum[i + 1] < from || cum[i] > to) continue
      val dx = xs[i + 1] - xs[i]
      val dy = ys[i + 1] - ys[i]
      val l2 = dx * dx + dy * dy
      val t = if (l2 == 0.0) 0.0 else (((px - xs[i]) * dx + (py - ys[i]) * dy) / l2).coerceIn(0.0, 1.0)
      val ex = xs[i] + t * dx - px
      val ey = ys[i] + t * dy - py
      val d = sqrt(ex * ex + ey * ey)
      if (best == null || d < best.distanceM) best = Projection(cum[i] + t * sqrt(l2), d)
    }
    return best
  }

  private fun segmentAt(p: Double): Int {
    // Binary search: last i with cum[i] <= p.
    var lo = 0
    var hi = xs.size - 2
    while (lo < hi) {
      val mid = (lo + hi + 1) / 2
      if (cum[mid] <= p) lo = mid else hi = mid - 1
    }
    return lo.coerceIn(0, xs.size - 2)
  }

  /** (lat, lon) at [progressM] along the route. */
  fun positionAt(progressM: Double): Pair<Double, Double> {
    val p = progressM.coerceIn(0.0, lengthM)
    val i = segmentAt(p)
    val seg = cum[i + 1] - cum[i]
    val t = if (seg == 0.0) 0.0 else (p - cum[i]) / seg
    return frame.lat(ys[i] + (ys[i + 1] - ys[i]) * t) to frame.lon(xs[i] + (xs[i + 1] - xs[i]) * t)
  }

  /** Direction of the road at [progressM], looking a little behind and ahead so corners turn smoothly. */
  fun bearingAt(progressM: Double, back: Double = 5.0, ahead: Double = 15.0): Double {
    val a = (progressM - back).coerceIn(0.0, lengthM)
    val b = (progressM + ahead).coerceIn(0.0, lengthM)
    if (b - a < 0.5) {
      val i = segmentAt(progressM)
      return bearingOf(xs[i + 1] - xs[i], ys[i + 1] - ys[i])
    }
    val (la, oa) = positionAt(a)
    val (lb, ob) = positionAt(b)
    return bearingOf(frame.x(ob) - frame.x(oa), frame.y(lb) - frame.y(la))
  }

  private fun hypot(a: Double, b: Double) = sqrt(a * a + b * b)
}
