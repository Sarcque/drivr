package app.drivr.cameras

import app.drivr.traffic.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedCamerasTest {
  // Hand-written in Overpass's answer format; not a live download (Overpass was unreachable from CI).
  private fun sample() =
      OverpassEnforcementParser.parse(javaClass.getResource("/overpass_enforcement_sample.json")!!.readText())

  /** Route heading north along lon 5.0 from [fromLat]. */
  private fun route(fromLat: Double = 52.0) = (0..100).map { LatLon(fromLat + it * 0.0001, 5.0) }

  @Test
  fun parses_cameras_and_sections() {
    val all = sample()
    val cameras = all.filterIsInstance<Enforcement.Camera>()
    val sections = all.filterIsInstance<Enforcement.Section>()
    // The section's own device (node 7) is shown as the section, not as a loose camera.
    assertEquals(listOf("n1", "n2", "n3"), cameras.map { it.id })
    assertEquals(100, cameras[0].maxspeedKmh)
    assertEquals(180.0, cameras[0].directionDeg!!, 0.0)
    assertEquals("red light", cameras[2].kind)
    assertEquals(1, sections.size)
    assertEquals(100, sections[0].maxspeedKmh)
    assertEquals(LatLon(52.005, 5.0), sections[0].from)
  }

  @Test
  fun cameras_and_sections_on_the_route_ahead() {
    val ahead = RouteEnforcement.ahead(route(), sample())
    // Camera 2 faces across the road and is dropped.
    assertEquals(listOf("n1", "n3", "r900"), ahead.map { it.enforcement.id })
    assertEquals(333.0, ahead[0].distanceM, 5.0)
    val section = ahead.last()
    assertFalse(section.isInsideSection)
    assertEquals(556.0, section.distanceM, 5.0)
    assertEquals(445.0, section.sectionLengthM!!, 5.0)
  }

  @Test
  fun inside_a_section_control() {
    // Car is past the section start (52.005), driving towards its end (52.009).
    val ahead = RouteEnforcement.ahead(route(fromLat = 52.006), sample())
    val section = ahead.single { it.enforcement is Enforcement.Section }
    assertTrue(section.isInsideSection)
    assertNotNull(section.endDistanceM)
    assertEquals(333.0, section.endDistanceM!!, 5.0)
  }

  @Test
  fun broken_answers_give_nothing() {
    assertEquals(emptyList<Enforcement>(), OverpassEnforcementParser.parse("""{"remark":"runtime error"}"""))
  }
}
