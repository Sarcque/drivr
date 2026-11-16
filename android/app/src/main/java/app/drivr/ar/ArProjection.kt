package app.drivr.ar

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A point in local metres around the car: x = east, y = north, z = up (ground is z = 0). */
data class Enu(val x: Double, val y: Double, val z: Double = 0.0) {
  operator fun plus(o: Enu) = Enu(x + o.x, y + o.y, z + o.z)

  operator fun minus(o: Enu) = Enu(x - o.x, y - o.y, z - o.z)

  operator fun times(s: Double) = Enu(x * s, y * s, z * s)

  fun length() = sqrt(x * x + y * y + z * z)
}

data class LatLng(val lat: Double, val lng: Double)

data class ScreenPoint(val x: Float, val y: Float, val depth: Double)

/**
 * Where the phone's camera is and where it looks.
 *
 * @param headingDeg compass bearing the camera faces (we use the car's course over ground, which is
 *   far steadier in a car than the magnetometer).
 * @param pitchDownDeg how far the camera looks below the horizon.
 * @param rollDeg rotation of the image around the view axis (phone not mounted level).
 * @param heightM camera height above the road. A dashboard mount is about 1.2 m.
 */
data class CameraPose(
    val headingDeg: Double,
    val pitchDownDeg: Double,
    val rollDeg: Double = 0.0,
    val heightM: Double = 1.2,
)

/**
 * Pinhole projection from the road around the car onto the camera image. No ARCore: pose comes
 * from GPS course plus the gravity sensor, which is enough to put a route ribbon and signs in the
 * right place at junction scale (a few metres), not to paint an exact lane.
 */
class ArProjector(
    private val pose: CameraPose,
    private val viewWidth: Float,
    private val viewHeight: Float,
    /** Field of view across the long side of the image. Typical phone main camera: 65-75°. */
    longSideFovDeg: Double = 68.0,
) {
  private val focalPx: Double =
      (maxOf(viewWidth, viewHeight) / 2.0) / tan(Math.toRadians(longSideFovDeg) / 2.0)

  private val forward: Enu
  private val right: Enu
  private val up: Enu

  init {
    val yaw = Math.toRadians(pose.headingDeg)
    val pitch = Math.toRadians(pose.pitchDownDeg)
    val roll = Math.toRadians(pose.rollDeg)
    val fh = Enu(sin(yaw), cos(yaw), 0.0)
    val rh = Enu(cos(yaw), -sin(yaw), 0.0)
    val worldUp = Enu(0.0, 0.0, 1.0)
    val f = fh * cos(pitch) + worldUp * -sin(pitch)
    val u0 = fh * sin(pitch) + worldUp * cos(pitch)
    // Roll the image: rotate right/up around the forward axis.
    right = rh * cos(roll) + u0 * -sin(roll)
    up = rh * sin(roll) + u0 * cos(roll)
    forward = f
  }

  /** Projects a point (relative to the spot on the road under the camera). Null if behind. */
  fun project(p: Enu, nearPlaneM: Double = 1.5): ScreenPoint? {
    val rel = Enu(p.x, p.y, p.z - pose.heightM)
    val depth = dot(rel, forward)
    if (depth < nearPlaneM) return null
    val sx = viewWidth / 2.0 + focalPx * dot(rel, right) / depth
    val sy = viewHeight / 2.0 - focalPx * dot(rel, up) / depth
    return ScreenPoint(sx.toFloat(), sy.toFloat(), depth)
  }

  /** Pixels per metre at a given depth, for sizing things that sit at that distance. */
  fun pixelsPerMetreAt(depth: Double): Float = (focalPx / depth).toFloat()

  private fun dot(a: Enu, b: Enu) = a.x * b.x + a.y * b.y + a.z * b.z
}

private const val EARTH_RADIUS_M = 6_371_008.8

/** Local flat-earth conversion; exact enough within a few kilometres. */
fun LatLng.toEnu(origin: LatLng): Enu {
  val dLat = Math.toRadians(lat - origin.lat)
  val dLng = Math.toRadians(lng - origin.lng)
  val x = dLng * EARTH_RADIUS_M * cos(Math.toRadians(origin.lat))
  val y = dLat * EARTH_RADIUS_M
  return Enu(x, y)
}

/** Compass bearing (0 = north, clockwise) of the ground direction a -> b. */
fun bearingDeg(a: Enu, b: Enu): Double {
  val deg = Math.toDegrees(atan2(b.x - a.x, b.y - a.y))
  return (deg + 360.0) % 360.0
}

/**
 * The route ahead of the car as a dense polyline in local metres, starting at the car's projection
 * on the route and running [aheadM] metres forward.
 */
fun routeAhead(route: List<Enu>, aheadM: Double = 300.0, stepM: Double = 2.0): List<Enu> {
  if (route.size < 2) return route
  // Closest point on the route to the car (origin).
  var bestSeg = 0
  var bestT = 0.0
  var bestD = Double.MAX_VALUE
  for (i in 0 until route.size - 1) {
    val a = route[i]
    val b = route[i + 1]
    val ab = b - a
    val len2 = ab.x * ab.x + ab.y * ab.y
    val t = if (len2 == 0.0) 0.0 else ((-a.x) * ab.x + (-a.y) * ab.y) / len2
    val tc = t.coerceIn(0.0, 1.0)
    val p = a + ab * tc
    val d = hypot(p.x, p.y)
    if (d < bestD) {
      bestD = d
      bestSeg = i
      bestT = tc
    }
  }
  val out = ArrayList<Enu>()
  var cur = route[bestSeg] + (route[bestSeg + 1] - route[bestSeg]) * bestT
  out += cur
  var remaining = aheadM
  var i = bestSeg + 1
  var carry = 0.0
  while (i < route.size && remaining > 0) {
    val next = route[i]
    val seg = next - cur
    val len = hypot(seg.x, seg.y)
    if (len < 1e-6) {
      i++
      continue
    }
    var d = stepM - carry
    while (d <= len && remaining > 0) {
      out += cur + seg * (d / len)
      remaining -= stepM
      d += stepM
    }
    carry = len - (d - stepM)
    cur = next
    i++
    if (i == route.size) out += next
  }
  return out
}

/** Left and right edges of a ribbon of [halfWidthM] around a polyline, for drawing on the road. */
fun ribbonEdges(line: List<Enu>, halfWidthM: Double): Pair<List<Enu>, List<Enu>> {
  val left = ArrayList<Enu>(line.size)
  val right = ArrayList<Enu>(line.size)
  for (i in line.indices) {
    val a = line[maxOf(0, i - 1)]
    val b = line[minOf(line.lastIndex, i + 1)]
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len = hypot(dx, dy).takeIf { it > 1e-6 } ?: 1.0
    // Perpendicular, pointing left of travel.
    val nx = -dy / len
    val ny = dx / len
    left += Enu(line[i].x + nx * halfWidthM, line[i].y + ny * halfWidthM)
    right += Enu(line[i].x - nx * halfWidthM, line[i].y - ny * halfWidthM)
  }
  return left to right
}

fun normalizeDeg(d: Double): Double = ((d % 360.0) + 360.0) % 360.0
