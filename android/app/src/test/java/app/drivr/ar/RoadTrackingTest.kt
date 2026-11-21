package app.drivr.ar

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The camera side of AR: from a dashboard camera frame to where the phone actually points.
 *
 * Frames are rendered by ray-casting a synthetic motorway (asphalt, lane markings, verge, sky,
 * sensor noise) from a known camera pose, delivered in sensor orientation like CameraX does, and
 * the pipeline has to recover that pose.
 */
class RoadTrackingTest {

  /** Renders a 640x480 sensor frame (landscape) of a straight road running north. */
  private fun renderFrame(
      cameraHeadingDeg: Double,
      pitchDownDeg: Double,
      rotationDegrees: Int = 90,
      fovDeg: Double = 68.0,
      seed: Int = 1,
      emptyScene: Boolean = false,
  ): Triple<ByteArray, Int, Int> {
    val fw = 640
    val fh = 480
    // Upright image the driver sees (portrait when the frame needs turning by 90).
    val uw = if (rotationDegrees % 180 == 90) fh else fw
    val uh = if (rotationDegrees % 180 == 90) fw else fh
    val f = (maxOf(uw, uh) / 2.0) / tan(Math.toRadians(fovDeg) / 2.0)
    val yaw = Math.toRadians(cameraHeadingDeg)
    val pitch = Math.toRadians(pitchDownDeg)
    val fhx = sin(yaw)
    val fhy = cos(yaw)
    // forward, right, up (same construction as ArProjector, no roll)
    val fwd = doubleArrayOf(fhx * cos(pitch), fhy * cos(pitch), -sin(pitch))
    val rgt = doubleArrayOf(cos(yaw), -sin(yaw), 0.0)
    val up = doubleArrayOf(fhx * sin(pitch), fhy * sin(pitch), cos(pitch))
    val h = 1.2
    val rnd = Random(seed)
    val upright = ByteArray(uw * uh)
    for (v in 0 until uh) for (u in 0 until uw) {
      val a = (u + 0.5 - uw / 2.0) / f
      val b = (uh / 2.0 - v - 0.5) / f
      val dx = fwd[0] + a * rgt[0] + b * up[0]
      val dy = fwd[1] + a * rgt[1] + b * up[1]
      val dz = fwd[2] + a * rgt[2] + b * up[2]
      var lum: Double
      if (dz >= -1e-4) {
        lum = 205.0 - 40.0 * (v.toDouble() / uh) // sky
      } else {
        val t = h / -dz
        val x = dx * t
        val y = dy * t
        lum =
            when {
              emptyScene -> 110.0
              y > 2000 -> 140.0
              x < -9.0 || x > 3.5 -> 120.0 + 25.0 * sin(x * 1.7) * cos(y * 0.9) // verge
              else -> {
                fun marking(cx: Double, dashed: Boolean): Boolean =
                    abs(x - cx) < 0.075 && (!dashed || floor(y / 12.0).toInt() % 2 == 0)
                if (marking(1.75, false) || marking(-1.75, true) || marking(-5.25, true) || marking(-8.6, false)) 225.0
                else 85.0 // asphalt
              }
            }
        // Bonnet across the bottom of the frame.
        if (v > uh * 0.9) lum = 30.0
      }
      lum += rnd.nextDouble(-8.0, 8.0)
      upright[v * uw + u] = lum.coerceIn(0.0, 255.0).toInt().toByte()
    }
    // Back into sensor orientation: upright = frame turned clockwise by rotationDegrees.
    val frame = ByteArray(fw * fh)
    for (v in 0 until uh) for (u in 0 until uw) {
      val (x, y) = uprightToFrame(u, v, fw, fh, rotationDegrees)
      frame[y * fw + x] = upright[v * uw + u]
    }
    return Triple(frame, fw, fh)
  }

  private fun measure(cameraHeadingDeg: Double, pitchDownDeg: Double, rotation: Int = 90, seed: Int = 1): RoadAngles? {
    val (frame, w, h) = renderFrame(cameraHeadingDeg, pitchDownDeg, rotation, seed = seed)
    val img = uprightLuma(frame, w, h, w, rotation)
    val vp = VanishingPointEstimator().estimate(img) ?: return null
    assertTrue("confident on a clear road (was ${vp.confidence})", vp.confidence > 0.35f)
    return roadAngles(vp, img.width.toFloat() / img.height, 68.0)
  }

