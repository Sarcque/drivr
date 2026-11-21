package app.drivr.ar

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/**
 * A synthetic but physically consistent drive for testing the whole AR pipeline end to end:
 * a two-lane town road (3.2 m lanes, dashed centre line, solid edge lines, kerbs, pavements)
 * that runs straight, bends right through a 160 m radius curve, runs straight again and has a
 * side street to the right where the route turns.
 *
 * The world is "the truth". The app only gets what a phone would get: an OSM-style route
 * (centre line, a vertex every 15-30 m, each off by up to half a metre), GPS fixes once a second
 * that are 0.6 s old on arrival and snapped to that route, a gyro with bias and noise, a gravity
 * sensor fooled by braking and accelerating, and camera frames of the truth (with optional rain
 * on the windscreen, the low sun in the picture and a car ahead).
 */
class DriveWorld(seed: Int = 7) {
  val laneW = 3.2
  /** The car drives in the right-hand lane. */
  val carLat = laneW / 2

  // Road pieces. s = metres along the road's centre line, lat = metres to the right of it.
  private val r = 160.0
  private val arcTheta = Math.toRadians(30.0)
  private val s0End = 250.0
  private val arcEnd = s0End + r * arcTheta
  private val centre = Enu(r, 200.0)
  private val arcExit = Enu(r - r * cos(arcTheta), 200.0 + r * sin(arcTheta))
  private val d2 = Enu(sin(arcTheta), cos(arcTheta))
  private val n2 = Enu(cos(arcTheta), -sin(arcTheta))
  /** Where the side street joins, metres along the road. */
  val junctionS = arcEnd + 120.0
  private val junction = arcExit + d2 * (junctionS - arcEnd)
  private val dSide = n2
  private val nSide = Enu(-d2.x, -d2.y)

  /** Point on the centre line at [s], and the road's direction there (unit). */
  fun road(s: Double): Pair<Enu, Enu> =
      when {
        s <= s0End -> Enu(0.0, s - 50.0) to Enu(0.0, 1.0)
        s <= arcEnd -> {
          val th = (s - s0End) / r
          Enu(centre.x - r * cos(th), centre.y + r * sin(th)) to Enu(sin(th), cos(th))
        }
        else -> arcExit + d2 * (s - arcEnd) to d2
      }

  /** (s, lat) of a ground point on the main road. */
  fun toRoad(p: Enu): Pair<Double, Double> {
    var best = Double.MAX_VALUE to 0.0
    fun take(s: Double, lat: Double) {
      if (abs(lat) < abs(best.second) || best.first == Double.MAX_VALUE) best = s to lat
    }
    if (p.y <= 200.0) take(p.y + 50.0, p.x)
    run {
      val vx = p.x - centre.x
      val vy = p.y - centre.y
      val th = PI - atan2(vy, vx)
      if (th in 0.0..arcTheta) take(s0End + r * th, r - hypot(vx, vy))
    }
    run {
      val v = p - arcExit
      val along = v.x * d2.x + v.y * d2.y
      if (along >= 0) take(arcEnd + along, v.x * n2.x + v.y * n2.y)
    }
    return best
  }

  /** (s, lat) on the side street (s from the main road's centre line). */
  private fun toSide(p: Enu): Pair<Double, Double> {
    val v = p - junction
    return (v.x * dSide.x + v.y * dSide.y) to (v.x * nSide.x + v.y * nSide.y)
  }

  /** The car's lane centre at [s] and the direction. */
  fun lane(s: Double): Pair<Enu, Enu> {
    val (c, d) = road(s)
    return c + Enu(d.y, -d.x) * carLat to d
  }

  /** What OSM would have: the centre line, coarse and a little off, then the side street. */
  val osmRoute: List<Enu> = run {
    val rnd = Random(seed)
    val pts = ArrayList<Enu>()
    var s = 0.0
    while (s < junctionS) {
      val (c, d) = road(s)
      val off = rnd.nextDouble(-0.5, 0.5)
      pts += c + Enu(d.y, -d.x) * off
      s += if (s in s0End..arcEnd) 15.0 else 25.0 + rnd.nextDouble(0.0, 8.0)
    }
    pts += junction
    for (k in 1..8) pts += junction + dSide * (k * 25.0)
    pts
  }

  // ---- Rendering --------------------------------------------------------------------------

  data class Weather(val rain: Boolean = false, val sun: Boolean = false, val carAhead: Boolean = false)

  /** Colour tint (r, g, b factors on the luma) of the last sample, for the colour pictures. */
  private var tint = GREY

