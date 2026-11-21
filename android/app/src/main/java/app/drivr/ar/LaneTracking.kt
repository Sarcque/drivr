package app.drivr.ar

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The lane the car is in, as seen by the camera, in metres on the road relative to the camera.
 *
 * @param centreM lane centre to the right of the camera (negative: left), about 11 m ahead.
 * @param angleDeg the lane's direction relative to the heading the grid was laid along (the route's
 *   direction, i.e. the carpet's), positive: the lane runs to the right. This is exactly how far the
 *   carpet is turned off the lane.
 * @param widthM distance between the two lines.
 * @param confidence 0..1, how much of the two lines was found.
 */
data class LaneMeasurement(
    val centreM: Double,
    val angleDeg: Double,
    val widthM: Double,
    val confidence: Float,
    /** How much further down the camera really looks than the pose says, °; null if unknown. */
    val pitchErrorDeg: Double? = null,
)

/**
 * Parallel lines keep their width. If, laid out with our pitch, the lane comes out [wFar] wide at
 * [dFar] but [wNear] at [dNear], the pitch is off: solve for the error that makes them equal.
 * Positive: the camera looks further down than the pose says.
 */
fun pitchErrorFromWidths(wNear: Double, wFar: Double, dNear: Double, dFar: Double, heightM: Double): Double? {
  if (wNear < 1.5 || wFar < 1.5) return null
  val target = wFar / wNear
  // Believed distance d' -> true distance with an error delta: angle below horizon grows by delta.
  fun ratio(delta: Double): Double {
    val tn = heightM / kotlin.math.tan(atan(heightM / dNear) + delta)
    val tf = heightM / kotlin.math.tan(atan(heightM / dFar) + delta)
    // Lateral scales with believed/true distance.
    return (dFar / tf) / (dNear / tn)
  }
  var lo = Math.toRadians(-4.0)
  var hi = Math.toRadians(4.0)
  if ((ratio(lo) - target) * (ratio(hi) - target) > 0) return null
  repeat(40) {
    val mid = (lo + hi) / 2
    if ((ratio(lo) - target) * (ratio(mid) - target) <= 0) hi = mid else lo = mid
  }
  return Math.toDegrees((lo + hi) / 2)
}

/**
 * Finds the lane markings either side of the car in a camera frame.
 *
 * With the camera pose we already have, the road surface 7-25 m ahead is resampled into a
 * bird's-eye grid (metres along x metres across). Lane markings are then simply narrow stripes
 * brighter than the asphalt either side, running along the grid. Each grid row votes for where it
 * sees such a stripe; the left and right line of the car's own lane are the pair of well-supported
 * columns, a lane's width apart, with the car between them. Comparing where the lines sit in the
 * near and the far half gives the lane's angle.
 *
 * Why this survives the windscreen: a raindrop or the glare of the sun is a blob, not a stripe
 * that runs along the road for metres, so it gets a vote from a row or two at most; saturated
 * (blown-out) pixels don't vote at all; and a car ahead only hides the far rows.
 */
