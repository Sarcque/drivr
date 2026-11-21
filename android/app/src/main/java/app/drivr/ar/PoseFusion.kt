package app.drivr.ar

import kotlin.math.abs
import kotlin.math.exp
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
 * The car's heading, smooth at frame rate.
 *
 * - The gyroscope carries it through curves and between GPS fixes.
 * - The route is the anchor: while on route, the car points along the road it is on, and the
 *   route's direction at the car is known exactly, standing still or not. That is what fixed the
 *   overlay pointing off into the verge while queueing: a standing car's GPS course is noise.
 * - The GPS course is only used before there is a route anchor, and only while really moving.
 */
class HeadingFuser {
  var headingDeg: Double? = null
    private set

  private var hasGyro = false
  private var hasRoute = false

  /** Smoothed turn rate, °/s; large while going round a corner. */
  var turnRateDegPerS = 0.0
    private set

  /** Gyro step. [rateDegPerS] from [headingRateDegPerS]. */
  fun onGyro(rateDegPerS: Double, dtS: Double) {
    hasGyro = true
    turnRateDegPerS += 0.2 * (rateDegPerS - turnRateDegPerS)
    val h = headingDeg ?: return
    if (dtS <= 0 || dtS > 0.5) return
    headingDeg = normalizeDeg(h + rateDegPerS * dtS)
  }

  /** GPS fix. Course is only trusted while moving; standing still it wanders. */
  fun onCourse(courseDeg: Double, speedMps: Double) {
    if (speedMps < MIN_COURSE_SPEED_MPS || hasRoute) return
    val h = headingDeg
    if (h == null) {
      headingDeg = normalizeDeg(courseDeg)
      return
    }
    // With a gyro the course only corrects drift; without one it is the heading, eased.
    val gain = if (hasGyro) (if (speedMps > 8.0) 0.25 else 0.12) else 0.6
    headingDeg = normalizeDeg(h + gain * angleDiffDeg(courseDeg, h))
  }

  /**
   * The route's direction over the first few metres ahead of the car, every frame. Pulls the
   * heading towards it within about a second on straight roads; in a corner the gyro leads and
   * the pull is gentle, because the route's corner and the car's arc don't line up exactly.
   */
  fun onRoute(routeDeg: Double, dtS: Double) {
    hasRoute = true
    val h = headingDeg
    if (h == null) {
      headingDeg = normalizeDeg(routeDeg)
      return
    }
    val err = angleDiffDeg(routeDeg, h)
    val turning = abs(turnRateDegPerS) > 4.0
    if (!turning && abs(err) > 30.0) {
      headingDeg = normalizeDeg(routeDeg)
      return
    }
    val dt = dtS.coerceIn(0.0, 0.5)
    val tau = if (turning) 2.5 else if (hasGyro) 0.8 else 0.3
    headingDeg = normalizeDeg(h + err * (1.0 - exp(-dt / tau)))
  }

  fun reset() {
    headingDeg = null
    hasRoute = false
  }

  companion object {
    const val MIN_COURSE_SPEED_MPS = 3.0
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
    if (!straightAhead || confidence < 0.3f) return false
    val yaw = -angles.roadRightOfCentreDeg
    val bias = angles.pitchDownDeg - gravityPitchDownDeg
    if (abs(yaw) > 15 || abs(bias) > 8) return false
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
    const val LOCK_SAMPLES = 6
  }
}