  @Test
  fun upright_frames_have_the_right_shape() {
    val (frame, w, h) = renderFrame(0.0, 8.0, rotationDegrees = 90)
    val img = uprightLuma(frame, w, h, w, 90)
    assertTrue("portrait after turning", img.height > img.width)
    // Sky at the top, bonnet at the bottom.
    assertTrue(img[img.width / 2, 2] > 150f)
    assertTrue(img[img.width / 2, img.height - 2] < 60f)
  }

  @Test
  fun finds_where_the_road_goes_with_a_straight_mount() {
    val a = measure(cameraHeadingDeg = 0.0, pitchDownDeg = 6.0)!!
    assertEquals(0.0, a.roadRightOfCentreDeg, 0.8)
    assertEquals(6.0, a.pitchDownDeg, 0.8)
  }

  @Test
  fun sees_a_phone_turned_to_the_right_and_tilted_down() {
    // Phone turned 5° right of the car and looking 9° down: the road vanishes left of centre and
    // above it.
    val a = measure(cameraHeadingDeg = 5.0, pitchDownDeg = 9.0)!!
    assertEquals(-5.0, a.roadRightOfCentreDeg, 0.8)
    assertEquals(9.0, a.pitchDownDeg, 0.8)
  }

  @Test
  fun works_in_every_frame_orientation() {
    for (rot in listOf(0, 90, 180, 270)) {
      val a = measure(cameraHeadingDeg = -3.0, pitchDownDeg = 7.0, rotation = rot)
      assertNotNull("rotation $rot", a)
      assertEquals("yaw at rotation $rot", 3.0, a!!.roadRightOfCentreDeg, 1.0)
      assertEquals("pitch at rotation $rot", 7.0, a.pitchDownDeg, 1.0)
    }
  }

  @Test
  fun a_frame_without_a_road_gives_nothing_to_go_on() {
    val (frame, w, h) = renderFrame(0.0, 6.0, emptyScene = true)
    val vp = VanishingPointEstimator().estimate(uprightLuma(frame, w, h, w, 90))
    assertTrue(vp == null || vp.confidence < 0.35f)
  }

  @Test
  fun the_mount_is_learned_from_frames_and_corrects_gps_and_gravity() {
    // True mount: phone 4° to the right, camera 8° down. Gravity reads 6.5° (bias), GPS says the
    // car heads due north.
    var now = 1_000_000_000L
    val engine = ArPoseEngine(clockNanos = { now })
    engine.onTilt(Tilt(6.5, 0.0))
    engine.onFix(0.0, 25.0)
    engine.routeStraightAhead = true
    for (i in 0 until 12) {
      now += 1_000_000_000L
      val (frame, w, h) = renderFrame(cameraHeadingDeg = 4.0, pitchDownDeg = 8.0, seed = i)
      val img = uprightLuma(frame, w, h, w, 90)
      val vp = VanishingPointEstimator().estimate(img)!!
      engine.onVanishingPoint(vp, img.width.toFloat() / img.height)
    }
    val m = engine.mountState()
    assertTrue("locked after a dozen frames", m.locked)
    val pose = engine.pose(0.0)
    assertEquals(4.0, angleDiffDeg(pose.headingDeg, 0.0), 0.6)
    assertEquals(8.0, pose.pitchDownDeg, 0.6)
  }

  @Test
  fun the_mount_is_not_learned_in_a_bend() {
    val engine = ArPoseEngine()
    engine.onFix(0.0, 25.0)
    engine.routeStraightAhead = false
    val (frame, w, h) = renderFrame(4.0, 8.0)
    val img = uprightLuma(frame, w, h, w, 90)
    assertFalse(engine.onVanishingPoint(VanishingPointEstimator().estimate(img)!!, img.width.toFloat() / img.height))
    assertEquals(0, engine.mountState().samples)
  }

  @Test
  fun calibrator_ignores_outliers_once_settled() {
    val c = MountCalibrator()
    val rnd = Random(3)
    repeat(30) {
      c.onMeasurement(RoadAngles(-3.0 + rnd.nextDouble(-0.7, 0.7), 7.0 + rnd.nextDouble(-0.7, 0.7)), 6.0, 0.8f, true)
    }
    // A lorry's edge or a merging lane: way off. Must not move the estimate.
    repeat(5) { c.onMeasurement(RoadAngles(12.0, 2.0), 6.0, 0.9f, true) }
    assertEquals(3.0, c.state.yawOffsetDeg, 0.4)
    assertEquals(1.0, c.state.pitchBiasDeg, 0.4)
  }