class LaneDetector(
    private val nearM: Double = 7.5,
    private val farM: Double = 25.0,
    private val rowStepM: Double = 0.5,
    private val halfSpanM: Double = 5.0,
    private val colStepM: Double = 0.08,
) {
  private val rows = ((farM - nearM) / rowStepM).toInt() + 1
  private val cols = (2 * halfSpanM / colStepM).roundToInt() + 1
  private val splitM = (nearM + farM) / 2

  /**
   * [pose] is the camera pose for [img] (same field of view [fovDeg] across the long side). The
   * grid runs along [headingDeg]: pass the route's direction at the car, so the angle found is the
   * carpet's error.
   */
  fun detect(
      img: LumaImage,
      pose: CameraPose,
      fovDeg: Double,
      headingDeg: Double,
      route: List<Enu>? = null,
      expectedWidthM: Double = 3.3,
  ): LaneMeasurement? {
    fun at(offset: Double): LaneMeasurement? {
      val p = ArProjector(pose.copy(pitchDownDeg = pose.pitchDownDeg + offset), img.width.toFloat(), img.height.toFloat(), fovDeg)
      val m = detectWith(img, p, headingDeg, pose.heightM, route, expectedWidthM) ?: return null
      return m.copy(pitchErrorDeg = (m.pitchErrorDeg ?: 0.0) + offset)
    }
    val first = at(0.0)
    if (first != null && first.confidence >= 0.3f) return first
    // Nothing clear: the pitch may be off by more than the lane's own shape can tell (after a
    // hard pull-away, or a new mount). Try a few, keep the one that shows a proper lane.
    var best = first
    for (o in doubleArrayOf(1.5, -1.5, 3.0, -3.0)) {
      val m = at(o) ?: continue
      if (best == null || m.confidence > best.confidence) best = m
    }
    return best
  }

  private fun detectWith(
      img: LumaImage,
      p: ArProjector,
      headingDeg: Double,
      heightM: Double,
      route: List<Enu>?,
      expectedWidthM: Double,
  ): LaneMeasurement? {
    val yaw = Math.toRadians(headingDeg)
    // The grid follows the route: each row is laid across the route at that distance, so in a
    // curve the lane lines are still straight columns, and what is measured is directly how far
    // the lane is off the carpet (sideways and in angle).
    val baseX = DoubleArray(rows)
    val baseY = DoubleArray(rows)
    val rX = DoubleArray(rows)
    val rY = DoubleArray(rows)
    for (k in 0 until rows) {
      val d = nearM + k * rowStepM
      val pd = route?.let { pointAndDirectionAt(it, d) }
      if (pd != null) {
        baseX[k] = pd.first.x - route[0].x
        baseY[k] = pd.first.y - route[0].y
        rX[k] = pd.second.y
        rY[k] = -pd.second.x
      } else {
        baseX[k] = kotlin.math.sin(yaw) * d
        baseY[k] = kotlin.math.cos(yaw) * d
        rX[k] = kotlin.math.cos(yaw)
        rY[k] = -kotlin.math.sin(yaw)
      }
    }
    val near = FloatArray(cols)
    val far = FloatArray(cols)
    var nearRows = 0
    var farRows = 0
    val line = FloatArray(cols)
    val ok = BooleanArray(cols)
    for (k in 0 until rows) {
      val d = nearM + k * rowStepM
      var valid = 0
      for (j in 0 until cols) {
        val x = -halfSpanM + j * colStepM
        val s = p.project(Enu(baseX[k] + rX[k] * x, baseY[k] + rY[k] * x), 1.0)
        if (s == null || s.x < 0f || s.y < 0f || s.x > img.width - 1.001f || s.y > img.height - 1.001f) {
          ok[j] = false
          continue
        }
        val v = bilinear(img, s.x, s.y)
        line[j] = v
        // Blown out by the sun or a headlight: no information there.
        ok[j] = v < 250f
        if (ok[j]) valid++
      }
      if (valid < cols / 3) continue
      // Contrast in this row, so a dull wet road and a bright dry one are judged alike.
      var mean = 0.0
      var n = 0
      for (j in 0 until cols) if (ok[j]) { mean += line[j]; n++ }
      mean /= n
      var dev = 0.0
      for (j in 0 until cols) if (ok[j]) dev += abs(line[j] - mean)
      dev /= n
      val minRise = max(10.0, 1.2 * dev).toFloat()
      // Stripe: brighter than both sides 0.24-0.48 m away (markings are 0.1-0.3 m wide).
      val hit = BooleanArray(cols)
      for (j in 6 until cols - 6) {
        if (!ok[j] || !ok[j - 6] || !ok[j + 6] || !ok[j - 3] || !ok[j + 3]) continue
        val left = (line[j - 3] + line[j - 4] + line[j - 5] + line[j - 6]) / 4f
        val right = (line[j + 3] + line[j + 4] + line[j + 5] + line[j + 6]) / 4f
        val rise = line[j] - max(left, right)
        if (rise < minRise) continue
        // Local maximum only, so a stripe votes once per row.
        if (line[j] < line[j - 1] || line[j] < line[j + 1]) continue
        hit[j] = true
      }
      val target = if (d < splitM) near else far
      for (j in 0 until cols) if (hit[j]) {
        target[j] += 1f
        // Lines a few cm off the grid column: let neighbours share the vote.
        if (j > 0) target[j - 1] += 0.5f
        if (j < cols - 1) target[j + 1] += 0.5f
      }
      if (d < splitM) nearRows++ else farRows++
    }
    if (nearRows < 6) return null

    // Best pair in the near half (least affected by a pitch error): left line left of the camera,
    // right line right of it, a lane's width apart.
    val minW = (2.3 / colStepM).toInt()
    val maxW = (4.6 / colStepM).toInt()
    val centreCol = cols / 2
    var best = 0f
    var bl = -1
    var br = -1
    val need = 0.15f * nearRows
    for (l in 0 until centreCol) {
      val sl = near[l]
      if (sl < need) continue
      for (r in l + minW..min(cols - 1, l + maxW)) {
        if (r <= centreCol) continue
        val sr = near[r]
        if (sr < need) continue
        val c = (l + r) / 2.0 - centreCol
        // Prefer the lane the car is actually in: centre near the camera.
        val centrePenalty = exp(-(c * colStepM) * (c * colStepM) / (2 * 1.0 * 1.0)).toFloat()
        // And a lane of the width we've been seeing (a kerb next to the edge line is the usual
        // wrong answer).
        val wd = (r - l) * colStepM - expectedWidthM
        val widthPenalty = exp(-wd * wd / (2 * 0.4 * 0.4)).toFloat()
        val s = min(sl, sr) * centrePenalty * widthPenalty
        if (s > best) {
          best = s
          bl = l
          br = r
        }
      }
    }
    if (bl < 0) return null
    // Two stripes close together on one side (edge line and kerb, or a double line): the inner
    // one is the lane's edge.
    for (j in bl + 1..min(bl + (0.6 / colStepM).toInt(), centreCol)) if (near[j] >= 0.6f * near[bl] && isPeak(near, j)) bl = j
    for (j in br - 1 downTo max(br - (0.6 / colStepM).toInt(), centreCol)) if (near[j] >= 0.6f * near[br] && isPeak(near, j)) br = j
    val xlN = refine(near, bl)
    val xrN = refine(near, br)
    val centre = (xlN + xrN) / 2 * colStepM - halfSpanM
    val widthN = (xrN - xlN) * colStepM
    // The same lines in the far half, searched within a metre of where they are near.
    val dNear = (nearM + splitM) / 2
    val dFar = (splitM + farM) / 2
    // Along the route the lines are near-straight columns, so search the far half close by.
    val xlF = if (farRows >= 6) peakNear(far, bl, (0.6 / colStepM).toInt(), 0.12f * farRows) else null
    val xrF = if (farRows >= 6) peakNear(far, br, (0.6 / colStepM).toInt(), 0.12f * farRows) else null
    val angles = ArrayList<Double>(2)
    if (xlF != null) angles += Math.toDegrees(atan((xlF - xlN) * colStepM / (dFar - dNear)))
    if (xrF != null) angles += Math.toDegrees(atan((xrF - xrN) * colStepM / (dFar - dNear)))
    // A pitch error turns the lines outwards (camera really looks further down than we think)
    // or inwards; the average of both lines' angles cancels that, their difference measures it.
    val angle = if (angles.isEmpty()) 0.0 else angles.average()
    val pitchErr =
        if (xlF != null && xrF != null) pitchErrorFromWidths(widthN, (xrF - xlF) * colStepM, dNear, dFar, heightM) else null
    val conf = (min(near[bl], near[br]) / nearRows).coerceIn(0f, 1f) * (if (angles.size == 2) 1f else 0.6f)
    return LaneMeasurement(centre, angle, widthN, conf, pitchErr)
  }

  private fun isPeak(h: FloatArray, j: Int) = (j == 0 || h[j] >= h[j - 1]) && (j == h.lastIndex || h[j] >= h[j + 1])

  private fun refine(h: FloatArray, c: Int): Double {
    var s = 0.0
    var w = 0.0
    for (j in max(0, c - 2)..min(h.lastIndex, c + 2)) {
      s += j * h[j].toDouble()
      w += h[j]
    }
    return if (w > 0) s / w else c.toDouble()
  }

  /** Sub-column position of the strongest votes within [radius] of [c], or null if too few. */
  private fun peakNear(h: FloatArray, c: Int, radius: Int, minVotes: Float = 2f): Double? {
    var bj = -1
    for (j in max(0, c - radius)..min(h.lastIndex, c + radius)) if (bj < 0 || h[j] > h[bj]) bj = j
    if (bj < 0 || h[bj] < max(2f, minVotes)) return null
    return refine(h, bj)
  }
}

