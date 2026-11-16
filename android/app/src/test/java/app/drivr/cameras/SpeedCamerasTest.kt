package app.drivr.cameras

import app.drivr.places.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedCamerasTest {
  // Route heading north along lon 5.0, ~11 km.
  private val route = (0..100).map { LatLng(52.0 + it * 0.001, 5.0) }

  @Test
  fun parses_cameras_and_sections() {
    // Format sample of the Overpass answer: a lone camera node, a section relation with its
    // from/to nodes, and a maxspeed relation whose device is also a speed_camera node.
    val json =
        """{"elements":[
          {"type":"node","id":1,"lat":52.010,"lon":5.0,"tags":{"highway":"speed_camera","maxspeed":"100","direction":"0"}},
          {"type":"node","id":2,"lat":52.030,"lon":5.0,"tags":{"highway":"speed_camera"}},
          {"type":"relation","id":10,"members":[{"type":"node","ref":3,"role":"from"},{"type":"node","ref":4,"role":"to"}],
            "tags":{"type":"enforcement","enforcement":"average_speed","maxspeed":"80"}},
          {"type":"relation","id":11,"members":[{"type":"node","ref":2,"role":"device"}],
            "tags":{"type":"enforcement","enforcement":"maxspeed","maxspeed":"50 km/h"}},
          {"type":"node","id":3,"lat":52.050,"lon":5.0},
          {"type":"node","id":4,"lat":52.080,"lon":5.0}
        ]}"""
    val cams = SpeedCameraClient.parse(json).associateBy { it.id }
    assertEquals(3, cams.size)
    assertEquals(CameraKind.SECTION, cams["relation/10"]!!.kind)
    assertEquals(80, cams["relation/10"]!!.maxSpeedKmh)
    assertEquals(50, cams["relation/11"]!!.maxSpeedKmh) // device node folded into its relation
    assertEquals(0.0, cams["node/1"]!!.directionDeg!!, 0.0)
  }

  @Test
  fun only_cameras_facing_our_way_count() {
    val ours = SpeedCamera("a", CameraKind.SPEED, LatLng(52.01, 5.0), directionDeg = 0.0)
    val otherWay = SpeedCamera("b", CameraKind.SPEED, LatLng(52.02, 5.0), directionDeg = 180.0)
    val offRoad = SpeedCamera("c", CameraKind.SPEED, LatLng(52.03, 5.01))
    val ahead = SpeedCameraClient.ahead(route, listOf(otherWay, ours, offRoad))
    assertEquals(listOf("a"), ahead.map { it.camera.id })
    assertEquals(1112.0, ahead[0].distanceM, 15.0)
  }

  @Test
  fun section_ahead_then_inside() {
    val section = SpeedCamera("s", CameraKind.SECTION, LatLng(52.02, 5.0), end = LatLng(52.06, 5.0), maxSpeedKmh = 100)
    val before = SpeedCameraClient.ahead(route, listOf(section)).single()
    assertEquals(2224.0, before.distanceM, 20.0)
    assertTrue(!before.inside)
    // Car now halfway through: the remaining route starts at 52.04.
    val inside = SpeedCameraClient.ahead(route.drop(40), listOf(section)).single()
    assertTrue(inside.inside)
    assertEquals(2224.0, inside.endM!!, 20.0)
  }

  @Test
  fun tracks_average_speed_in_a_section() {
    val cam = SpeedCamera("s", CameraKind.SECTION, LatLng(0.0, 0.0), end = LatLng(0.0, 0.0), maxSpeedKmh = 100)
    val t = SectionTracker()
    assertNull(t.update(CameraAhead(cam, 0.0, 5000.0), 0))
    // 1000 m in 36 s = 100 km/h.
    assertEquals(100.0, t.update(CameraAhead(cam, 0.0, 4000.0), 36_000)!!, 0.01)
    assertNull(t.update(null, 40_000))
  }
}