  @Test
  fun gyro_carries_heading_between_fixes_and_gps_removes_drift() {
    val f = HeadingFuser()
    f.onCourse(90.0, 20.0)
    f.onGyro(10.0, 0.0) // first call only marks the gyro as present
    // One second of a 10°/s right-hand curve, at 100 Hz.
    repeat(100) { f.onGyro(10.0, 0.01) }
    assertEquals(100.0, f.headingDeg!!, 0.01)
    // A gyro that drifts 1°/s while the GPS says 90°: GPS pulls it back.
    repeat(20) {
      repeat(100) { f.onGyro(1.0, 0.01) }
      f.onCourse(90.0, 20.0)
    }
    assertEquals(90.0, f.headingDeg!!, 4.5)
  }

  @Test
  fun heading_rate_from_gyro_whatever_way_the_phone_is_mounted() {
    // Portrait on the dash: device +y is up. Turning right (clockwise from above) is a negative
    // rotation about up.
    assertEquals(10.0, headingRateDegPerS(0f, 9.81f, 0f, 0f, Math.toRadians(-10.0).toFloat(), 0f), 1e-3)
    // Landscape, device +x up.
    assertEquals(-5.0, headingRateDegPerS(9.81f, 0f, 0f, Math.toRadians(5.0).toFloat(), 0f, 0f), 1e-3)
    // Rotation about the camera axis (roll) is not a heading change.
    assertEquals(0.0, headingRateDegPerS(0f, 9.81f, 0f, 0f, 0f, 1f), 1e-6)
  }

  @Test
  fun angle_differences_wrap() {
    assertEquals(20.0, angleDiffDeg(10.0, 350.0), 1e-9)
    assertEquals(-20.0, angleDiffDeg(350.0, 10.0), 1e-9)
    assertEquals(180.0, abs(angleDiffDeg(0.0, 180.0)), 1e-9)
  }

  @Test
  fun straight_and_bending_routes() {
    val straight = (0..60).map { Enu(0.0, it * 2.0) }
    assertTrue(isStraight(straight))
    val bend = (0..60).map { val a = it * 0.02; Enu(60 * (1 - cos(a)), 60 * sin(a)) }
    assertFalse(isStraight(bend))
    assertFalse("too short to tell", isStraight(straight.take(10)))
  }

  @Test
  fun dead_reckoning_moves_the_car_along_the_route() {
    val route = listOf(Enu(0.0, 0.0), Enu(0.0, 50.0), Enu(50.0, 50.0))
    val moved = reOrigin(sliceAlong(route, 60.0))
    assertEquals(Enu(0.0, 0.0), moved.first())
    // After the corner, heading east: the rest of the route runs along +x.
    assertEquals(40.0, moved.last().x, 1e-9)
    assertEquals(0.0, moved.last().y, 1e-9)
    assertEquals(100.0, lengthM(route), 1e-9)
    val (p, d) = pointAndDirectionAt(route, 75.0)!!
    assertEquals(25.0, p.x, 1e-9)
    assertEquals(1.0, d.x, 1e-9)
    assertNull(pointAndDirectionAt(route, 101.0))
  }

  // ---- From the drive with the phone on the windscreen --------------------------------------

  private class FakeClock(var nanos: Long = 1_000_000_000L) {
    fun advance(s: Double) {
      nanos += (s * 1e9).toLong()
    }
  }

  @Test
  fun standing_in_a_queue_the_route_sets_the_heading_not_the_gps_course() {
    val clock = FakeClock()
    val engine = ArPoseEngine(clockNanos = { clock.nanos })
    // Standing still: the receiver reports a course of 0° (north); the street runs at 75°.
    engine.onFix(0.0, 0.2)
    val pose = engine.pose(75.0)
    assertEquals(75.0, pose.headingDeg, 0.5)
    // And it stays there while standing.
    repeat(100) {
      clock.advance(0.033)
      engine.onFix(10.0, 0.1)
      engine.pose(75.0)
    }
    assertEquals(75.0, engine.pose(75.0).headingDeg, 0.5)
  }

