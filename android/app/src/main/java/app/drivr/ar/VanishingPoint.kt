package app.drivr.ar

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A small grey image, upright as the driver sees it on screen (camera frames arrive in sensor
 * orientation; [uprightLuma] turns them).
 */
class LumaImage(val width: Int, val height: Int, val pixels: FloatArray) {
  operator fun get(x: Int, y: Int) = pixels[y * width + x]
}

/**
 * Reads the luma (Y) plane of a camera frame into a small upright image.
 *
 * @param rotationDegrees how far the frame must be turned clockwise to be upright (CameraX's
 *   `imageInfo.rotationDegrees`).
 * @param longSide size of the long side of the result; the image is sampled down to this.
 */
fun uprightLuma(
    y: ByteArray,
    width: Int,
    height: Int,
    rowStride: Int,
    rotationDegrees: Int,
    longSide: Int = 256,
): LumaImage {
  val rot = ((rotationDegrees % 360) + 360) % 360
  val upW = if (rot == 90 || rot == 270) height else width
  val upH = if (rot == 90 || rot == 270) width else height
  val scale = max(upW, upH).toFloat() / longSide
  val outW = (upW / scale).roundToInt().coerceAtLeast(1)
  val outH = (upH / scale).roundToInt().coerceAtLeast(1)
  val out = FloatArray(outW * outH)
  for (oy in 0 until outH) {
    for (ox in 0 until outW) {
      // Upright pixel -> frame pixel. A 2x2 average keeps sensor noise down when sampling.
      val u = ((ox + 0.5f) * scale).toInt().coerceIn(0, upW - 1)
      val v = ((oy + 0.5f) * scale).toInt().coerceIn(0, upH - 1)
      val (fx, fy) = uprightToFrame(u, v, width, height, rot)
      val fx1 = min(fx + 1, width - 1)
      val fy1 = min(fy + 1, height - 1)
      val s =
          (y[fy * rowStride + fx].toInt() and 0xFF) +
              (y[fy * rowStride + fx1].toInt() and 0xFF) +
              (y[fy1 * rowStride + fx].toInt() and 0xFF) +
              (y[fy1 * rowStride + fx1].toInt() and 0xFF)
      out[oy * outW + ox] = s / 4f
    }
  }
  return LumaImage(outW, outH, out)
}

/** Where upright pixel (u, v) lives in a frame that must be turned [rot]° clockwise. */
internal fun uprightToFrame(u: Int, v: Int, frameW: Int, frameH: Int, rot: Int): Pair<Int, Int> =
    when (rot) {
      90 -> v to (frameH - 1 - u)
      180 -> (frameW - 1 - u) to (frameH - 1 - v)
      270 -> (frameW - 1 - v) to u
      else -> u to v
    }

/**
 * Where the road converges in the image, as a fraction of the image size (0..1, origin top left),
 * and how sure we are.
 */
data class VanishingPoint(val fx: Float, val fy: Float, val confidence: Float)

/**
 * Finds the road's vanishing point in a dashboard camera frame: lane markings, road edges, kerbs
 * and barriers are parallel in the world, so in the image they all point at one spot on the
 * horizon. That spot tells us exactly where the camera looks relative to the road (yaw and pitch),
 * which GPS and the gravity sensor alone get wrong by several degrees.
 *
 * Plain Kotlin, no OpenCV: Scharr edges, every slanted edge votes along its line for the cells it
 * passes through in a grid of candidate points, and a point only counts when edges from both its
 * left and its right meet there (a road has two sides; a single diagonal wall or shadow doesn't).
 */
