package app.drivr.ar

import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole AR pipeline on a simulated drive (see [DriveWorld]): sensors, GPS, camera frames,
 * pose fusion, vanishing point, lane lines, dead reckoning and the carpet geometry, measured
 * against the truth. The number that matters: where the carpet's centre lands on the real road,
 * 15 m and 40 m ahead, relative to the centre of the lane the car is in.
 *
 * Three pipelines run on exactly the same inputs:
 * - "before": the AR as it was until 21 Nov (heading from GPS course, no route anchor);
 * - "PR 14": route-anchored heading, steadier tilt, no lane lines, no latency compensation;
 * - "now": plus lane-line lock, fix-age and camera-latency compensation, rounded corners.
 */
class ArDriveSimulationTest {
  private val w = 480
  private val h = 640
  private val fov = 68.0
  private val dt = 1.0 / 30

  internal interface Pipe {
    val name: String
    val usesFixAge: Boolean
    val fillet: Boolean

    fun tilt(t: Tilt)

    fun gyro(rate: Double, dt: Double)

    fun fix(course: Double?, speed: Double, straight: Boolean)

    fun frame(img: LumaImage)

    fun pose(routeHeading: Double): CameraPose

    fun shift(): Double

    fun draws(): Boolean
  }

  internal class Legacy : Pipe {
    override val name = "before"
    override val usesFixAge = false
    override val fillet = false
    private val e = app.drivr.ar.legacy.ArPoseEngine()
    private val vp = app.drivr.ar.legacy.VanishingPointEstimator()

    override fun tilt(t: Tilt) = e.onTilt(t)

    override fun gyro(rate: Double, dt: Double) = e.onGyro(rate, dt)

    override fun fix(course: Double?, speed: Double, straight: Boolean) {
      e.onFix(course, speed)
      e.routeStraightAhead = straight
    }

    override fun frame(img: LumaImage) {
      val li = app.drivr.ar.legacy.LumaImage(img.width, img.height, img.pixels)
      vp.estimate(li)?.let { v ->
        e.onVanishingPoint(app.drivr.ar.legacy.VanishingPoint(v.fx, v.fy, v.confidence), img.width.toFloat() / img.height)
      }
    }

    override fun pose(routeHeading: Double): CameraPose = e.pose(routeHeading).copy(heightM = 1.2)

    override fun shift() = 0.0

    override fun draws() = true
  }

  internal inner class Current(override val name: String, laneLock: Boolean, latency: Boolean, val clock: () -> Long) : Pipe {
    override val usesFixAge = latency
    override val fillet = latency
    val e = ArPoseEngine({ fov }, clock).apply {
      laneLockEnabled = laneLock
      cameraLatencyS = if (latency) 0.09 else 0.0
    }
    private val vp = VanishingPointEstimator()
    private val lanes = LaneDetector()
    private val useLanes = laneLock
    var lastPose: CameraPose? = null
    var lastLane: LaneMeasurement? = null

    override fun tilt(t: Tilt) = e.onTilt(t)

    override fun gyro(rate: Double, dt: Double) = e.onGyro(rate, dt)

    override fun fix(course: Double?, speed: Double, straight: Boolean) {
      e.onFix(course, speed)
      e.routeStraightAhead = straight
    }

    override fun frame(img: LumaImage) {
      vp.estimate(img)?.let { e.onVanishingPoint(it, img.width.toFloat() / img.height) }
      if (!useLanes) return
      val pose = e.peekPose() ?: return
      val hd = e.routeHeading() ?: return
      val m = lanes.detect(img, pose, fov, hd, e.routeLine, e.laneWidthM())
      lastLane = m
      e.onLane(m)
    }

    override fun pose(routeHeading: Double): CameraPose = e.pose(routeHeading).also { lastPose = it }

    fun route(line: List<Enu>) {
      e.routeLine = line
    }

    override fun shift() = e.laneShiftM()

    override fun draws(): Boolean = e.track(5.0).draws
  }