/** True when [ahead] (2 m steps, starting at the car) stays within [toleranceM] of a straight line for [overM]. */
fun isStraight(ahead: List<Enu>, overM: Double = 60.0, toleranceM: Double = 1.5, stepM: Double = 2.0): Boolean {
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

/** How sure the AR view is that the overlay sits on the road, as shown in the status chip. */
enum class ArTrack {
  /** Camera points at the dashboard, the sky or sideways: no road to draw on. Overlay hidden. */
  AIM_AT_ROAD,
  /** GPS too rough to know which road we're on. Overlay hidden. */
  WEAK_GPS,
  /** Drawn, near part reliable; the mount (how the phone is turned) is still being learned. */
  FINE_TUNING,
  /** Mount learned from the road, or lined up by hand. */
  LOCKED,
  /** Lined up by hand (drag) and kept. */
  MANUAL,
  ;

  val draws: Boolean
    get() = this == FINE_TUNING || this == LOCKED || this == MANUAL
}

/** Pitch within this range (°, down positive) means the camera can see the road ahead. */
private const val MIN_PITCH_DOWN = -14.0
private const val MAX_PITCH_DOWN = 28.0
private const val MAX_ROLL = 28.0

/**
 * Everything that decides where the camera looks, in one place: gravity tilt, gyro + route
 * heading, the mount learned from the camera image, and the driver's own adjustment. Sensor
 * callbacks, the camera analyser and the drawing all run on different threads, hence the locking.
 */
class ArPoseEngine(
    private val longSideFovDeg: () -> Double = { 68.0 },
    private val clockNanos: () -> Long = System::nanoTime,
) {
  private val heading = HeadingFuser()
  private val mount = MountCalibrator()
  private var rawTilt: Tilt? = null
  private var tilt = Tilt(8.0, 0.0)
  private var tiltNanos = 0L
  private var followUntilNanos = 0L
  private var speedMps = 0.0
  private var fixNanos = 0L
  private var accelMps2 = 0.0
  private var lastPoseNanos = 0L
  private var manual: Pair<Double, Double>? = null

  /** Set by the scene: the route runs straight ahead for a good distance. */
  @Volatile var routeStraightAhead = false

  /** Last vanishing point seen, for the on-screen alignment hint (fractions of the view). */
  @Volatile var lastVanishingPoint: VanishingPoint? = null
    private set

  /**
   * Gravity tilt. The phone is fixed to the car, so its true tilt hardly changes; what does change
   * the reading is the car braking, accelerating and cornering (the sensor can't tell those from
   * tilt). So: a slow average, frozen while the car brakes, accelerates or turns, and a quick
   * follow only when the phone itself has clearly been moved.
   */
  @Synchronized
  fun onTilt(t: Tilt) {
    val now = clockNanos()
    val prev = rawTilt
    rawTilt = t
    if (prev == null) {
      tilt = t
      tiltNanos = now
      return
    }
    val dt = ((now - tiltNanos) / 1e9).coerceIn(0.0, 0.5)
    tiltNanos = now
    val jump = maxOf(abs(t.pitchDownDeg - tilt.pitchDownDeg), abs(t.rollDeg - tilt.rollDeg))
    // A jump bigger than braking or cornering can fake: the phone itself was moved (on its mount
    // or in the hand). Follow quickly for a moment.
    if (jump > 8.0) followUntilNanos = now + 1_000_000_000L
    val carForces = abs(accelMps2) > 1.0 || abs(heading.turnRateDegPerS) > 4.0
    val tau =
        when {
          now < followUntilNanos -> 0.2
          carForces -> return
          else -> 4.0
        }
    val a = 1.0 - exp(-dt / tau)
    tilt = Tilt(tilt.pitchDownDeg + a * (t.pitchDownDeg - tilt.pitchDownDeg), tilt.rollDeg + a * (t.rollDeg - tilt.rollDeg))
  }

  @Synchronized
  fun onGyro(rateDegPerS: Double, dtS: Double) {
    heading.onGyro(rateDegPerS, dtS)
  }

  @Synchronized
  fun onFix(courseDeg: Double?, speed: Double) {
    val now = clockNanos()
    if (fixNanos != 0L) {
      val dt = (now - fixNanos) / 1e9
      if (dt in 0.2..3.0) accelMps2 += 0.5 * ((speed - speedMps) / dt - accelMps2)
    }
    fixNanos = now
    speedMps = speed
    if (courseDeg != null) heading.onCourse(courseDeg, speed)
  }

  /** A camera frame's vanishing point; [aspect] = width / height of the upright frame. */
  @Synchronized
  fun onVanishingPoint(vp: VanishingPoint, aspect: Float): Boolean {
    lastVanishingPoint = vp
    if (manual != null) return false
    val steady = abs(heading.turnRateDegPerS) < 3.0 && speedMps > 3.0
    return mount.onMeasurement(roadAngles(vp, aspect, longSideFovDeg()), tilt.pitchDownDeg, vp.confidence, routeStraightAhead && steady)
  }

  @Synchronized fun mountState(): MountState = mount.state

  @Synchronized fun speed(): Double = speedMps

  /** The driver's own alignment: yaw and pitch offsets (°), replacing the learned mount. */
  @Synchronized fun manualOffsets(): Pair<Double, Double>? = manual

  @Synchronized
  fun setManual(yawOffsetDeg: Double, pitchBiasDeg: Double) {
    manual = yawOffsetDeg.coerceIn(-30.0, 30.0) to pitchBiasDeg.coerceIn(-15.0, 15.0)
  }

  /** Back to learning from the road. */
  @Synchronized
  fun clearManual() {
    manual = null
    mount.reset()
  }

  /** Mount offsets in use now: the driver's, else the learned ones. */
  @Synchronized
  fun offsets(): Pair<Double, Double> = manual ?: (mount.state.yawOffsetDeg to mount.state.pitchBiasDeg)

  /**
   * Camera pose for this frame. [routeHeadingDeg] is the route's direction over the first metres
   * ahead of the car (where it is now, after dead reckoning); it anchors the heading.
   */
  @Synchronized
  fun pose(routeHeadingDeg: Double): CameraPose {
    val now = clockNanos()
    val dt = if (lastPoseNanos == 0L) 0.0 else (now - lastPoseNanos) / 1e9
    lastPoseNanos = now
    heading.onRoute(routeHeadingDeg, dt)
    val (yaw, pitchBias) = offsets()
    val car = heading.headingDeg ?: routeHeadingDeg
    return CameraPose(
        headingDeg = normalizeDeg(car + yaw),
        pitchDownDeg = tilt.pitchDownDeg + pitchBias,
        rollDeg = tilt.rollDeg,
        heightM = 1.25,
    )
  }

  /** What the status chip says, and whether the overlay is drawn at all. */
  @Synchronized
  fun track(gpsAccuracyM: Double?): ArTrack {
    val pitch = tilt.pitchDownDeg + offsets().second
    if (pitch !in MIN_PITCH_DOWN..MAX_PITCH_DOWN || abs(tilt.rollDeg) > MAX_ROLL) return ArTrack.AIM_AT_ROAD
    if (gpsAccuracyM != null && gpsAccuracyM > 30.0) return ArTrack.WEAK_GPS
    return when {
      manual != null -> ArTrack.MANUAL
      mount.state.locked -> ArTrack.LOCKED
      else -> ArTrack.FINE_TUNING
    }
  }
}