  @Test
  fun round_a_corner_the_gyro_leads_and_the_route_catches_up_after() {
    val clock = FakeClock()
    val engine = ArPoseEngine(clockNanos = { clock.nanos })
    engine.pose(0.0)
    // A right turn: 90° in 4 s, the route's direction (first metres ahead) running a little ahead.
    for (i in 0 until 120) {
      clock.advance(1.0 / 30)
      engine.onGyro(22.5, 1.0 / 30)
      val t = (i + 1) / 30.0
      engine.pose(minOf(90.0, 22.5 * t + 12.0))
    }
    // The route's corner runs a little ahead of the car's arc; never more than that.
    assertEquals(90.0, engine.pose(90.0).headingDeg, 12.0)
    // Straight on: settles on the road within a second or two.
    repeat(60) {
      clock.advance(1.0 / 30)
      engine.onGyro(0.0, 1.0 / 30)
      engine.pose(90.0)
    }
    assertEquals(90.0, engine.pose(90.0).headingDeg, 1.0)
  }

  @Test
  fun braking_does_not_tip_the_overlay() {
    val clock = FakeClock()
    val engine = ArPoseEngine(clockNanos = { clock.nanos })
    engine.onTilt(Tilt(4.0, 0.0))
    repeat(30) {
      clock.advance(0.02)
      engine.onTilt(Tilt(4.0, 0.0))
    }
    // Braking at 3 m/s²: speed falls 3 m/s per second, and the gravity sensor reads 6° more down.
    var v = 20.0
    for (s in 0 until 3) {
      engine.onFix(0.0, v)
      repeat(50) {
        clock.advance(0.02)
        engine.onTilt(Tilt(10.0, 0.0))
      }
      v -= 3.0
    }
    assertEquals(4.0, engine.pose(0.0).pitchDownDeg, 1.5)
  }

  @Test
  fun moving_the_phone_is_followed_quickly() {
    val clock = FakeClock()
    val engine = ArPoseEngine(clockNanos = { clock.nanos })
    engine.onTilt(Tilt(4.0, 0.0))
    repeat(50) {
      clock.advance(0.02)
      engine.onTilt(Tilt(16.0, 0.0))
    }
    assertEquals(16.0, engine.pose(0.0).pitchDownDeg, 1.0)
  }

  @Test
  fun nothing_is_drawn_when_the_camera_cannot_see_the_road() {
    val clock = FakeClock()
    val engine = ArPoseEngine(clockNanos = { clock.nanos })
    engine.onTilt(Tilt(40.0, 0.0))
    assertEquals(ArTrack.AIM_AT_ROAD, engine.track(5.0))
    repeat(50) {
      clock.advance(0.02)
      engine.onTilt(Tilt(4.0, 0.0))
    }
    assertEquals(ArTrack.WEAK_GPS, engine.track(60.0))
    assertEquals(ArTrack.FINE_TUNING, engine.track(5.0))
    assertFalse(ArTrack.AIM_AT_ROAD.draws)
    assertTrue(ArTrack.FINE_TUNING.draws)
  }

  @Test
  fun the_drivers_alignment_replaces_the_learned_mount_until_reset() {
    val engine = ArPoseEngine()
    engine.onTilt(Tilt(5.0, 0.0))
    engine.setManual(-3.0, 1.0)
    assertEquals(ArTrack.MANUAL, engine.track(5.0))
    val p = engine.pose(90.0)
    assertEquals(87.0, p.headingDeg, 0.5)
    assertEquals(6.0, p.pitchDownDeg, 0.5)
    engine.clearManual()
    assertEquals(ArTrack.FINE_TUNING, engine.track(5.0))
    assertEquals(90.0, engine.pose(90.0).headingDeg, 0.5)
  }

  @Test
  fun turn_angle_from_the_route() {
    val right = listOf(Enu(0.0, 0.0), Enu(0.0, 50.0), Enu(40.0, 50.0))
    assertEquals(90.0, turnAngleDeg(right, 50.0), 1.0)
    val left = listOf(Enu(0.0, 0.0), Enu(0.0, 50.0), Enu(-30.0, 80.0))
    assertEquals(-45.0, turnAngleDeg(left, 50.0), 1.0)
  }
}