  data class Stats(
      val name: String,
      val near: List<Double>,
      val far: List<Double>,
      val shown: Double,
      val vertRad: List<Double> = emptyList(),
      /** The same after the first 10 s (once moving and settled). */
      val nearLate: List<Double> = emptyList(),
      val farLate: List<Double> = emptyList(),
  ) {
    fun rms(v: List<Double>) = if (v.isEmpty()) Double.NaN else sqrt(v.sumOf { it * it } / v.size)

    fun p95(v: List<Double>) = if (v.isEmpty()) Double.NaN else v.map { abs(it) }.sorted()[(v.size * 0.95).toInt().coerceAtMost(v.size - 1)]

    override fun toString() =
        "%-8s sideways 15 m: rms %.2f m, p95 %.2f m | 40 m: rms %.2f m, p95 %.2f m | up/down rms %.2f° | shown %3.0f%%"
            .format(name, rms(near), p95(near), rms(far), p95(far), Math.toDegrees(rms(vertRad)), shown * 100) +
            "\n         after 10 s: 15 m rms %.2f m, p95 %.2f m | 40 m rms %.2f m, p95 %.2f m".format(rms(nearLate), p95(nearLate), rms(farLate), p95(farLate))
  }

  /** The state of the "now" pipeline at one moment, for drawing it over the camera picture. */
  class Snapshot(
      val world: DriveWorld,
      val camPos: Enu,
      val truthPose: CameraPose,
      val carHeadingDeg: Double,
      val believed: CameraPose,
      val line: List<Enu>,
      val maneuverAtM: Double?,
      val track: ArTrack,
      val legacyPose: CameraPose,
      val legacyLine: List<Enu>,
  )

