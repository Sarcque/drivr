package app.drivr.traffic

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/** A traffic event that lies on the route ahead. */
data class TrafficAhead(
    val event: TrafficEvent,
    /** Distance along the route from the car to where the event starts. */
    val distanceM: Double,
)

/**
 * Finds which events are on the route ahead, in order. An event counts when it lies within
 * [toleranceM] of the route and, where NDW gives a direction, runs the same way we drive (so a jam
 * on the other carriageway of the motorway doesn't trigger a warning).
 */
object RouteTraffic {

  fun ahead(
      route: List<LatLon>,
      events: List<TrafficEvent>,
      toleranceM: Double = 35.0,
  ): List<TrafficAhead> {
    if (route.size < 2) return emptyList()
    val origin = route.first()
    val pts = route.map { toXY(it, origin) }
    val cum = DoubleArray(pts.size)
    for (i in 1 until pts.size) cum[i] = cum[i - 1] + dist(pts[i - 1], pts[i])

    val out = ArrayList<TrafficAhead>()
    for (e in events) {
      val geom = e.geometry.map { toXY(it, origin) }
      val first = nearest(pts, cum, geom.first()) ?: continue
      if (first.offset > toleranceM) continue
      val sameDirection =
          if (geom.size >= 2) {
            // Line events: the end must also be on the route, further along.
            val last = nearest(pts, cum, geom.last()) ?: continue
            last.offset <= toleranceM && last.along > first.along
          } else {
            val b = e.bearingDeg
            b == null || angleDiff(b, first.routeBearing) < 60.0
          }
      if (sameDirection) out += TrafficAhead(e, first.along)
    }
    return out.sortedBy { it.distanceM }
  }

  private data class Hit(val along: Double, val offset: Double, val routeBearing: Double)

  private fun nearest(pts: List<DoubleArray>, cum: DoubleArray, p: DoubleArray): Hit? {
    var best: Hit? = null
    for (i in 0 until pts.size - 1) {
      val a = pts[i]
      val b = pts[i + 1]
      val dx = b[0] - a[0]
      val dy = b[1] - a[1]
      val len2 = dx * dx + dy * dy
      val t = if (len2 == 0.0) 0.0 else (((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / len2).coerceIn(0.0, 1.0)
      val off = hypot(a[0] + dx * t - p[0], a[1] + dy * t - p[1])
      if (best == null || off < best.offset) {
        val bearing = (Math.toDegrees(atan2(dx, dy)) + 360) % 360
        best = Hit(cum[i] + t * kotlin.math.sqrt(len2), off, bearing)
      }
    }
    return best
  }

  private fun angleDiff(a: Double, b: Double): Double {
    val d = abs(a - b) % 360
    return if (d > 180) 360 - d else d
  }

  private fun toXY(p: LatLon, o: LatLon): DoubleArray {
    val r = 6_371_008.8
    return doubleArrayOf(
        Math.toRadians(p.lon - o.lon) * r * cos(Math.toRadians(o.lat)),
        Math.toRadians(p.lat - o.lat) * r,
    )
  }

  private fun dist(a: DoubleArray, b: DoubleArray) = hypot(a[0] - b[0], a[1] - b[1])
}
