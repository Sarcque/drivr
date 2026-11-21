package app.drivr.location

import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A simulated 7 km drive with realistic bad GPS: wandering noise, city multipath jumps, rough
 * Wi-Fi/cell fixes mixed in, late and dropped fixes, a 600 m tunnel and a stop at a traffic light.
 * The car's real position is known at every moment, so the drawn car can be measured against it,
 * the old way (every fix drawn as it came, once a second) and the new way (filtered, carried
 * forward, 60 fps).
 */
class LocationPipelineTest {

  // ---- The road: motorway east, a long left bend, north through a tunnel, a right turn in town. --
  private val lat0 = 52.2
  private val lon0 = 5.9
  private val frame = LocalFrame(lat0, lon0)
  private val roadXY: List<Pair<Double, Double>> = buildList {
    var x = 0.0
    var y = 0.0
    fun straight(len: Double, dirDeg: Double) {
      val r = Math.toRadians(dirDeg)
      var d = 0.0
      while (d < len) { d += 5.0; add((x + sin(r) * d) to (y + cos(r) * d)) }
      x += sin(r) * len; y += cos(r) * len
    }
    fun arc(radius: Double, fromDeg: Double, turnDeg: Double) {
      val steps = (abs(turnDeg) * radius * Math.PI / 180 / 5).toInt().coerceAtLeast(2)
      for (i in 1..steps) {
        val h = Math.toRadians(fromDeg + turnDeg * (i - 0.5) / steps)
        val step = abs(turnDeg) * Math.PI / 180 * radius / steps
        x += sin(h) * step; y += cos(h) * step
        add(x to y)
      }
    }
    add(0.0 to 0.0)
    straight(2500.0, 90.0) // motorway east
    arc(250.0, 90.0, -90.0) // long bend left, now heading north
    straight(1500.0, 0.0) // tunnel from 600 to 1200 m along this
    arc(30.0, 0.0, 90.0) // right turn in town
    straight(800.0, 90.0) // town street, traffic light half way
  }
  private val route = roadXY.map { frame.lat(it.second) to frame.lon(it.first) }
  private val track = RouteTrack(route)
  private val bendEnd = 2500.0 + 250.0 * Math.PI / 2
  private val tunnel = (bendEnd + 600.0)..(bendEnd + 1200.0)
  private val turnAt = bendEnd + 1500.0
  private val lightAt = turnAt + 30.0 * Math.PI / 2 + 400.0

  private fun targetSpeed(s: Double) =
      when {
        s < 2400 -> 30.0
        s < bendEnd -> 22.0
        s < turnAt - 120 -> 25.0
        s < turnAt + 60 -> 7.0
        else -> 13.0
      }

  /** The car's true progress every 10 ms: accelerates/brakes at ≤ 2.5 m/s², waits 12 s at the light. */
  private data class Truth(val tMs: Long, val s: Double, val v: Double)

  private val truth: List<Truth> = buildList {
    var s = 0.0
    var v = 20.0
    var t = 0L
    var waitedMs = 0L
    while (s < track.lengthM - 20) {
      val dt = 0.01
      var target = targetSpeed(s + v * 2) // brake ahead of the next section
      if (s < lightAt && lightAt - s < v * v / 3.0 + 3 && waitedMs < 12_000) target = 0.0
      if (abs(lightAt - s) < 3 && v < 0.2 && waitedMs < 12_000) { waitedMs += 10; target = 0.0; v = 0.0 }
      v += (target - v).coerceIn(-2.5 * dt, 2.5 * dt)
      s += v * dt
      t += 10
      add(Truth(t, s, v))
    }
  }

  private fun truthAt(tMs: Long): Truth = truth[((tMs / 10).toInt() - 1).coerceIn(0, truth.lastIndex)]

  /** Bad-but-typical phone GPS, once a second. Times are when the fix reaches the app. */
  private data class SimFix(val arriveMs: Long, val fix: Fix)