class VanishingPointEstimator(
    /** Region where the vanishing point may be, as fractions of the image. */
    private val searchLeft: Float = 0.15f,
    private val searchRight: Float = 0.85f,
    private val searchTop: Float = 0.15f,
    private val searchBottom: Float = 0.70f,
    private val cols: Int = 72,
    private val rows: Int = 48,
) {
  fun estimate(img: LumaImage): VanishingPoint? {
    val w = img.width
    val h = img.height
    if (w < 16 || h < 16) return null
    val x0 = searchLeft * w
    val x1 = searchRight * w
    val y0 = searchTop * h
    val y1 = searchBottom * h
    val cellW = (x1 - x0) / cols
    val cellH = (y1 - y0) / rows
    val fromLeft = FloatArray(cols * rows)
    val fromRight = FloatArray(cols * rows)

    // Edge threshold relative to the frame's contrast, so dusk and glare behave alike.
    var sum = 0.0
    var n = 0
    val mags = FloatArray(w * h)
    val gxs = FloatArray(w * h)
    val gys = FloatArray(w * h)
    // A light blur first: thin lane lines alias into stair steps, which bias edge angles.
    val b = blur3(img)
    fun at(x: Int, y: Int) = b[y * w + x]
    val firstRow = max(1, y0.toInt())
    for (y in firstRow until h - 1) {
      for (x in 1 until w - 1) {
        // Scharr: far more accurate edge directions than Sobel, which matters since every edge
        // votes along its direction for tens of pixels.
        val gx =
            (3 * at(x + 1, y - 1) + 10 * at(x + 1, y) + 3 * at(x + 1, y + 1)) -
                (3 * at(x - 1, y - 1) + 10 * at(x - 1, y) + 3 * at(x - 1, y + 1))
        val gy =
            (3 * at(x - 1, y + 1) + 10 * at(x, y + 1) + 3 * at(x + 1, y + 1)) -
                (3 * at(x - 1, y - 1) + 10 * at(x, y - 1) + 3 * at(x + 1, y - 1))
        val m = sqrt(gx * gx + gy * gy)
        val i = y * w + x
        mags[i] = m
        gxs[i] = gx
        gys[i] = gy
        sum += m
        n++
      }
    }
    if (n == 0) return null
    val threshold = max(110f, (sum / n).toFloat() * 2.5f)

    fun continues(x: Int, y: Int, dx: Float, dy: Float, nx: Float, ny: Float, step: Int): Boolean {
      val x2 = (x + dx * step).roundToInt()
      val y2 = (y + dy * step).roundToInt()
      if (x2 !in 1 until w - 1 || y2 !in 1 until h - 1) return false
      val j = y2 * w + x2
      val m2 = mags[j]
      if (m2 < 0.5f * threshold) return false
      // Same edge direction (gradient within ~18°, either polarity is the same edge's side).
      return (gxs[j] * nx + gys[j] * ny) / m2 > 0.95f
    }

    var edges = 0
    // Every voting edge, kept for the fine fit: position, unit normal, weight.
    val ex = FloatList()
    val ey = FloatList()
    val enx = FloatList()
    val eny = FloatList()
    val ew = FloatList()
    for (y in firstRow until h - 1) {
      for (x in 1 until w - 1) {
        val i = y * w + x
        val m = mags[i]
        if (m < threshold) continue
        // Edge direction is perpendicular to the gradient.
        val dx = -gys[i] / m
        val dy = gxs[i] / m
        // Keep slanted edges only: horizontal ones (dashboard, bridges, car roofs) and vertical
        // ones (poles, trees, buildings) don't point down the road.
        val slant = abs(dy)
        if (slant < 0.22f || slant > 0.96f) continue
        // Glare: the rim of a blown-out patch (sun, headlights, reflections on a wet road) is a
        // strong edge pointing nowhere in particular.
        if (nearSaturated(img, x, y)) continue
        // Only edges that continue as a straight line: lane lines, kerbs and road edges do,
        // raindrops, smudges and sensor noise don't.
        if (!continues(x, y, dx, dy, gxs[i] / m, gys[i] / m, 4) || !continues(x, y, -dx, -dy, gxs[i] / m, gys[i] / m, 4)) continue
        edges++
        ex += x.toFloat()
        ey += y.toFloat()
        enx += gxs[i] / m
        eny += gys[i] / m
        ew += min(m, 4f * threshold)
        val slope = dy / dx // image y per image x along the edge
        val wgt = min(m, 4f * threshold)
        for (c in 0 until cols) {
          val cx = x0 + (c + 0.5f) * cellW
          val yAt = y + (cx - x) * slope
          // The vanishing point is above the edge pixel (further away on the road).
          if (yAt >= y - 1) continue
          val r = ((yAt - y0) / cellH).toInt()
          if (yAt < y0 || r >= rows) continue
          val k = r * cols + c
          if (x < cx) fromLeft[k] += wgt else fromRight[k] += wgt
        }
      }
    }
    if (edges < 30) return null

    // Score = geometric mean of left and right support, lightly blurred.
    val score = FloatArray(cols * rows)
    for (r in 0 until rows) for (c in 0 until cols) {
      var l = 0f
      var rr = 0f
      for (dr in -1..1) for (dc in -1..1) {
        val r2 = r + dr
        val c2 = c + dc
        if (r2 !in 0 until rows || c2 !in 0 until cols) continue
        val wk = if (dr == 0 && dc == 0) 2f else 1f
        l += wk * fromLeft[r2 * cols + c2]
        rr += wk * fromRight[r2 * cols + c2]
      }
      score[r * cols + c] = sqrt(l * rr)
    }
    var best = 0
    for (k in score.indices) if (score[k] > score[best]) best = k
    val peak = score[best]
    if (peak <= 0f) return null
    val mean = score.average().toFloat()

    // Sub-cell position: weighted centroid of the 3x3 around the peak.
    val br = best / cols
    val bc = best % cols
    var sx = 0f
    var sy = 0f
    var sw = 0f
    for (dr in -2..2) for (dc in -2..2) {
      val r2 = br + dr
      val c2 = bc + dc
      if (r2 !in 0 until rows || c2 !in 0 until cols) continue
      // Only the peak's own hill: drop the floor so far cells don't pull the centroid.
      val s = (score[r2 * cols + c2] - 0.5f * peak).coerceAtLeast(0f)
      sx += s * (x0 + (c2 + 0.5f) * cellW)
      sy += s * (y0 + (r2 + 0.5f) * cellH)
      sw += s
    }
    // Confidence: how much the peak stands out over the rest of the grid (1 = very clear).
    val sharpness = (peak / (mean + 1e-3f))
    val confidence = ((sharpness - 3f) / 9f).coerceIn(0f, 1f)
    // +0.5: pixel indices are pixel centres.
    // Fine fit: the point closest (least squares) to the lines of the edges that pass near the
    // coarse peak, twice with a tighter radius. The grid alone is only good to about half a
    // degree; this gets it to about a tenth.
    var vx = sx / sw
    var vy = sy / sw
    for (radius in floatArrayOf(3f * cellW, 1.5f * cellW)) {
      var a11 = 0.0
      var a12 = 0.0
      var a22 = 0.0
      var b1 = 0.0
      var b2 = 0.0
      var used = 0
      for (k in 0 until ex.size) {
        val nx = enx[k]
        val ny = eny[k]
        // Distance from the current estimate to this edge's line (normal form).
        val dist = nx * (vx - ex[k]) + ny * (vy - ey[k])
        if (abs(dist) > radius) continue
        // Only edges below the vanishing point (on the road).
        if (ey[k] <= vy) continue
        val wk = ew[k].toDouble()
        a11 += wk * nx * nx
        a12 += wk * nx * ny
        a22 += wk * ny * ny
        val c = nx * ex[k] + ny * ey[k]
        b1 += wk * nx * c
        b2 += wk * ny * c
        used++
      }
      val det = a11 * a22 - a12 * a12
      if (used < 20 || abs(det) < 1e-6 * (a11 + a22) * (a11 + a22)) break
      val nxv = ((a22 * b1 - a12 * b2) / det).toFloat()
      val nyv = ((a11 * b2 - a12 * b1) / det).toFloat()
      // Never let the fit wander off the peak it started from.
      if (abs(nxv - vx) > 2.5f * cellW || abs(nyv - vy) > 2.5f * cellH) break
      vx = nxv
      vy = nyv
    }
    return VanishingPoint((vx + 0.5f) / w, (vy + 0.5f) / h, confidence)
  }
}