  /** Luma (0..255) of the ground at a world point. */
  private fun groundLuma(p: Enu): Double {
    val l = groundLuma0(p)
    return l
  }

  private fun groundLuma0(p: Enu): Double {
    val tex = hash(floor5(p.x), floor5(p.y)) * 10.0 - 5.0
    val (s, lat) = toRoad(p)
    val (ss, sl) = toSide(p)
    val onSide = ss > 3.0 && abs(sl) < 3.0 && ss < 300.0
    val a = abs(lat)
    val nearJunction = abs(s - junctionS) < 4.5 && lat > 0
    return when {
      a < 3.45 -> {
        when {
          a < 0.05 && ((s / 3.0).toInt() % 2 == 0) && !nearJunction -> { tint = GREY; 205.0 } // dashed centre line
          abs(a - 3.3) < 0.06 && !(nearJunction && lat > 0) -> { tint = GREY; 205.0 } // solid edge lines
          else -> { tint = ASPHALT; 78.0 + tex }
        }
      }
      onSide -> { tint = ASPHALT; 80.0 + tex }
      a < 3.6 -> { tint = GREY; 165.0 } // kerb
      a < 5.8 -> { tint = PAVEMENT; 132.0 + tex * 0.6 } // pavement
      else -> { tint = GRASS; 92.0 + tex * 1.5 } // grass
    }
  }

  private fun floor5(v: Double) = kotlin.math.floor(v * 5.0).toInt()

  private fun hash(a: Int, b: Int): Double {
    var h = a * 374761393 + b * 668265263
    h = (h xor (h ushr 13)) * 1274126177
    return ((h xor (h ushr 16)) and 0xFFFF) / 65535.0
  }