  private fun gpsTrace(seed: Int): List<SimFix> {
    val rnd = Random(seed)
    fun gauss() = sqrt(-2 * kotlin.math.ln(rnd.nextDouble(1e-9, 1.0))) * cos(2 * Math.PI * rnd.nextDouble())
    val out = mutableListOf<SimFix>()
    var nx = 0.0
    var ny = 0.0
    var t = 1000L
    val end = truth.last().tMs
    while (t < end) {
      val tr = truthAt(t)
      // Wandering error (GPS error is correlated over seconds, not white).
      nx = 0.85 * nx + gauss() * 3.0
      ny = 0.85 * ny + gauss() * 3.0
      val inTunnel = tr.s in tunnel
      val dropped = rnd.nextDouble() < 0.06
      if (!inTunnel && !dropped) {
        val (la, lo) = track.positionAt(tr.s)
        var ex = nx
        var ey = ny
        var acc = 6.0 + rnd.nextDouble() * 4
        if (rnd.nextDouble() < 0.04) { // multipath: a fix 80–400 m off that claims to be fine
          val a = rnd.nextDouble() * 2 * Math.PI
          val d = 80 + rnd.nextDouble() * 320
          ex += sin(a) * d; ey += cos(a) * d
        }
        val f = LocalFrame(la, lo)
        val course = track.bearingAt(tr.s, 0.0, 1.0)
        val bearing = if (tr.v > 2) course + gauss() * 3 else rnd.nextDouble() * 360
        // Fix arrives 150–450 ms after it was taken.
        val lag = 150L + rnd.nextLong(300)
        out += SimFix(t + lag, Fix(f.lat(ey), f.lon(ex), acc, max(0.0, tr.v + gauss() * 0.3), normDeg(bearing), t))
        // Now and then a rough Wi-Fi/cell fix in between.
        if (rnd.nextDouble() < 0.1) {
          val a = rnd.nextDouble() * 2 * Math.PI
          out += SimFix(t + lag + 400, Fix(f.lat(cos(a) * 120), f.lon(sin(a) * 120), 150.0, null, null, t + 400))
        }
      }
      t += 1000
    }
    return out.sortedBy { it.arriveMs }
  }

  private data class Frame(val tMs: Long, val lat: Double, val lon: Double, val bearing: Double?)

  /** Before: Ferrostar snapped each raw fix to the route and Drivr drew it as it came (bearing from the fix, north without one). */
  private fun before(fixes: List<SimFix>): List<Frame> {
    val frames = mutableListOf<Frame>()
    var shown: Frame? = null
    var i = 0
    var t = 1000L
    while (t < truth.last().tMs) {
      while (i < fixes.size && fixes[i].arriveMs <= t) {
        val f = fixes[i].fix
        val p = track.project(f.lat, f.lon)
        // Ferrostar snaps only when close to the route; otherwise the raw fix is shown.
        val (la, lo) = if (p.distanceM < 50) track.positionAt(p.progressM) else f.lat to f.lon
        shown = Frame(t, la, lo, f.bearingDeg ?: 0.0)
        i++
      }
      shown?.let { frames += it.copy(tMs = t) }
      t += 16
    }
    return frames
  }

  /** After: filter + tunnel bridge in the provider, Ferrostar's snap, then the 60 fps animator. */
  private fun after(fixes: List<SimFix>): List<Frame> {
    val filter = FixFilter()
    val bridge = TunnelBridge().apply { setRoute(route) }
    val anim = DisplayAnimator().apply { setRoute(route) }
    val frames = mutableListOf<Frame>()
    var i = 0
    var t = 1000L
    var nextTick = 2000L
    var lastProgress: Double? = null
    fun deliver(f: Fix) {
      val out = if (f.predicted) f else filter.accept(f) ?: return
      if (!f.predicted) bridge.onRealFix(out)
      val p = track.project(out.lat, out.lon, near = lastProgress)
      lastProgress = p.progressM
      val onRoute = p.distanceM < 50
      val (la, lo) = if (onRoute) track.positionAt(p.progressM) else out.lat to out.lon
      anim.onFix(la, lo, out.speedMps, out.bearingDeg, t, onRoute)
    }
    while (t < truth.last().tMs) {
      while (i < fixes.size && fixes[i].arriveMs <= t) deliver(fixes[i++].fix)
      if (t >= nextTick) { bridge.predict(t)?.let(::deliver); nextTick += 1000 }
      anim.frame(t)?.let { frames += Frame(t, it.lat, it.lon, it.bearingDeg) }
      t += 16
    }
    return frames
  }

  private data class Stats(
      val meanM: Double, val p95M: Double, val maxM: Double,
      val maxFrameJumpM: Double, val wildFrames: Int, val headingP95: Double, val tunnelExitM: Double,
  )

