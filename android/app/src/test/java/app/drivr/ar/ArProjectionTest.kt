package app.drivr.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArProjectionTest {
  init {
    app.drivr.i18n.TestStrings.install()
  }

  private val w = 1080f
  private val h = 2400f

  @Test
  fun road_straight_ahead_is_centred_and_below_the_horizon() {
    val p = ArProjector(CameraPose(headingDeg = 0.0, pitchDownDeg = 0.0), w, h)
    val near = p.project(Enu(0.0, 10.0))!!
    val far = p.project(Enu(0.0, 100.0))!!
    assertEquals(w / 2, near.x, 0.5f)
    assertTrue("ground is below centre", near.y > h / 2)
    assertTrue("further road is closer to the horizon", far.y < near.y)
    assertTrue(far.y > h / 2)
  }

  @Test
  fun things_on_the_right_appear_on_the_right() {
    val p = ArProjector(CameraPose(headingDeg = 90.0, pitchDownDeg = 5.0), w, h) // facing east
    val southOfRoute = p.project(Enu(30.0, -5.0))!! // ahead, to the right when facing east
    assertTrue(southOfRoute.x > w / 2)
  }

  @Test
  fun points_behind_the_camera_are_dropped() {
    val p = ArProjector(CameraPose(headingDeg = 0.0, pitchDownDeg = 10.0), w, h)
    assertNull(p.project(Enu(0.0, -20.0)))
  }

  @Test
  fun looking_down_moves_the_road_up_the_screen() {
    val level = ArProjector(CameraPose(0.0, 0.0), w, h).project(Enu(0.0, 20.0))!!
    val down = ArProjector(CameraPose(0.0, 10.0), w, h).project(Enu(0.0, 20.0))!!
    assertTrue(down.y < level.y)
  }

  @Test
  fun sign_above_the_road_projects_above_it() {
    val p = ArProjector(CameraPose(0.0, 5.0), w, h)
    val road = p.project(Enu(0.0, 50.0))!!
    val sign = p.project(Enu(0.0, 50.0, 4.0))!!
    assertTrue(sign.y < road.y)
  }

  @Test
  fun route_ahead_starts_at_the_car_and_runs_forward() {
    val route = listOf(Enu(0.0, -50.0), Enu(0.0, 50.0), Enu(100.0, 50.0))
    val ahead = routeAhead(route, aheadM = 120.0, stepM = 2.0)
    assertEquals(0.0, ahead.first().y, 1e-6)
    assertTrue(ahead.all { it.y >= -1e-6 })
    assertTrue("goes round the corner", ahead.last().x > 50.0)
  }

  @Test
  fun bearings_and_local_coordinates() {
    assertEquals(90.0, bearingDeg(Enu(0.0, 0.0), Enu(10.0, 0.0)), 1e-9)
    val origin = LatLng(52.09, 5.12)
    val north = LatLng(52.091, 5.12).toEnu(origin)
    assertEquals(111.2, north.y, 0.5)
  }
}