  /** Renders what the camera sees, [w] x [h] upright, with the app's pinhole model. */
  fun render(
      cam: Enu,
      pose: CameraPose,
      w: Int,
      h: Int,
      fovDeg: Double,
      weather: Weather,
      seed: Int,
      carHeadingDeg: Double,
      rgbOut: IntArray? = null,
  ): LumaImage {
    val f = (max(w, h) / 2.0) / tan(Math.toRadians(fovDeg) / 2.0)
    val (fwd, rgt, up) = basis(pose)
    val px = FloatArray(w * h)
    val rnd = Random(seed)
    val ch = Math.toRadians(carHeadingDeg)
    val cf = Enu(sin(ch), cos(ch))
    val cr = Enu(cos(ch), -sin(ch))
    // A real lens doesn't alias: 2x2 samples per pixel.
    fun sample(uu: Double, vv: Double, u: Int, v: Int): Double {
      val a = (uu - w / 2.0) / f
      val b = (h / 2.0 - vv) / f
      val dx = fwd.x + a * rgt.x + b * up.x
      val dy = fwd.y + a * rgt.y + b * up.y
      val dz = fwd.z + a * rgt.z + b * up.z
      val n = sqrt(dx * dx + dy * dy + dz * dz)
      var lum: Double
      // The dashboard: everything more than 12° below level.
      if (dz / n < -sin(Math.toRadians(12.0))) {
        lum = 26.0 + 4.0 * hash(u / 3, v / 3)
        tint = GREY
      } else if (dz >= -1e-4) {
        lum = 212.0 - 50.0 * (v.toDouble() / h)
        tint = SKY
        // Trees and houses along the horizon.
        val elev = dz / n
        val az = atan2(dx, dy)
        val skyline = 0.025 + 0.02 * hash((az * 60).toInt(), 3) + 0.03 * (if (hash((az * 15).toInt(), 9) > 0.6) 1.0 else 0.0)
        if (elev < skyline) {
          lum = 70.0 + 25.0 * hash((az * 400).toInt(), (elev * 400).toInt())
          tint = TREES
        }
      } else {
        val t = pose.heightM / -dz
        val g = Enu(cam.x + dx * t, cam.y + dy * t)
        lum = groundLuma(g)
        // A little haze with distance.
        lum += (175.0 - lum) * (1.0 - exp(-t / 400.0))
        if (weather.carAhead) {
          // A car 20 m ahead in our lane: its back is a plane across the lane.
          val along = dx * cf.x + dy * cf.y
          if (along > 0) {
            val tt = 20.0 / along
            val side = (dx * cr.x + dy * cr.y) * tt
            val z = pose.heightM + dz * tt
            if (tt < t && side in 0.75..2.45 && z in 0.25..1.5) {
              lum = 48.0
              tint = CAR
              if (z in 0.85..1.05 && (side < 1.0 || side > 2.2)) { lum = 170.0; tint = LIGHTS } // rear lights
              if (z in 1.05..1.45 && side in 0.95..2.25) { lum = 30.0; tint = GREY } // rear window
            }
          }
        }
      }
      return lum
    }
    val tr = FloatArray(if (rgbOut != null) w * h else 0)
    val tg = FloatArray(tr.size)
    val tb = FloatArray(tr.size)
    for (v in 0 until h) for (u in 0 until w) {
      var acc = 0.0
      var r = 0.0
      var gg = 0.0
      var b = 0.0
      for (sy in 0..1) for (sx in 0..1) {
        val l = sample(u + 0.25 + 0.5 * sx, v + 0.25 + 0.5 * sy, u, v)
        acc += l
        if (rgbOut != null) {
          r += tint[0] * l
          gg += tint[1] * l
          b += tint[2] * l
        }
      }
      px[v * w + u] = (acc / 4).toFloat()
      if (rgbOut != null) {
        val i = v * w + u
        val l = acc.coerceAtLeast(1e-3)
        tr[i] = (r / l).toFloat()
        tg[i] = (gg / l).toFloat()
        tb[i] = (b / l).toFloat()
      }
    }
    if (weather.sun) {
      // Low sun just above the road, left of centre, plus veiling glare over the whole picture.
      val sx = 0.32 * w
      val sy = 0.28 * h
      val rr = 0.09 * w
      for (v in 0 until h) for (u in 0 until w) {
        val d = hypot(u - sx, v - sy)
        val add = 300.0 * exp(-d * d / (2 * rr * rr)) + 45.0 * exp(-d / (0.6 * w))
        val i = v * w + u
        px[i] = (px[i] + add.toFloat()) * 0.85f + 20f
      }
    }
    if (weather.rain) {
      // Drops on the windscreen: small lenses that blur and brighten what's behind them.
      val src = px.copyOf()
      repeat(160) {
        val cx = rnd.nextInt(w)
        val cy = rnd.nextInt(h)
        val rad = rnd.nextInt(2, 8)
        for (v in max(0, cy - rad) until min(h, cy + rad)) for (u in max(0, cx - rad) until min(w, cx + rad)) {
          val d = hypot((u - cx).toDouble(), (v - cy).toDouble())
          if (d > rad) continue
          // Refraction: an upside-down, shrunk view from around the drop.
          val su = (cx - (u - cx) * 2).coerceIn(0, w - 1)
          val sv = (cy - (v - cy) * 2).coerceIn(0, h - 1)
          val rim = if (d > rad - 1.2) 35f else 10f
          px[v * w + u] = src[sv * w + su] * 0.8f + rim + 20f
        }
      }
      // A film of water: everything a bit soft.
      blurInPlace(px, w, h)
    }
    for (i in px.indices) px[i] = (px[i] + gauss(rnd) * 3f).coerceIn(0f, 255f)
    if (rgbOut != null) {
      // Colour from the material tint, over the luma after glare, rain and noise.
      for (i in px.indices) {
        val l = px[i]
        // Glare washes colour out.
        val wash = ((l - 200f) / 55f).coerceIn(0f, 1f)
        fun ch(t: Float) = (l * (t + (1f - t) * wash)).coerceIn(0f, 255f).toInt()
        rgbOut[i] = (0xFF shl 24) or (ch(tr[i]) shl 16) or (ch(tg[i]) shl 8) or ch(tb[i])
      }
    }
    return LumaImage(w, h, px)
  }

  private fun blurInPlace(px: FloatArray, w: Int, h: Int) {
    val t = px.copyOf()
    for (v in 1 until h - 1) for (u in 1 until w - 1) {
      val i = v * w + u
      px[i] = (t[i] * 4 + t[i - 1] + t[i + 1] + t[i - w] + t[i + w]) / 8f
    }
  }

  private fun gauss(r: Random): Float {
    val u1 = r.nextDouble().coerceAtLeast(1e-9)
    val u2 = r.nextDouble()
    return (sqrt(-2.0 * kotlin.math.ln(u1)) * cos(2 * PI * u2)).toFloat()
  }
}

private val GREY = floatArrayOf(1f, 1f, 1f)
private val ASPHALT = floatArrayOf(0.97f, 0.99f, 1.05f)
private val PAVEMENT = floatArrayOf(1.06f, 1.0f, 0.92f)
private val GRASS = floatArrayOf(0.72f, 1.12f, 0.55f)
private val SKY = floatArrayOf(0.78f, 0.9f, 1.15f)
private val TREES = floatArrayOf(0.7f, 1.0f, 0.62f)
private val CAR = floatArrayOf(1.35f, 0.55f, 0.5f)
private val LIGHTS = floatArrayOf(1.5f, 0.35f, 0.3f)

