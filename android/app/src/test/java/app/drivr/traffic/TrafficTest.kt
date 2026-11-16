package app.drivr.traffic

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser

class TrafficTest {
  private fun parseSample(): List<TrafficEvent> {
    val parser = KXmlParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    parser.setInput(javaClass.getResourceAsStream("/ndw_actueel_beeld_sample.xml"), "UTF-8")
    return NdwParser.parse(parser)
  }

  @Test
  fun parses_real_ndw_records() {
    // Sample is five real records from NDW's feed of 16 Nov 2026; speed management is skipped.
    val events = parseSample()
    assertEquals(
        setOf(
            TrafficKind.ACCIDENT,
            TrafficKind.JAM,
            TrafficKind.ROAD_CLOSED,
            TrafficKind.LANE_CLOSED,
            TrafficKind.BROKEN_DOWN,
        ),
        events.map { it.kind }.toSet(),
    )
    val accident = events.first { it.kind == TrafficKind.ACCIDENT }
    assertEquals(1, accident.geometry.size)
    assertTrue(accident.bearingDeg != null)
    val jam = events.first { it.kind == TrafficKind.JAM }
    assertTrue("jam has a line", jam.geometry.size >= 2)
    assertTrue(jam.geometry.all { it.lat in 50.0..54.0 && it.lon in 3.0..8.0 })
    val closure = events.first { it.kind == TrafficKind.ROAD_CLOSED }
    assertTrue(closure.end != null)
  }

  @Test
  fun only_events_on_our_side_of_the_road_count() {
    // Route heading north along lon 5.0.
    val route = (0..100).map { LatLon(52.0 + it * 0.0001, 5.0) }
    val now = Instant.now()
    val onRoute = TrafficEvent("a", TrafficKind.ACCIDENT, listOf(LatLon(52.005, 5.0001)), bearingDeg = 2.0, start = now)
    val otherWay = TrafficEvent("b", TrafficKind.ACCIDENT, listOf(LatLon(52.006, 5.0002)), bearingDeg = 182.0)
    val farAway = TrafficEvent("c", TrafficKind.ACCIDENT, listOf(LatLon(52.005, 5.01)))
    val jam =
        TrafficEvent("d", TrafficKind.JAM, listOf(LatLon(52.002, 5.0), LatLon(52.004, 5.0)), delaySeconds = 300.0)
    val jamOtherWay =
        TrafficEvent("e", TrafficKind.JAM, listOf(LatLon(52.004, 5.0002), LatLon(52.002, 5.0002)))
    val ahead = RouteTraffic.ahead(route, listOf(onRoute, otherWay, farAway, jam, jamOtherWay))
    assertEquals(listOf("d", "a"), ahead.map { it.event.id })
    assertEquals(222.0, ahead[0].distanceM, 5.0)
  }

  @Test
  fun inside_a_jam_counts_down_to_its_end() {
    // Jam from 52.002 to 52.006; the car is at 52.003, so about a quarter of the way through.
    val route = (0..100).map { LatLon(52.003 + it * 0.0001, 5.0) }
    val jam =
        TrafficEvent("j", TrafficKind.JAM, listOf(LatLon(52.002, 5.0), LatLon(52.006, 5.0)), delaySeconds = 400.0)
    val ahead = RouteTraffic.ahead(route, listOf(jam)).single()
    assertTrue(ahead.isInside)
    assertEquals(333.0, ahead.endDistanceM!!, 5.0)
    assertEquals(0.25f, ahead.progress, 0.02f)
    // Three quarters of the delay left, plus free-flow time for the rest.
    assertEquals(300.0 + 333.0 / 27.8, ahead.secondsLeft!!, 5.0)
  }

  @Test
  fun closed_roadworks_are_filtered_by_time() {
    val now = Instant.parse("2026-11-16T10:00:00Z")
    val ended = TrafficEvent("x", TrafficKind.ROAD_CLOSED, listOf(LatLon(52.0, 5.0)), end = Instant.parse("2026-11-15T10:00:00Z"))
    val future = TrafficEvent("y", TrafficKind.ROAD_CLOSED, listOf(LatLon(52.0, 5.0)), start = Instant.parse("2026-11-20T10:00:00Z"))
    val live = TrafficEvent("z", TrafficKind.ROAD_CLOSED, listOf(LatLon(52.0, 5.0)), start = Instant.parse("2026-11-01T10:00:00Z"))
    assertEquals(listOf("z"), listOf(ended, future, live).filter { it.isActive(now) }.map { it.id })
  }
}
