package app.drivr.location

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Cleans up the phone's position fixes before anything else sees them, the way a car navigator
 * does:
 *
 * - drops fixes that arrive out of order or twice,
 * - drops rough fixes (cell / Wi-Fi, 80 m+) while good GPS fixes are coming in,
 * - drops jumps the car can't have made (multipath in cities, a stray fix 300 m away), but gives
 *   in after a few in a row so it never gets stuck on the wrong spot,
 * - smooths what's left with a small Kalman filter (position + velocity, constant-velocity
 *   model), using the receiver's own speed and course so smoothing doesn't add lag,
 * - keeps the last good heading while standing still or crawling, instead of the receiver's
 *   random course at walking speed (or north when it has none).
 */
class FixFilter(
    /** How hard a car can change speed, m/s². Higher follows faster, lower smooths more. */
    private val accelNoise: Double = 3.0,
) {
  private var frame: LocalFrame? = null
  private val x = Axis()
  private val y = Axis()
  private var lastTimeMs = Long.MIN_VALUE
  private var lastGoodMs = Long.MIN_VALUE
  private var rejectedInRow = 0
  private var firstRejectedMs = 0L
  private var heading: Double? = null

  /** Last accepted, cleaned-up fix. */
  var last: Fix? = null
    private set

  fun reset() {
    frame = null
    last = null
    heading = null
    lastTimeMs = Long.MIN_VALUE
    lastGoodMs = Long.MIN_VALUE
    rejectedInRow = 0
  }

  /** The cleaned-up fix, or null when [raw] is dropped. */
  fun accept(raw: Fix): Fix? {
    if (raw.timeMs <= lastTimeMs) return null
    if (!raw.lat.isFinite() || !raw.lon.isFinite() || (raw.lat == 0.0 && raw.lon == 0.0)) return null
    val acc = raw.accuracyM.takeIf { it.isFinite() && it > 0 } ?: 30.0

    val f = frame
    if (f == null || last == null) return start(raw, acc)

    // Rough fixes while precise ones are coming in: ignore (the receiver will send a good one).
    val goodRecently = raw.timeMs - lastGoodMs < 15_000
    if (goodRecently && acc > ROUGH_M) return reject(raw, acc)

    val dt = ((raw.timeMs - lastTimeMs) / 1000.0).coerceIn(0.0, 30.0)
    val mx = f.x(raw.lon)
    val my = f.y(raw.lat)
    x.predict(dt, accelNoise)
    y.predict(dt, accelNoise)
    val dx = mx - x.p
    val dy = my - y.p
    val miss = sqrt(dx * dx + dy * dy)
    val gate = 3.0 * sqrt(x.pp + y.pp + 2 * acc * acc) + 15.0
    if (miss > gate && miss > JUMP_M && !raw.predicted) {
      x.undoPredict(); y.undoPredict()
      return reject(raw, acc)
    }

    x.updatePosition(mx, acc * acc)
    y.updatePosition(my, acc * acc)
    val speed = raw.speedMps?.takeIf { it.isFinite() && it >= 0 }
    val course = raw.bearingDeg?.takeIf { it.isFinite() }
    if (speed != null && course != null && speed >= 1.0) {
      val r = Math.toRadians(course)
      val sv = (0.6 + speed * 0.05).let { it * it }
      x.updateVelocity(speed * sin(r), sv)
      y.updateVelocity(speed * cos(r), sv)
    } else if (speed != null && speed < 0.5) {
      // Receiver says we're standing: pull velocity to zero so the car doesn't creep.
      x.updateVelocity(0.0, 0.25)
      y.updateVelocity(0.0, 0.25)
    }
    return emit(raw, acc, speed, course)
  }

  private fun start(raw: Fix, acc: Double): Fix {
    val f = LocalFrame(raw.lat, raw.lon)
    frame = f
    x.reset(0.0, acc * acc)
    y.reset(0.0, acc * acc)
    val speed = raw.speedMps?.takeIf { it.isFinite() && it >= 0 }
    val course = raw.bearingDeg?.takeIf { it.isFinite() }
    if (speed != null && course != null && speed >= 1.0) {
      val r = Math.toRadians(course)
      x.v = speed * sin(r); y.v = speed * cos(r)
      x.vv = 1.0; y.vv = 1.0
    }
    heading = null
    return emit(raw, acc, speed, course)
  }

  private fun reject(raw: Fix, acc: Double): Fix? {
    if (rejectedInRow == 0) firstRejectedMs = raw.timeMs
    rejectedInRow++
    // Several in a row, or for a while: the car really is somewhere else (left a tunnel, the
    // filter was wrong). Start over from this fix rather than ignoring the truth forever.
    if (rejectedInRow >= 4 || raw.timeMs - firstRejectedMs > 6_000) {
      // …unless it's a rough fix while good ones are still arriving.
      if (acc > ROUGH_M && raw.timeMs - lastGoodMs < 15_000) return null
      return start(raw, acc)
    }
    return null
  }

  private fun emit(raw: Fix, acc: Double, speed: Double?, course: Double?): Fix {
    val f = frame!!
    // Keep the local frame near the car (flat-earth stays accurate).
    if (x.p * x.p + y.p * y.p > 20_000.0 * 20_000.0) {
      val lat = f.lat(y.p); val lon = f.lon(x.p)
      frame = LocalFrame(lat, lon)
      x.p = 0.0; y.p = 0.0
    }
    val fr = frame!!
    lastTimeMs = raw.timeMs
    if (acc <= GOOD_M && !raw.predicted) lastGoodMs = raw.timeMs
    rejectedInRow = 0

    val filteredSpeed = sqrt(x.v * x.v + y.v * y.v)
    val outSpeed = speed ?: filteredSpeed
    heading =
        when {
          // Fast enough for the receiver's Doppler course to be right: use it.
          course != null && outSpeed >= 5.0 -> course
          // Slower: the filtered direction of travel is steadier than the raw course.
          filteredSpeed >= 2.0 -> bearingOf(x.v, y.v)
          // Crawling or standing: keep the heading we had (no spinning arrow at the lights).
          else -> heading ?: course?.takeIf { outSpeed >= 1.0 }
        }
    val out =
        Fix(
            lat = fr.lat(y.p),
            lon = fr.lon(x.p),
            accuracyM = acc,
            speedMps = outSpeed,
            bearingDeg = heading,
            timeMs = raw.timeMs,
            predicted = raw.predicted,
        )
    last = out
    return out
  }

  /** One axis of the constant-velocity filter: position p, velocity v, covariance [pp pv; pv vv]. */
  private class Axis {
    var p = 0.0
    var v = 0.0
    var pp = 0.0
    var pv = 0.0
    var vv = 0.0
    private val saved = DoubleArray(5)

    fun reset(p0: Double, var0: Double) {
      p = p0; v = 0.0; pp = var0; pv = 0.0; vv = 25.0
    }

    fun predict(dt: Double, accel: Double) {
      saved[0] = p; saved[1] = v; saved[2] = pp; saved[3] = pv; saved[4] = vv
      val q = accel * accel
      p += v * dt
      val npp = pp + 2 * dt * pv + dt * dt * vv + q * dt * dt * dt * dt / 4
      val npv = pv + dt * vv + q * dt * dt * dt / 2
      val nvv = vv + q * dt * dt
      pp = npp; pv = npv; vv = nvv
    }

    fun undoPredict() {
      p = saved[0]; v = saved[1]; pp = saved[2]; pv = saved[3]; vv = saved[4]
    }

    fun updatePosition(z: Double, r: Double) {
      val s = pp + r
      val kp = pp / s
      val kv = pv / s
      val e = z - p
      p += kp * e
      v += kv * e
      val npp = (1 - kp) * pp
      val npv = (1 - kp) * pv
      val nvv = vv - kv * pv
      pp = npp; pv = npv; vv = nvv
    }

    fun updateVelocity(z: Double, r: Double) {
      val s = vv + r
      val kp = pv / s
      val kv = vv / s
      val e = z - v
      p += kp * e
      v += kv * e
      val npp = pp - kp * pv
      val npv = (1 - kv) * pv
      val nvv = (1 - kv) * vv
      pp = npp; pv = npv; vv = nvv
    }
  }

  private companion object {
    const val GOOD_M = 25.0
    const val ROUGH_M = 80.0
    const val JUMP_M = 60.0
  }
}