/** Any pixel within 2 px at (near) full white. */
private fun nearSaturated(img: LumaImage, x: Int, y: Int): Boolean {
  for (yy in max(0, y - 2)..min(img.height - 1, y + 2)) for (xx in max(0, x - 2)..min(img.width - 1, x + 2)) {
    if (img[xx, yy] >= 250f) return true
  }
  return false
}

private fun blur3(img: LumaImage): FloatArray {
  val w = img.width
  val h = img.height
  val tmp = FloatArray(w * h)
  val out = FloatArray(w * h)
  for (y in 0 until h) for (x in 0 until w) {
    val l = img[max(0, x - 1), y]
    val r = img[min(w - 1, x + 1), y]
    tmp[y * w + x] = 0.25f * l + 0.5f * img[x, y] + 0.25f * r
  }
  for (y in 0 until h) for (x in 0 until w) {
    val u = tmp[max(0, y - 1) * w + x]
    val d = tmp[min(h - 1, y + 1) * w + x]
    out[y * w + x] = 0.25f * u + 0.5f * tmp[y * w + x] + 0.25f * d
  }
  return out
}

/** Camera angles relative to the road, read off a vanishing point. */
data class RoadAngles(
    /** Positive: the road runs to the right of where the camera points. */
    val roadRightOfCentreDeg: Double,
    /** How far the camera looks below the road's horizon. */
    val pitchDownDeg: Double,
)

/**
 * Converts a vanishing point (fractions of an upright image of [aspect] = width / height) to
 * angles, with the same pinhole model as [ArProjector] (field of view across the long side).
 */
fun roadAngles(vp: VanishingPoint, aspect: Float, longSideFovDeg: Double): RoadAngles {
  val w = if (aspect >= 1f) 1.0 else aspect.toDouble()
  val h = if (aspect >= 1f) 1.0 / aspect else 1.0
  // Focal length in units of the long side (= 1).
  val f = 0.5 / tan(Math.toRadians(longSideFovDeg) / 2.0)
  val dx = (vp.fx - 0.5) * w
  val dy = (0.5 - vp.fy) * h
  return RoadAngles(Math.toDegrees(atan(dx / f)), Math.toDegrees(atan(dy / f)))
}

/** A growable float array without boxing. */
private class FloatList {
  private var a = FloatArray(1024)
  var size = 0
    private set

  operator fun plusAssign(v: Float) {
    if (size == a.size) a = a.copyOf(size * 2)
    a[size++] = v
  }

  operator fun get(i: Int) = a[i]
}