  private fun stats(frames: List<Frame>): Stats {
    val errs = frames.map { fr ->
      val (la, lo) = track.positionAt(truthAt(fr.tMs).s)
      distanceM(la, lo, fr.lat, fr.lon)
    }
    // Jump beyond what the car itself moved in that frame.
    var maxJump = 0.0
    for (k in 1 until frames.size) {
      val a = frames[k - 1]; val b = frames[k]
      val moved = abs(truthAt(b.tMs).s - truthAt(a.tMs).s)
      maxJump = max(maxJump, distanceM(a.lat, a.lon, b.lat, b.lon) - moved)
    }
    val headingErrs = frames.filter { truthAt(it.tMs).v > 3 }.map { fr ->
      abs(angleDelta(fr.bearing ?: 0.0, track.bearingAt(truthAt(fr.tMs).s, 0.0, 1.0)))
    }.sorted()
    val exit = frames.first { truthAt(it.tMs).s > tunnel.endInclusive }
    val (la, lo) = track.positionAt(truthAt(exit.tMs).s)
    val sorted = errs.sorted()
    return Stats(
        errs.average(), sorted[(sorted.size * 0.95).toInt()], sorted.last(),
        maxJump, errs.count { it > 50 }, headingErrs[(headingErrs.size * 0.95).toInt()],
        distanceM(la, lo, exit.lat, exit.lon),
    )
  }

  @Test
  fun `noisy drive - the drawn car stays on the real car, smoothly`() {
    val seeds = 1..5
    val b = seeds.map { stats(before(gpsTrace(it))) }
    val a = seeds.map { stats(after(gpsTrace(it))) }
    fun List<Stats>.avg(f: (Stats) -> Double) = map(f).average()
    val report = buildString {
      appendLine("metric                      before    after")
      appendLine("mean error (m)            %8.1f %8.1f".format(b.avg { it.meanM }, a.avg { it.meanM }))
      appendLine("95%% error (m)             %8.1f %8.1f".format(b.avg { it.p95M }, a.avg { it.p95M }))
      appendLine("worst error (m)           %8.1f %8.1f".format(b.avg { it.maxM }, a.avg { it.maxM }))
      appendLine("worst jump in 1 frame (m) %8.1f %8.1f".format(b.avg { it.maxFrameJumpM }, a.avg { it.maxFrameJumpM }))
      appendLine("frames > 50 m off         %8.0f %8.0f".format(b.avg { it.wildFrames.toDouble() }, a.avg { it.wildFrames.toDouble() }))
      appendLine("95%% heading error (deg)   %8.1f %8.1f".format(b.avg { it.headingP95 }, a.avg { it.headingP95 }))
      appendLine("off at tunnel exit (m)    %8.1f %8.1f".format(b.avg { it.tunnelExitM }, a.avg { it.tunnelExitM }))
    }
    println(report)
    File("build/location-sim").mkdirs()
    File("build/location-sim/report.txt").writeText(report)
    writeCsv(gpsTrace(1), before(gpsTrace(1)), after(gpsTrace(1)))

    assertTrue("mean error", a.avg { it.meanM } < b.avg { it.meanM } * 0.6)
    assertTrue("95% error", a.avg { it.p95M } < 20.0)
    assertTrue("never jumps more than a few metres in a frame", a.maxOf { it.maxFrameJumpM } < 4.0)
    assertEquals("multipath fixes never shown", 0, a.sumOf { it.wildFrames })
    assertTrue("heading follows the road", a.avg { it.headingP95 } < 8.0)
    assertTrue("comes out of the tunnel where the car is", a.avg { it.tunnelExitM } < 40.0)
  }

  private fun writeCsv(fixes: List<SimFix>, before: List<Frame>, after: List<Frame>) {
    val sb = StringBuilder("kind,t,x,y,bearing\n")
    fun row(kind: String, t: Long, lat: Double, lon: Double, b: Double?) =
        sb.append("$kind,$t,${"%.2f".format(frame.x(lon))},${"%.2f".format(frame.y(lat))},${b ?: ""}\n")
    for (f in fixes) row("gps", f.arriveMs, f.fix.lat, f.fix.lon, f.fix.bearingDeg)
    for (f in before) row("before", f.tMs, f.lat, f.lon, f.bearing)
    for (f in after) row("after", f.tMs, f.lat, f.lon, f.bearing)
    var t = 0L
    while (t < truth.last().tMs) { val (la, lo) = track.positionAt(truthAt(t).s); row("truth", t, la, lo, null); t += 16 }
    roadXY.forEach { sb.append("road,0,${"%.2f".format(it.first)},${"%.2f".format(it.second)},\n") }
    File("build/location-sim/trace.csv").writeText(sb.toString())
  }