/**
 * How far right of a straight line along [headingDeg] (through [line]'s first point) the route is
 * when it is [aheadM] metres ahead along that line.
 */
internal fun lateralAlong(line: List<Enu>, headingDeg: Double, aheadM: Double): Double {
  if (line.size < 2) return 0.0
  val h = Math.toRadians(headingDeg)
  val fx = kotlin.math.sin(h)
  val fy = kotlin.math.cos(h)
  val o = line[0]
  var prevA = 0.0
  var prevL = 0.0
  for (i in 1 until line.size) {
    val dx = line[i].x - o.x
    val dy = line[i].y - o.y
    val a = dx * fx + dy * fy
    val l = dx * fy - dy * fx
    if (a >= aheadM) {
      val t = if (a - prevA > 1e-9) (aheadM - prevA) / (a - prevA) else 0.0
      return prevL + t * (l - prevL)
    }
    prevA = a
    prevL = l
  }
  return prevL
}

private fun bilinear(img: LumaImage, x: Float, y: Float): Float {
  val x0 = x.toInt()
  val y0 = y.toInt()
  val ax = x - x0
  val ay = y - y0
  val a = img[x0, y0]
  val b = img[x0 + 1, y0]
  val c = img[x0, y0 + 1]
  val d = img[x0 + 1, y0 + 1]
  return (a * (1 - ax) + b * ax) * (1 - ay) + (c * (1 - ax) + d * ax) * ay
}