/** Camera axes, exactly as [ArProjector] builds them. */
fun basis(pose: CameraPose): Triple<Enu, Enu, Enu> {
  val yaw = Math.toRadians(pose.headingDeg)
  val pitch = Math.toRadians(pose.pitchDownDeg)
  val roll = Math.toRadians(pose.rollDeg)
  val fh = Enu(sin(yaw), cos(yaw), 0.0)
  val rh = Enu(cos(yaw), -sin(yaw), 0.0)
  val worldUp = Enu(0.0, 0.0, 1.0)
  val f = fh * cos(pitch) + worldUp * -sin(pitch)
  val u0 = fh * sin(pitch) + worldUp * cos(pitch)
  val right = rh * cos(roll) + u0 * -sin(roll)
  val up = rh * sin(roll) + u0 * cos(roll)
  return Triple(f, right, up)
}

/** Where on the road (world, relative to the camera's ground point) a screen pixel lands. */
fun backProject(pose: CameraPose, w: Float, h: Float, fovDeg: Double, sx: Float, sy: Float): Enu? {
  val f = (max(w, h) / 2.0) / tan(Math.toRadians(fovDeg) / 2.0)
  val (fwd, rgt, up) = basis(pose)
  val a = (sx - w / 2.0) / f
  val b = (h / 2.0 - sy) / f
  val dz = fwd.z + a * rgt.z + b * up.z
  if (dz >= -1e-6) return null
  val t = pose.heightM / -dz
  return Enu((fwd.x + a * rgt.x + b * up.x) * t, (fwd.y + a * rgt.y + b * up.y) * t)
}

/** One tick's worth of truth: where the car is, how it points, how fast it goes. */
data class CarState(val t: Double, val s: Double, val speed: Double, val accel: Double, val headingDeg: Double, val yawRateDegS: Double)

/**
 * The drive: 4 s standing in a queue, pulling away at 2 m/s², 50 km/h through the curve, braking
 * to 20 km/h for the turn, ending 12 m before the side street.
 */
class DriveScript(private val world: DriveWorld, private val dt: Double) {
  fun states(): List<CarState> {
    val out = ArrayList<CarState>()
    var t = 0.0
    var s = 40.0
    var v = 0.0
    var prevHeading = headingAt(s)
    while (s < world.junctionS - 12.0) {
      val toTurn = world.junctionS - s
      val target = if (t < 4.0) 0.0 else if (toTurn < 70.0) 6.0 else 13.9
      val a =
          when {
            v < target - 0.05 -> 2.0
            v > target + 0.05 -> -2.5
            else -> 0.0
          }
      v = (v + a * dt).coerceAtLeast(0.0)
      s += v * dt
      t += dt
      val hdg = headingAt(s)
      val rate = angleDiffDeg(hdg, prevHeading) / dt
      prevHeading = hdg
      out += CarState(t, s, v, a, hdg, rate)
    }
    return out
  }

  private fun headingAt(s: Double): Double {
    val d = world.road(s).second
    return normalizeDeg(Math.toDegrees(atan2(d.x, d.y)))
  }
}

/** The closest point of a polyline to [p]. */
fun snapTo(line: List<Enu>, p: Enu): Enu {
  var best = line[0]
  var bd = Double.MAX_VALUE
  for (i in 0 until line.size - 1) {
    val a = line[i]
    val b = line[i + 1]
    val ab = b - a
    val l2 = ab.x * ab.x + ab.y * ab.y
    val t = if (l2 == 0.0) 0.0 else (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / l2).coerceIn(0.0, 1.0)
    val q = a + ab * t
    val d = hypot(q.x - p.x, q.y - p.y)
    if (d < bd) {
      bd = d
      best = q
    }
  }
  return best
}

/** Distance along a polyline to its closest point to [p]. */
fun alongOf(line: List<Enu>, p: Enu): Double {
  var walked = 0.0
  var best = 0.0
  var bd = Double.MAX_VALUE
  for (i in 0 until line.size - 1) {
    val a = line[i]
    val b = line[i + 1]
    val ab = b - a
    val len = hypot(ab.x, ab.y)
    if (len < 1e-9) continue
    val t = (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / (len * len)).coerceIn(0.0, 1.0)
    val q = a + ab * t
    val d = hypot(q.x - p.x, q.y - p.y)
    if (d < bd) {
      bd = d
      best = walked + t * len
    }
    walked += len
  }
  return best
}

internal fun atanDeg(x: Double) = Math.toDegrees(atan(x))
