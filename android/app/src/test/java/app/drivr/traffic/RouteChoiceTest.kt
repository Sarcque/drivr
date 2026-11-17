package app.drivr.traffic

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteChoiceTest {

  /** Real data: Valhalla's two routes 's-Hertogenbosch → Utrecht and NDW's feed, Tue 17 Nov 2026, 09:40. */
  private class Fixture {
    val json = JSONObject(javaClass.getResourceAsStream("/routes/denbosch_utrecht_rush_hour.json")!!.bufferedReader().readText())
    val events =
        json.getJSONArray("events").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.map { e ->
          val g = e.getJSONArray("geom")
          TrafficEvent(
              e.getString("id"),
              TrafficKind.valueOf(e.getString("kind")),
              (0 until g.length()).map { LatLon(g.getJSONArray(it).getDouble(0), g.getJSONArray(it).getDouble(1)) },
              bearingDeg = e.optDouble("bearing").takeIf { !it.isNaN() },
              delaySeconds = e.optDouble("delay").takeIf { !it.isNaN() },
              queueLengthM = e.optDouble("queue").takeIf { !it.isNaN() },
          )
        }
    val routes = json.getJSONArray("routes").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    val geometries =
        routes.map { r ->
          val g = r.getJSONArray("geom")
          (0 until g.length()).map { LatLon(g.getJSONArray(it).getDouble(0), g.getJSONArray(it).getDouble(1)) }
        }
    val steps =
        routes.map { r ->
          val s = r.getJSONArray("steps")
          (0 until s.length()).map { s.getJSONObject(it) }.map {
            RouteChoice.stepRoad(it.optString("name"), it.optString("instruction"), it.getDouble("distance"))
          }
        }
    val via = RouteChoice.viaLabels(steps)
    val scored =
        routes.mapIndexed { i, r ->
          val (delay, closures) = RouteChoice.traffic(geometries[i], events)
          ScoredRoute(r.getDouble("duration"), r.getDouble("distance"), delay, closures, via[i])
        }
  }

  @Test
  fun real_rush_hour_both_ways_are_jammed_and_the_a2_stays_quickest() {
    val f = Fixture()
    val (a2, a27) = f.scored
    // Valhalla alone says the A2 is 20 minutes quicker (51 vs 72 min)...
    assertEquals(51.4, a2.baseSeconds / 60, 0.5)
    assertEquals(71.6, a27.baseSeconds / 60, 0.5)
    // ...but NDW reports 78 minutes of jams on it (Zaltbommel, Empel), and 58 on the A27 (Gorinchem).
    assertEquals(4697.0, a2.trafficSeconds, 1.0)
    assertEquals(3512.0, a27.trafficSeconds, 1.0)
    // NDW also has a "road closed" on the A27 where it reports a moving jam: not a real closure.
    assertEquals(0, a27.closures)
    assertEquals("A2", a2.via)
    assertEquals("A27", a27.via)
    // With traffic they're a minute apart: not worth suggesting a switch.
    assertEquals(1.0, (a27.totalSeconds - a2.totalSeconds) / 60, 1.0)
    assertNull(RouteChoice.betterThan(a2, f.geometries[0], listOf(1 to a27)) { f.geometries[it] })
  }

  @Test
  fun two_different_ways_are_kept_and_a_route_matches_itself() {
    val f = Fixture()
    assertEquals(1.0, RouteChoice.overlap(f.geometries[0], f.geometries[0]), 1e-9)
    assertTrue(RouteChoice.overlap(f.geometries[0], f.geometries[1]) < 0.5)
    assertEquals(2, RouteChoice.distinct(f.geometries.indices.toList()) { f.geometries[it] }.size)
    assertEquals(1, RouteChoice.distinct(listOf(0, 0)) { f.geometries[it] }.size)
  }

  // ---- Synthetic: two parallel roads north, 1 km apart -------------------------------------------

  private val west = (0..300).map { LatLon(52.0 + it * 0.0001, 5.0) }
  private val east = listOf(LatLon(52.0, 5.0), LatLon(52.0, 5.0146)) + (0..300).map { LatLon(52.0 + it * 0.0001, 5.0146) } +
      listOf(LatLon(52.03, 5.0))

  @Test
  fun a_much_quicker_way_around_a_jam_is_offered() {
    val jam = TrafficEvent("j", TrafficKind.JAM, listOf(LatLon(52.005, 5.0), LatLon(52.025, 5.0)), delaySeconds = 20 * 60.0)
    val (d, c) = RouteChoice.traffic(west, listOf(jam))
    assertEquals(1200.0, d, 1e-6)
    val current = ScoredRoute(30 * 60.0, 3300.0, d, c)
    val detour = ScoredRoute(38 * 60.0, 5400.0, 0.0, 0)
    val best = RouteChoice.betterThan(current, west, listOf("east" to detour)) { east }
    assertNotNull(best)
    assertEquals(12.0, (current.totalSeconds - best!!.second.totalSeconds) / 60, 0.01)
  }

  @Test
  fun small_savings_and_the_same_road_are_not_offered() {
    val current = ScoredRoute(30 * 60.0, 3300.0, 5 * 60.0, 0)
    // Saves 2 min: below the 3 min threshold.
    assertNull(RouteChoice.betterThan(current, west, listOf("east" to ScoredRoute(33 * 60.0, 5400.0, 0.0, 0))) { east })
    // Saves 10 min but is the road we're already on.
    assertNull(RouteChoice.betterThan(current, west, listOf("west" to ScoredRoute(25 * 60.0, 3300.0, 0.0, 0))) { west })
  }

  @Test
  fun a_real_closure_counts_but_a_closure_inside_a_jam_does_not() {
    val closed = TrafficEvent("c", TrafficKind.ROAD_CLOSED, listOf(LatLon(52.010, 5.0), LatLon(52.012, 5.0)))
    assertEquals(0.0 to 1, RouteChoice.traffic(west, listOf(closed)))
    val jam = TrafficEvent("j", TrafficKind.JAM, listOf(LatLon(52.008, 5.0), LatLon(52.015, 5.0)), delaySeconds = 300.0)
    assertEquals(300.0 to 0, RouteChoice.traffic(west, listOf(closed, jam)))
    val withClosure = ScoredRoute(600.0, 3300.0, 0.0, 1)
    assertEquals(600.0 + ScoredRoute.CLOSURE_PENALTY_S, withClosure.totalSeconds, 1e-6)
  }

  @Test
  fun jam_without_a_reported_delay_is_estimated_from_its_length() {
    // 2.2 km of jam (52.005 → 52.025 is ~2.2 km) at 15 km/h instead of 100: about 7.5 min.
    val jam = TrafficEvent("j", TrafficKind.JAM, listOf(LatLon(52.005, 5.0), LatLon(52.025, 5.0)))
    val (d, _) = RouteChoice.traffic(west, listOf(jam))
    assertEquals(7.5, d / 60, 0.3)
  }

  @Test
  fun inside_a_jam_only_the_part_ahead_counts() {
    val jam = TrafficEvent("j", TrafficKind.JAM, listOf(LatLon(51.99, 5.0), LatLon(52.01, 5.0)), delaySeconds = 600.0)
    val ahead = RouteTraffic.ahead(west, listOf(jam)).single()
    assertTrue(ahead.inside)
    assertEquals(300.0, RouteChoice.delayOf(ahead), 15.0)
  }

  @Test
  fun road_numbers_come_from_valhalla_instructions() {
    assertEquals("A2", RouteChoice.stepRoad("", "Take the Knooppunt Empel exit onto A2 toward Amsterdam/Utrecht.", 1.0).name)
    assertEquals("A27", RouteChoice.stepRoad("", "Keep left to stay on A27/E 311 toward Amsterdam/Utrecht.", 1.0).name)
    assertEquals("Lijsterlaan", RouteChoice.stepRoad("Lijsterlaan", "Turn right onto Lijsterlaan.", 1.0).name)
    assertEquals("A27", RouteChoice.primaryName("A27; E 311"))
    assertEquals("Ring Utrecht", RouteChoice.primaryName("Ring Utrecht"))
    assertEquals("A27", RouteChoice.stepRoad("", "Take the A27 exit toward Utrecht/Gorinchem.", 1.0).name)
  }
}
