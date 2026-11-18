package app.drivr.ar

import kotlin.math.abs
import kotlin.math.sqrt

/** Signed difference a - b in degrees, in (-180, 180]. */
fun angleDiffDeg(a: Double, b: Double): Double {
  var d = (a - b) % 360.0
  if (d > 180.0) d -= 360.0
  if (d <= -180.0) d += 360.0
  return d
}

/**
 * How fast the phone turns around the vertical, in compass degrees per second (clockwise
 * positive), from the gyroscope and the gravity vector (both in device axes; gravity as Android's
 * TYPE_GRAVITY reports it, pointing up). Works whatever way the phone is mounted.
 */
fun headingRateDegPerS(gx: Float, gy: Float, gz: Float, wx: Float, wy: Float, wz: Float): Double {
  val n = sqrt(gx * gx + gy * gy + gz * gz)
  if (n < 1e-3f) return 0.0
  // Rotation about "up" is counter-clockwise seen from above; compass heading runs clockwise.
  val upRate = (wx * gx + wy * gy + wz * gz) / n
  return -Math.toDegrees(upRate.toDouble())
}

/**
 * The car's heading, smooth at frame rate: the gyroscope carries it through curves and between
 * GPS fixes (which come once a second), and the GPS course pulls it back so gyro drift never
 * builds up. Without a gyro it falls back to the GPS course, eased.
 */
class HeadingFuser {
  var headingDeg: Double? = null
    private set

  private var hasGyro = false

  /** Gyro step. [rateDegPerS] from [headingRateDegPerS]. */
  fun onGyro(rateDegPerS: Double, dtS: Double) {
    hasGyro = true
    val h = headingDeg ?: return
    if (dtS <= 0 || dtS > 0.5) return
    headingDeg = normalizeDeg(h + rateDegPerS * dtS)
  }

  /** GPS fix. Course is only trusted while moving; standing still it wanders. */
  fun onCourse(courseDeg: Double, speedMps: Double) {
    val h = headingDeg
    if (h == null) {
      headingDeg = normalizeDeg(courseDeg)
      return
    }
    if (speedMps < 2.0) return
    // With a gyro the course only corrects drift; without one it is the heading, eased.
    val gain = if (hasGyro) (if (speedMps > 8.0) 0.25 else 0.12) else 0.6
    headingDeg = normalizeDeg(h + gain * angleDiffDeg(courseDeg, h))
  }

  fun reset() {
    headingDeg = null
  }
}

/** What the camera knows about how it is mounted, learned from the road itself. */
data class MountState(
    /** Camera axis minus car axis, degrees (positive: phone turned to the right). */
    val yawOffsetDeg: Double,
    /** Correction added to the gravity sensor's pitch (accelerometer bias, mount, road slope). */
    val pitchBiasDeg: Double,
    /** Good road measurements taken so far. */
    val samples: Int,
) {
  /** Enough measurements to trust the overlay is on the road. */
  val locked: Boolean
    get() = samples >= MountCalibrator.LOCK_SAMPLES
}

/**
 * Learns the phone mount from the camera: on a straight stretch, the road's vanishing point is
 * straight ahead of the car, so its offset from the image centre is exactly how far the phone is
 * turned (yaw) and tilted (pitch) relative to the car. These barely change during a drive, so a
 * slow, outlier-resistant average is accurate even from noisy single frames.
 */
class MountCalibrator {
  var state = MountState(0.0, 0.0, 0)
    private set