  // ---- Small, specific behaviours ----------------------------------------------------------------

  private fun fixAt(eastM: Double, t: Long, acc: Double = 5.0, speed: Double? = 20.0, bearing: Double? = 90.0) =
      Fix(frame.lat(0.0), frame.lon(eastM), acc, speed, bearing, t)

  @Test
  fun `drops out of order and duplicate fixes`() {
    val f = FixFilter()
    assertNotNull(f.accept(fixAt(0.0, 1000)))
    assertNull(f.accept(fixAt(20.0, 1000)))
    assertNull(f.accept(fixAt(-20.0, 500)))
  }

  @Test
  fun `ignores rough fixes while gps is good, uses them when it isn't`() {
    val f = FixFilter()
    f.accept(fixAt(0.0, 1000))
    assertNull(f.accept(fixAt(300.0, 2000, acc = 150.0)))
    val fresh = FixFilter()
    assertNotNull(fresh.accept(fixAt(300.0, 2000, acc = 150.0)))
  }

  @Test
  fun `a run of far-away fixes wins in the end - never stuck on the wrong spot`() {
    val f = FixFilter()
    for (i in 0..5) f.accept(fixAt(i * 20.0, 1000L + i * 1000))
    val results = (1..5).map { f.accept(fixAt(5000.0 + it * 20, 7000L + it * 1000)) }
    assertNull(results[0])
    assertNotNull(results.last())
    assertTrue(abs(frame.x(results.last()!!.lon) - 5100.0) < 5)
  }

  @Test
  fun `keeps the heading while standing at the lights`() {
    val f = FixFilter()
    for (i in 0..5) f.accept(fixAt(i * 15.0, 1000L + i * 1000, speed = 15.0, bearing = 90.0))
    val stopped = (1..5).map { f.accept(fixAt(80.0, 6000L + it * 1000, speed = 0.1, bearing = it * 70.0))!! }
    stopped.forEach { assertEquals(90.0, it.bearingDeg!!, 5.0) }
  }

  @Test
  fun `tunnel - carries on along the route, stops after real fixes return`() {
    val b = TunnelBridge().apply { setRoute(route) }
    val (la, lo) = track.positionAt(1000.0)
    b.onRealFix(Fix(la, lo, 5.0, 25.0, 90.0, 10_000))
    assertNull(b.predict(11_000))
    val p = b.predict(20_000)!!
    val progress = track.project(p.lat, p.lon).progressM
    assertEquals(1250.0, progress, 2.0)
    assertTrue(p.predicted)
    val (la2, lo2) = track.positionAt(1260.0)
    b.onRealFix(Fix(la2, lo2, 5.0, 25.0, 90.0, 20_500))
    assertNull(b.predict(21_000))
  }

  @Test
  fun `display - moves every frame between fixes and never backwards for small corrections`() {
    val anim = DisplayAnimator().apply { setRoute(route) }
    anim.onFix(route[0].first, route[0].second, 20.0, 90.0, 0, true)
    var lastX = -1.0
    var t = 0L
    var sent = false
    while (t < 3000) {
      if (t >= 1000L && !sent) {
        sent = true
        val (la, lo) = track.positionAt(15.0) // fix says a bit behind where we've drawn
        anim.onFix(la, lo, 20.0, 90.0, t, true)
      }
      val p = anim.frame(t)!!
      val x = frame.x(p.lon)
      assertTrue("moved backwards at $t: $x < $lastX", x >= lastX - 1e-6)
      lastX = x
      t += 16
    }
    assertTrue("got $lastX", lastX > 40)
  }

  @Test
  fun `display - browsing without a route glides to the new fix`() {
    val anim = DisplayAnimator()
    anim.onFix(frame.lat(0.0), frame.lon(0.0), 0.0, null, 0, false)
    anim.frame(1000)
    anim.onFix(frame.lat(0.0), frame.lon(30.0), 0.0, null, 1000, false)
    val first = frame.x(anim.frame(1016)!!.lon)
    assertTrue(first in 0.1..5.0)
    var t = 1016L
    while (t < 4000) { anim.frame(t); t += 16 }
    assertEquals(30.0, frame.x(anim.frame(4000)!!.lon), 0.5)
  }
}
