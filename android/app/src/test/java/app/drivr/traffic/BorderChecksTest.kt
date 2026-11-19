package app.drivr.traffic

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BorderChecksTest {
  /** Real Valhalla routes Arnhem → Oberhausen (19 Nov 2026), the way around the checks, and back. */
  private val fixture = JSONObject(javaClass.getResourceAsStream("/routes/arnhem_oberhausen_border.json")!!.bufferedReader().readText())

  private fun geom(r: JSONObject): List<LatLon> {
    val g = r.getJSONArray("geom")
    return (0 until g.length()).map { LatLon(g.getJSONArray(it).getDouble(0), g.getJSONArray(it).getDouble(1)) }
  }

  private val main = geom(fixture.getJSONArray("routes").getJSONObject(0))
  private val around = geom(fixture.getJSONObject("around"))
  private val back = geom(fixture.getJSONObject("back"))

  private val warnings = javaClass.getResourceAsStream("/autobahn/a3_warnings_constructed.json")!!.bufferedReader().readText()
  private val fetched = Instant.parse("2026-11-19T07:40:00Z")

  @Test
  fun the_a12_route_enters_germany_at_elten() {
    val c = BorderChecks.checkOn(main)
    assertNotNull(c)
    assertEquals("elten-a3", c!!.crossing.id)
    assertEquals(CrossingDirection.INTO_GERMANY, c.direction)
  }

  @Test
  fun the_way_back_crosses_into_the_netherlands_so_no_german_check() {
    val all = BorderChecks.crossingsOn(back)
    assertEquals(CrossingDirection.INTO_NETHERLANDS, all.single { it.crossing.id == "elten-a3" }.direction)
    assertNull(BorderChecks.checkOn(back))
  }

  @Test
  fun the_route_around_avoids_every_stationary_checkpoint() {
    assertNull(BorderChecks.checkOn(around))
    // It really leaves the motorway at the border: nowhere near the Elten crossing point.
    val elten = BorderChecks.NL_DE.first { it.id == "elten-a3" }.at
    assertTrue(around.minOf { BorderChecks.metres(it, elten) } > 500)
    assertTrue(main.minOf { BorderChecks.metres(it, elten) } < 60)
  }

  @Test
  fun exclude_polygons_fit_valhallas_outline_limit() {
    val polys = BorderChecks.avoidFor(listOf(BorderChecks.NL_DE.first()), listOf(LatLon(51.75, 6.4)))
    var outline = 0.0
    for (i in 0 until polys.length()) {
      val ring = polys.getJSONArray(i)
      for (k in 1 until ring.length()) {
        val a = ring.getJSONArray(k - 1)
        val b = ring.getJSONArray(k)
        outline += BorderChecks.metres(LatLon(a.getDouble(1), a.getDouble(0)), LatLon(b.getDouble(1), b.getDouble(0)))
      }
    }
    assertTrue("outline $outline", outline < 10_000)
    // The check on the route and the reported control come first.
    val first = polys.getJSONArray(0).getJSONArray(0)
    assertTrue(BorderChecks.metres(LatLon(first.getDouble(1), first.getDouble(0)), BorderChecks.NL_DE.first().at) < 300)
  }

  @Test
  fun autobahn_border_check_and_queue_reports() {
    val controls = ArrayList<ControlReport>()
    val reports = BorderChecks.parseAutobahnWarnings(warnings, fetched, controls = controls)
    // T1 names the check (25 min). T2 is the other direction (into NL) and is dropped.
    val r = reports.single()
    assertEquals("elten-a3", r.crossingId)
    assertEquals(25 * 60.0, r.delaySeconds!!, 0.1)
    assertTrue(r.namesCheck)
    assertEquals(Instant.parse("2026-11-19T06:12:00Z"), r.since)
    // T3 is a police check further on; T4 is an ordinary jam far away.
    assertEquals(1, controls.size)
    assertTrue(controls.single().text.contains("Polizeikontrolle"))
  }

  @Test
  fun a_jam_at_the_crossing_from_the_netherlands_counts_as_the_queue() {
    val w = JSONObject(warnings)
    val jam = w.getJSONArray("warning").getJSONObject(1).put("subtitle", "Arnheim -> Oberhausen")
    val reports = BorderChecks.parseAutobahnWarnings(JSONObject().put("warning", JSONArray().put(jam)).toString(), fetched)
    assertEquals(1, reports.size)
    assertFalse(reports.single().namesCheck)
    assertEquals(4 * 60.0, reports.single().delaySeconds!!, 0.1)
  }

  @Test
  fun broken_or_empty_answers_give_nothing() {
    assertTrue(BorderChecks.parseAutobahnWarnings("<html>", fetched).isEmpty())
    assertTrue(BorderChecks.parseAutobahnWarnings("{}", fetched).isEmpty())
  }

  @Test
  fun the_reported_control_lies_on_the_a3_route() {
    val controls = ArrayList<ControlReport>()
    BorderChecks.parseAutobahnWarnings(warnings, fetched, controls = controls)
    assertEquals(1, BorderChecks.controlsOn(main, controls).size)
    // The way around Elten still drives that part of the A3.
    assertEquals(1, BorderChecks.controlsOn(around, controls).size)
  }

  @Test
  fun old_reports_are_dropped_and_the_biggest_fresh_one_wins() {
    val now = fetched
    val old = BorderReport("elten-a3", BorderSource.AUTOBAHN_GMBH, 3600.0, null, now.minusSeconds(4 * 3600), "", true)
    val small = BorderReport("elten-a3", BorderSource.NDW, 300.0, null, now, "")
    val big = BorderReport("elten-a3", BorderSource.AUTOBAHN_GMBH, 1500.0, null, now, "", true)
    assertEquals(big, BorderChecks.bestReport(listOf(old, small, big), "elten-a3", now))
    assertNull(BorderChecks.bestReport(listOf(old), "elten-a3", now))
  }

  @Test
  fun with_a_25_minute_queue_the_way_around_is_faster() {
    // Valhalla: 74.8 min via Elten, 84.4 min around it. NDW saw nothing at Zevenaar in this test.
    val viaElten = ScoredRoute(fixture.getJSONArray("routes").getJSONObject(0).getDouble("duration"), 99_000.0, 0.0, 0, "A12",
        borderDelaySeconds = BorderChecks.extraDelay(BorderReport("elten-a3", BorderSource.AUTOBAHN_GMBH, 1500.0, null, fetched, "", true), 0.0))
    val aroundIt = ScoredRoute(fixture.getJSONObject("around").getDouble("duration"), 105_500.0, 0.0, 0, "N812")
    assertTrue(aroundIt.totalSeconds < viaElten.totalSeconds)
    // Without a report the A12 stays fastest: no invented delay.
    assertTrue(viaElten.copy(borderDelaySeconds = 0.0).totalSeconds < aroundIt.totalSeconds)
    // A queue NDW already counted isn't added twice.
    assertEquals(0.0, BorderChecks.extraDelay(BorderReport("elten-a3", BorderSource.AUTOBAHN_GMBH, 1500.0, null, fetched, ""), 1500.0), 0.1)
  }

  @Test
  fun motorway_numbers_from_step_names() {
    assertEquals(listOf("A12", "A3", "A42"), BorderChecks.motorways(listOf("Rijksweg A12", "A 3;E 35", null, "A 42", "N812")))
  }
}