  internal fun drive(
      weather: DriveWorld.Weather,
      seed: Int,
      debug: StringBuilder? = null,
      snapshotAt: List<Double> = emptyList(),
      onSnapshot: (Double, Snapshot) -> Unit = { _, _ -> },
  ): List<Stats> {
    val pendingSnaps = snapshotAt.sorted().toMutableList()
    val world = DriveWorld(seed)
    val states = DriveScript(world, dt).states()
    var simNanos = 1_000_000_000L
    val clock = { simNanos }
    val pipes: List<Pipe> =
        listOf(Legacy(), Current("PR 14", laneLock = false, latency = false, clock), Current("now", laneLock = true, latency = true, clock))
    val scenes = arrayOfNulls<ArScene>(pipes.size)
    val near = List(pipes.size) { ArrayList<Double>() }
    val far = List(pipes.size) { ArrayList<Double>() }
    val shown = IntArray(pipes.size)
    val vert = List(pipes.size) { ArrayList<Double>() }
    val nearLate = List(pipes.size) { ArrayList<Double>() }
    val farLate = List(pipes.size) { ArrayList<Double>() }
    val focal = (maxOf(w, h) / 2.0) / kotlin.math.tan(Math.toRadians(fov) / 2.0)
    var evaluated = 0
    val rnd = Random(seed * 31 + 1)
    // Mount: phone turned 3° right, camera 4.5° down, 1° roll; the gravity sensor reads 1° less.
    val mountYaw = 3.0
    val truePitch = 4.5
    val trueRoll = 1.0
    var tiltLp: Tilt? = null
    val pendingFixes = ArrayDeque<Triple<Double, Enu, Pair<Double?, Double>>>() // deliver time, pos, course/speed
    var nextFix = 0.0
    fun gauss() = (rnd.nextDouble() + rnd.nextDouble() + rnd.nextDouble() + rnd.nextDouble() - 2.0) * 1.73

    for ((k, st) in states.withIndex()) {
      simNanos += (dt * 1e9).toLong()
      // What's on screen is the camera picture from about 90 ms ago (3 frames): that's the truth
      // the overlay has to match.
      val shownState = states[maxOf(0, k - 3)]
      val (camPos, _) = world.lane(shownState.s)
      val truthPose = CameraPose(normalizeDeg(shownState.headingDeg + mountYaw), truePitch, trueRoll, 1.25)

      // Gravity sensor: fooled by acceleration (half of it gets through the phone's own fusion).
      val raw = Tilt(truePitch - 1.0 - 0.5 * atanDeg(st.accel / 9.81) + gauss() * 0.15, trueRoll + gauss() * 0.15)
      // The app's own low-pass in rememberDeviceTilt (0.08 at ~50 Hz ≈ 0.13 at 30 Hz).
      tiltLp = tiltLp?.let { Tilt(it.pitchDownDeg + 0.13 * (raw.pitchDownDeg - it.pitchDownDeg), it.rollDeg + 0.13 * (raw.rollDeg - it.rollDeg)) } ?: raw
      pipes.forEach { it.tilt(tiltLp!!) }
      val gyroRate = st.yawRateDegS + 0.3 + gauss() * 0.5
      pipes.forEach { it.gyro(gyroRate, dt) }
      // The phone doesn't tip: the gyro's level-axis rate is just noise.
      pipes.forEach { if (it is Current) it.e.onTiltRate(abs(gauss() * 0.3)) }

      // GPS: a fix every second, 0.6 s old when it arrives.
      if (st.t >= nextFix) {
        nextFix += 1.0
        val pos = camPos + Enu(gauss() * 2.0, gauss() * 2.0)
        val course = if (st.speed > 1.0) normalizeDeg(st.headingDeg + gauss() * 3.0) else rnd.nextDouble(0.0, 360.0)
        pendingFixes.addLast(Triple(st.t + 0.6, pos, course to (st.speed + gauss() * 0.2).coerceAtLeast(0.0)))
      }
      while (pendingFixes.isNotEmpty() && pendingFixes.first().first <= st.t) {
        val (_, pos, cs) = pendingFixes.removeFirst()
        val snapped = snapTo(world.osmRoute, pos)
        val enu = world.osmRoute.map { it - snapped }
        val toTurn = alongOf(world.osmRoute, world.osmRoute[world.osmRoute.size - 9]) - alongOf(world.osmRoute, snapped)
        for ((i, p) in pipes.withIndex()) {
          val ahead =
              if (p.fillet) routeAhead(filletCorners(enu), 400.0, 2.0)
              else routeAhead(enu, 400.0, 2.0)
          val age = if (p.usesFixAge) 600_000_000L else 0L
          scenes[i] = ArScene(ahead, toTurn, cs.second, bearingDeg(ahead.first(), ahead[minOf(5, ahead.lastIndex)]), simNanos - age)
          p.fix(cs.first, cs.second, isStraight(ahead))
        }
      }
      if (scenes.any { it == null }) continue

      val lines = ArrayList<List<Enu>>(pipes.size)
      val poses = ArrayList<CameraPose>(pipes.size)
      for ((i, p) in pipes.withIndex()) {
        val (line, _) = scenes[i]!!.at(if (p.usesFixAge) simNanos - 90_000_000L else simNanos)
        val rh =
            if (p is Legacy || p.name == "PR 14") pointAndDirectionAt(line, 8.0)?.let { bearingDeg(line.first(), it.first) } ?: scenes[i]!!.routeHeadingDeg
            else routeHeadingAtCar(line, scenes[i]!!.routeHeadingDeg)
        if (p is Current) p.route(line)
        poses += p.pose(rh)
        lines += shiftSideways(line, rh, p.shift())
      }

      if (pendingSnaps.isNotEmpty() && st.t >= pendingSnaps.first()) {
        val at = pendingSnaps.removeAt(0)
        val (_, man) = scenes[2]!!.at(simNanos - 90_000_000L)
        onSnapshot(at, Snapshot(world, camPos, truthPose, shownState.headingDeg, poses[2], lines[2], man, (pipes[2] as Current).e.track(5.0), poses[0], lines[0]))
      }

      if (k % 6 == 0) {
        val img = world.render(camPos, truthPose, w, h, fov, weather, seed * 1000 + k, st.headingDeg)
        pipes.forEach { it.frame(img) }
      }

      if (k % 3 == 0) {
        evaluated++
        for ((i, p) in pipes.withIndex()) {
          if (!p.draws()) continue
          val proj = ArProjector(poses[i], w.toFloat(), h.toFloat(), fov)
          // The same check the app makes before drawing: is the route in front of the camera?
          val probe = pointAndDirectionAt(lines[i], 15.0)?.first?.let { proj.project(it, 1.0) }
          if (p !is Legacy && (probe == null || probe.x !in -0.25f * w..1.25f * w || probe.y !in 0.2f * h..1.4f * h)) continue
          shown[i]++
          val truthProj = ArProjector(truthPose, w.toFloat(), h.toFloat(), fov)
          for ((d, out) in listOf(15.0 to near[i], 40.0 to far[i])) {
            // Where the carpet's centre is drawn, against where the lane centre really is on screen,
            // at the same distance; the sideways gap in metres at that distance.
            val b = pointAndDirectionAt(lines[i], d)?.first ?: continue
            if (world.toRoad(camPos).first + d > world.junctionS - 5.0) continue
            val (lanePt, _) = world.lane(world.toRoad(camPos).first + d)
            val t = truthProj.project(lanePt - camPos, 1.0) ?: continue
            val sp = proj.project(b, 1.0)
            if (sp == null) {
              out += 10.0
              continue
            }
            val e = ((sp.x - t.x) / truthProj.pixelsPerMetreAt(t.depth)).toDouble().coerceIn(-10.0, 10.0)
            out += e
            if (st.t > 10.0) (if (d == 15.0) nearLate[i] else farLate[i]) += e
            if (d == 15.0) vert[i] += ((sp.y - t.y) / focal)
          }
          if (debug != null && p.name == "now" && k % 9 == 0) {
            val cur = p as Current
            debug.append(
                "t=%.1f v=%.1f hdgErr=%.2f pitchErr=%.2f shift=%.2f laneQ=%.2f mount=%s lat15=%.2f lat40=%.2f laneYaw=%.2f meas=%s\n".format(
                    st.t, st.speed, angleDiffDeg(poses[i].headingDeg, truthPose.headingDeg), poses[i].pitchDownDeg - truthPose.pitchDownDeg,
                    cur.e.laneShiftM(), cur.e.laneQuality(), cur.e.mountState().samples, near[i].lastOrNull() ?: 0.0, far[i].lastOrNull() ?: 0.0, cur.e.laneCorrections().first,
                    cur.lastLane?.let { "c=%.2f a=%.2f w=%.2f q=%.2f p=%s".format(it.centreM, it.angleDeg, it.widthM, it.confidence, it.pitchErrorDeg?.let { v -> "%.2f".format(v) }) }))
          }
        }
      }
    }
    return pipes.indices.map { Stats(pipes[it].name, near[it], far[it], shown[it].toDouble() / evaluated, vert[it], nearLate[it], farLate[it]) }
  }