  /**
   * @param angles measured from the current frame.
   * @param gravityPitchDownDeg the gravity sensor's pitch at the same moment.
   * @param confidence from [VanishingPointEstimator].
   * @param straightAhead the route runs straight for a good distance ahead and the car isn't
   *   turning, so the road's vanishing point is the car's direction of travel.
   * @return whether the measurement was used.
   */
  fun onMeasurement(
      angles: RoadAngles,
      gravityPitchDownDeg: Double,
      confidence: Float,
      straightAhead: Boolean,
  ): Boolean {
    if (!straightAhead || confidence < 0.35f) return false
    val yaw = -angles.roadRightOfCentreDeg
    val bias = angles.pitchDownDeg - gravityPitchDownDeg
    if (abs(yaw) > 25 || abs(bias) > 12) return false
    val s = state
    if (s.samples >= 5) {
      // Once settled, one frame can't drag it far: a car ahead or a merging lane gives outliers.
      if (abs(yaw - s.yawOffsetDeg) > 6 || abs(bias - s.pitchBiasDeg) > 4) return false
    }
    // Fast at first, then a slow average.
    val a = maxOf(1.0 / (s.samples + 1), 0.06) * confidence.coerceAtLeast(0.5f)
    state =
        if (s.samples == 0) MountState(yaw, bias, 1)
        else
            MountState(
                s.yawOffsetDeg + a * (yaw - s.yawOffsetDeg),
                s.pitchBiasDeg + a * (bias - s.pitchBiasDeg),
                s.samples + 1,
            )
    return true
  }

  fun reset() {
    state = MountState(0.0, 0.0, 0)
  }

  companion object {
    const val LOCK_SAMPLES = 8
  }
}

/** True when [ahead] (2 m steps, starting at the car) stays within [toleranceM] of a straight line for [overM]. */
fun isStraight(ahead: List<Enu>, overM: Double = 80.0, toleranceM: Double = 1.2, stepM: Double = 2.0): Boolean {
  val n = (overM / stepM).toInt()
  if (ahead.size <= n) return false
  val a = ahead[0]
  val b = ahead[n]
  val dx = b.x - a.x
  val dy = b.y - a.y
  val len = sqrt(dx * dx + dy * dy)
  if (len < overM * 0.8) return false
  for (i in 1 until n) {
    val p = ahead[i]
    val off = abs((p.x - a.x) * dy - (p.y - a.y) * dx) / len
    if (off > toleranceM) return false
  }
  return true
}

/**
 * Everything that decides where the camera looks, in one place: gravity tilt, gyro + GPS heading,
 * and the mount learned from the camera image. Sensor callbacks, the camera analyser and the
 * drawing all run on different threads, hence the locking.
 */
class ArPoseEngine(private val longSideFovDeg: () -> Double = { 68.0 }) {
  private val heading = HeadingFuser()
  private val mount = MountCalibrator()
  private var tilt = Tilt(8.0, 0.0)
  private var turnRateDegPerS = 0.0
  private var speedMps = 0.0

  /** Set by the scene: the route runs straight ahead for a good distance. */
  @Volatile var routeStraightAhead = false

  /** Last vanishing point seen, for the on-screen alignment hint (fractions of the view). */
  @Volatile var lastVanishingPoint: VanishingPoint? = null
    private set

  @Synchronized
  fun onTilt(t: Tilt) {
    tilt = t
  }

  @Synchronized
  fun onGyro(rateDegPerS: Double, dtS: Double) {
    turnRateDegPerS += 0.2 * (rateDegPerS - turnRateDegPerS)
    heading.onGyro(rateDegPerS, dtS)
  }

  @Synchronized
  fun onFix(courseDeg: Double?, speed: Double) {
    speedMps = speed
    if (courseDeg != null) heading.onCourse(courseDeg, speed)
  }

  /** A camera frame's vanishing point; [aspect] = width / height of the upright frame. */
  @Synchronized
  fun onVanishingPoint(vp: VanishingPoint, aspect: Float): Boolean {
    lastVanishingPoint = vp
    val steady = abs(turnRateDegPerS) < 3.0 && speedMps > 6.0
    return mount.onMeasurement(roadAngles(vp, aspect, longSideFovDeg()), tilt.pitchDownDeg, vp.confidence, routeStraightAhead && steady)
  }

  @Synchronized fun mountState(): MountState = mount.state

  @Synchronized fun speed(): Double = speedMps

  /** Camera pose now, or null before the first heading. [fallbackHeading] covers that gap. */
  @Synchronized
  fun pose(fallbackHeading: Double): CameraPose {
    val m = mount.state
    val car = heading.headingDeg ?: fallbackHeading
    return CameraPose(
        headingDeg = normalizeDeg(car + m.yawOffsetDeg),
        pitchDownDeg = tilt.pitchDownDeg + m.pitchBiasDeg,
        rollDeg = tilt.rollDeg,
    )
  }
}
