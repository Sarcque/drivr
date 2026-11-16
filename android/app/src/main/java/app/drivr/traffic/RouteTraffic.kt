package app.drivr.traffic

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

/** A traffic event that lies on the route ahead (or that we are driving through). */
data class TrafficAhead(
    val event: TrafficEvent,
    /** Distance along the route from the car to where the event starts; 0 if we're already in it. */
    val distanceM: Double,
    /** For jams and closures: distance along the route to where it ends. */
    val endDistanceM: Double? = null,
    /** For jams: total length of the jam along the road. */
    val lengthM: Double? = null,
) {
  /** True when the car is inside this jam: it started behind us and ends ahead. */
  val isInside
    get() = distanceM <= 0.0 && endDistanceM != null

  /** 0..1, how far through the jam we are. */
  val progress: Float
    get() {
      val len = lengthM ?: return 0f
      val left = endDistanceM ?: return 0f
      if (len <= 0) return 0f
      return (1 - left / len).toFloat().coerceIn(0f, 1f)
    }

  /**
   * Rough time to get through the rest of the jam: the reported delay spread over its length plus
   * free-flow time. NDW gives the delay, not the speed in the queue, so this is an estimate.
   */
  val secondsLeft: Double?
    get() {
      val len = lengthM ?: return null
      val left = endDistanceM ?: return null
      val delay = event.delaySeconds ?: return null
      if (len <= 0) return null
      return (left / len) * delay + left / FREE_FLOW_MPS
    }

  companion object {
    private const val FREE_FLOW_MPS = 27.8 // 100 km/h
  }
}

/** A route in local metres, for asking "where along the route is this point?". */
class RouteIndex(route: List<LatLon>) {
  private val origin = route.firstOrNull() ?: LatLon(0.0, 0.0)
  private val pts = route.map { toXY(it) }
  private val cum = DoubleArray(pts.size).also {
    for (i in 1 until pts.size) it[i] = it[i - 1] + dist(pts[i - 1], pts[i])
  }
  val isUsable = pts.size >= 2
  val lengthM: Double
    get() = cum.lastOrNull() ?: 0.0

  data class Hit(val along: Double, val offset: Double, val routeBearing: Double)

  fun locate(p: LatLon): Hit? {
    if (!isUsable) return null
    val q = toXY(p)
    var best: Hit? = null
    for (i in 0 until pts.size - 1) {
      val a = pts[i]
      val b = pts[i + 1]
      val dx = b[0] - a[0]
      val dy = b[1] - a[1]
      val len2 = dx * dx + dy * dy
      val t = if (len2 == 0.0) 0.0 else (((q[0] - a[0]) * dx + (q[1] - a[1]) * dy) / len2).coerceIn(0.0, 1.0)
      val off = hypot(a[0] + dx * t - q[0], a[1] + dy * t - q[1])
      if (best == null || off < best.offset) {
        best = Hit(cum[i] + t * sqrt(len2), off, (Math.toDegrees(atan2(dx, dy)) + 360) % 360)
      }
    }
    return best
  }

  /** Distance from the car (route start) to the nearest point of a polyline. */
  fun distanceFromStartTo(line: List<LatLon>): Double {
    val c = doubleArrayOf(0.0, 0.0)
    val xs = line.map { toXY(it) }
    if (xs.size == 1) return dist(c, xs[0])
    var best = Double.MAX_VALUE
    for (i in 0 until xs.size - 1) {
      val a = xs[i]
      val b = xs[i + 1]
      val dx = b[0] - a[0]
      val dy = b[1] - a[1]
      val len2 = dx * dx + dy * dy
      val t = if (len2 == 0.0) 0.0 else (((c[0] - a[0]) * dx + (c[1] - a[1]) * dy) / len2).coerceIn(0.0, 1.0)
      best = minOf(best, hypot(a[0] + dx * t, a[1] + dy * t))
    }
    return best
  }

  fun polylineLength(line: List<LatLon>): Double {
    val xs = line.map { toXY(it) }
    return (1 until xs.size).sumOf { dist(xs[it - 1], xs[it]) }
  }

  private fun toXY(p: LatLon): DoubleArray {
    val r = 6_371_008.8
    return doubleArrayOf(
        Math.toRadians(p.lon - origin.lon) * r * cos(Math.toRadians(origin.lat)),
        Math.toRadians(p.lat - origin.lat) * r,
    )
  }

  private fun dist(a: DoubleArray, b: DoubleArray) = hypot(a[0] - b[0], a[1] - b[1])
}

internal fun angleDiff(a: Double, b: Double): Double {
  val d = abs(a - b) % 360
  return if (d > 180) 360 - d else d
}

/**
 * Finds which events are on the route ahead, in order. An event counts when it lies within
 * [toleranceM] of the route and, where NDW gives a direction, runs the same way we drive (so a jam
 * on the other carriageway of the motorway doesn't trigger a warning). A jam that started behind
 * us and ends ahead is returned with distance 0: we're in it.
 */
object RouteTraffic {

  fun ahead(
      route: List<LatLon>,
      events: List<TrafficEvent>,
      toleranceM: Double = 35.0,
  ): List<TrafficAhead> {
    val index = RouteIndex(route)
    if (!index.isUsable) return emptyList()
    val out = ArrayList<TrafficAhead>()
    for (e in events) {
      if (e.isLine) {
        val last = index.locate(e.geometry.last()) ?: continue
        if (last.offset > toleranceM) continue
        val first = index.locate(e.geometry.first()) ?: continue
        val length = e.queueLengthM?.takeIf { it > 0 } ?: index.polylineLength(e.geometry)
        when {
          first.offset <= toleranceM && last.along > first.along ->
              out += TrafficAhead(e, first.along, last.along, length)
          // Start is behind us; we're inside if the car is on the jam's line.
          first.offset > toleranceM && index.distanceFromStartTo(e.geometry) <= toleranceM ->
              out += TrafficAhead(e, 0.0, last.along, maxOf(length, last.along))
        }
      } else {
        val hit = index.locate(e.geometry.first()) ?: continue
        if (hit.offset > toleranceM) continue
        val b = e.bearingDeg
        if (b == null || angleDiff(b, hit.routeBearing) < 60.0) out += TrafficAhead(e, hit.along)
      }
    }
    return out.sortedBy { it.distanceM }
  }
}