  @Test
  fun carpet_stays_in_the_lane_on_a_simulated_drive() {
    val report = StringBuilder()
    val results = LinkedHashMap<String, List<Stats>>()
    for ((label, weather) in
        listOf(
            "clear" to DriveWorld.Weather(),
            "rain on the windscreen" to DriveWorld.Weather(rain = true),
            "low sun + car ahead" to DriveWorld.Weather(sun = true, carAhead = true),
        )) {
      val dbg = StringBuilder()
      val stats = drive(weather, seed = 7, debug = dbg)
      File("build/drive-sim-${label.replace(' ', '-')}.log").writeText(dbg.toString())
      results[label] = stats
      report.append("== $label\n")
      stats.forEach { report.append(it).append('\n') }
    }
    println(report)
    File("build/drive-sim.txt").writeText(report.toString())
    for ((label, stats) in results) {
      val before = stats[0]
      val now = stats[2]
      assertTrue("$label: now beats before at 40 m", now.rms(now.far) < before.rms(before.far))
      assertTrue("$label: within half a metre at 15 m (p95 ${now.p95(now.near)})", now.p95(now.near) < 0.5)
      assertTrue("$label: within a lane at 40 m (p95 ${now.p95(now.far)})", now.p95(now.far) < 1.6)
      assertTrue("$label: shown most of the time (${now.shown})", now.shown > 0.85)
    }
  }
}
