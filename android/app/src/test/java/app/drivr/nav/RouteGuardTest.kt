package app.drivr.nav

import app.drivr.ar.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteGuardTest {
  // A road running due north from Utrecht, 2 km long, and a car on it.
  private val start = LatLng(52.09, 5.12)
  private val route = (0..20).map { LatLng(start.lat + it * 0.0009, start.lng) } // ~100 m steps
  private val mPerDegLat = 111_195.0
  private val mPerDegLng = 111_195.0 * Math.cos(Math.toRadians(52.09))

  private fun at(northM: Double, eastM: Double = 0.0) =
      LatLng(start.lat + northM / mPerDegLat, start.lng + eastM / mPerDegLng)

  private fun fix(northM: Double, course: Double, t: Long, eastM: Double = 0.0, speed: Double = 14.0) =
      RawFix(at(northM, eastM), course, speed, accuracyM = 5.0, timeMs = t)

  @Test
  fun driving_along_the_route_is_fine() {
    val g = RouteGuard()
    for (i in 0..10) g.update(fix(500.0 + i * 14, 2.0, i * 1000L, eastM = 3.0), route)
    assertEquals(RouteStanding.OnRoute, g.standing)
  }

  @Test
  fun driving_against_the_route_is_wrong_way_after_a_few_seconds() {
    val g = RouteGuard()
    g.update(fix(800.0, 180.0, 0), route)
    g.update(fix(786.0, 181.0, 1000), route)
    assertEquals("one or two fixes could be a glitch", RouteStanding.OnRoute, g.standing)
    g.update(fix(772.0, 179.0, 2000), route)
    g.update(fix(758.0, 180.0, 3000), route)
    assertTrue(g.standing is RouteStanding.WrongWay)
  }

  @Test
  fun turning_around_clears_wrong_way() {
    val g = RouteGuard()
    for (i in 0..4) g.update(fix(800.0 - i * 14, 180.0, i * 1000L), route)
    assertTrue(g.standing is RouteStanding.WrongWay)
    // Half way through the U-turn: still wrong way.
    g.update(fix(745.0, 90.0, 5000, speed = 4.0), route)
    assertTrue(g.standing is RouteStanding.WrongWay)
    g.update(fix(748.0, 5.0, 6000, speed = 6.0), route)
    g.update(fix(756.0, 2.0, 7000, speed = 8.0), route)
    assertEquals(RouteStanding.OnRoute, g.standing)
  }

  @Test
  fun standing_still_never_raises_wrong_way() {
    val g = RouteGuard()
    for (i in 0..10) g.update(fix(800.0, 180.0, i * 1000L, speed = 0.3), route)
    assertEquals(RouteStanding.OnRoute, g.standing)
  }

  @Test
  fun leaving_the_route_says_where_it_is() {
    val g = RouteGuard()
    // Took a road east, heading east, now 120 m from the route: the route is behind, to the left.
    for (i in 0..4) g.update(fix(1000.0, 90.0, i * 1000L, eastM = 60.0 + i * 15), route)
    val off = g.standing as RouteStanding.OffRoute
    assertEquals(120.0, off.distanceM, 5.0)
    assertTrue("route is to the left/behind, was ${off.turnDeg}", off.turnDeg < -90)

    // Heading north on a parallel road, 80 m west: the route is to the right.
    val g2 = RouteGuard()
    for (i in 0..4) g2.update(fix(600.0 + i * 14, 0.0, i * 1000L, eastM = -80.0), route)
    val off2 = g2.standing as RouteStanding.OffRoute
    assertTrue("route is to the right, was ${off2.turnDeg}", off2.turnDeg in 20.0..90.0)
  }

  @Test
  fun poor_gps_accuracy_widens_the_margin() {
    val g = RouteGuard()
    for (i in 0..5) g.update(RawFix(at(900.0, 45.0), 0.0, 14.0, accuracyM = 30.0, timeMs = i * 1000L), route)
    assertEquals(RouteStanding.OnRoute, g.standing)
  }
}
