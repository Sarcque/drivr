package app.drivr.ui

import app.drivr.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.ferrostar.GeographicCoordinate

class RouteSliceTest {
  private val route = (0..10).map { GeographicCoordinate(52.0 + it * 0.001, 5.0) }

  @Test
  fun line_starts_at_the_car_and_runs_to_the_end() {
    val rest = sliceRouteAt(route, GeographicCoordinate(52.0045, 5.0002))
    assertEquals(52.0045, rest.first().lat, 1e-6)
    assertEquals(5.0, rest.first().lng, 1e-9)
    assertEquals(route.last(), rest.last())
    assertEquals(7, rest.size) // start point + 52.005 .. 52.010
  }

  @Test
  fun never_empty_while_a_route_exists() {
    // Car before the start, past the end, and far off the route: there is always a line to draw.
    listOf(GeographicCoordinate(51.99, 5.0), GeographicCoordinate(52.02, 5.0), GeographicCoordinate(52.005, 5.3)).forEach {
      assertTrue(sliceRouteAt(route, it).size >= 2)
    }
  }

  @Test
  fun style_without_3d_buildings() {
    val json = """{"version":8,"layers":[{"id":"water"},{"id":"building-3d"},{"id":"label"}]}"""
    val out = withoutLayer(json, "building-3d")
    assertFalse(out.contains("building-3d"))
    assertTrue(out.contains("label"))
  }

  @Test
  fun calm_defaults() {
    val s = Settings()
    assertFalse(s.arEnabled)
    assertFalse(s.arAuto)
    assertTrue(s.voice)
  }
}