/**
 * Turns lane measurements into a steady correction for the overlay:
 * - [shiftM]: move the carpet sideways onto the lane's centre (the car is rarely dead centre);
 * - [yawDeg]: turn it so it runs along the lane lines (OSM geometry and the mount are a degree
 *   or two off, which is a metre or more at 50 m).
 * Outliers (a merging lane, a zebra crossing, a lane change) are held back until they repeat;
 * without lane lines both corrections fade back to zero within a few seconds.
 */
class LaneLock {
  var shiftM = 0.0
    private set

  var yawDeg = 0.0
    private set

  var widthM = 3.3
    private set

  /** Correction to add to the camera's pitch (down positive), from the lane's width. */
  var pitchDeg = 0.0
    private set

  /** Recent good measurements, 0..1; the overlay shows "on your lane" from about 0.5. */
  var quality = 0f
    private set

  private var pendingJump = 0
  private var lastNanos = 0L

  fun onMeasurement(m: LaneMeasurement?, nowNanos: Long, turning: Boolean) {
    val dt = if (lastNanos == 0L) 0.2 else ((nowNanos - lastNanos) / 1e9).coerceIn(0.0, 1.0)
    lastNanos = nowNanos
    if (m == null || turning || m.confidence < 0.2f) {
      decay(dt)
      return
    }
    if (abs(m.centreM - shiftM) > 0.9 && quality > 0.3f) {
      // A lane change, or a wrong pair of lines: believe it only once it repeats.
      if (++pendingJump < 3) {
        decay(dt)
        return
      }
    }
    pendingJump = 0
    val a = (0.35 * m.confidence.coerceAtMost(1f)).coerceAtLeast(0.12)
    shiftM += a * (m.centreM.coerceIn(-1.4, 1.4) - shiftM)
    // The angle is a residual against the corrected pose: integrate it.
    // Fast once the lines are clearly seen: the route's own direction steps by a degree or so at
    // every OSM vertex, and the carpet should stay on the lines through that.
    val ga = if (quality > 0.6f) 0.6 * m.confidence.coerceAtLeast(0.5f) else 0.5 * a
    if (abs(m.angleDeg) < 6.0) yawDeg = (yawDeg + ga * m.angleDeg).coerceIn(-6.0, 6.0)
    if (m.widthM in 2.6..4.2) widthM += 0.2 * (m.widthM - widthM)
    m.pitchErrorDeg?.let { if (abs(it) < 3.5) pitchDeg = (pitchDeg + 0.4 * a * it).coerceIn(-4.0, 4.0) }
    quality = min(1f, quality + 0.25f * m.confidence.coerceAtLeast(0.4f))
  }

  /** The base pose changed by these amounts: take them out of the correction. */
  fun absorb(dYawDeg: Double, dPitchDeg: Double) {
    // pose heading = car + mount yaw - lane yaw; pose pitch = gravity + bias + lane pitch.
    // Only what the lane lock had itself been making up for: towards zero, never past it.
    yawDeg = towardsZero(yawDeg, dYawDeg)
    pitchDeg = towardsZero(pitchDeg, -dPitchDeg)
  }

  private fun towardsZero(v: Double, d: Double): Double {
    if (v == 0.0 || v * d > 0) return v
    return if (abs(d) >= abs(v)) 0.0 else v + d
  }

  private fun decay(dt: Double) {
    val k = exp(-dt / 3.0)
    shiftM *= k
    yawDeg *= k
    // Pitch is a property of the mount, not of the moment: let it go only slowly.
    pitchDeg *= exp(-dt / 20.0)
    quality = max(0f, quality - 0.08f * dt.toFloat() * 5f)
  }

  fun reset() {
    shiftM = 0.0
    yawDeg = 0.0
    pitchDeg = 0.0
    quality = 0f
    pendingJump = 0
  }
}
