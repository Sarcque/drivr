package app.drivr.places

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacesTest {
  init {
    app.drivr.i18n.TestStrings.install()
  }

  private fun route(): List<LatLng> {
    val arr = JSONArray(javaClass.getResource("/places/route_utrecht_amersfoort.json")!!.readText())
    return (0 until arr.length()).map { arr.getJSONArray(it).let { c -> LatLng(c.getDouble(1), c.getDouble(0)) } }
  }

  @Test
  fun corridor_measures_the_real_route() {
    // Valhalla's route Utrecht Centraal → Amersfoort is 25.1 km.
    val c = RouteCorridor(route())
    assertEquals(25_100.0, c.lengthM, 250.0)
    // Esso by the A28 near Amersfoort: ~19.8 km along, under 200 m off the road.
    val esso = c.position(LatLng(52.126645, 5.36496))
    assertEquals(19_800.0, esso.alongM, 300.0)
    assertTrue(esso.offsetM < 200)
  }

  @Test
  fun corridor_sample_is_short_enough_for_overpass() {
    val s = RouteCorridor(route()).sample(maxLengthM = 100_000.0, maxPoints = 60)
    assertTrue(s.size in 30..60)
    assertEquals(route().first(), s.first())
  }

  @Test
  fun overpass_query_uses_small_boxes() {
    val q = OverpassClient.buildQuery(PlaceCategory.FUEL, listOf("52.0,5.0,52.1,5.1", "52.1,5.1,52.2,5.2"), 120, timeoutS = 10)
    assertEquals(
        "[out:json][timeout:10];(nwr[amenity=fuel](52.0,5.0,52.1,5.1);nwr[amenity=fuel](52.1,5.1,52.2,5.2););out center tags qt 120;",
        q)
  }

  @Test
  fun parses_overpass_json() {
    // Format sample: Overpass "out center tags" for one node and one way.
    val json =
        """
        {"elements":[
          {"type":"node","id":1,"lat":52.1266,"lon":5.3649,"tags":{"amenity":"fuel","brand":"Esso",
            "fuel:diesel":"yes","fuel:octane_95":"yes","fuel:lpg":"no","opening_hours":"24/7","addr:street":"Hogeweg","addr:city":"Amersfoort"}},
          {"type":"way","id":2,"center":{"lat":52.10,"lon":5.26},"tags":{"amenity":"charging_station",
            "operator":"Fastned","socket:type2_combo":"4","socket:type2_combo:output":"300 kW","socket:chademo":"1","socket:chademo:output":"50 kW"}},
          {"type":"node","id":3,"lat":1,"lon":1}
        ]}"""
    val places = OverpassClient.parse(json)
    assertEquals(2, places.size)
    val esso = places[0]
    assertEquals("node/1", esso.id)
    assertEquals("Esso", esso.name)
    assertEquals(PlaceCategory.FUEL, esso.category)
    assertEquals(listOf("Diesel", "Euro 95"), esso.fuels)
    assertTrue(esso.is24h)
    assertEquals("Hogeweg, Amersfoort", esso.address)
    val charger = places[1]
    assertEquals("Fastned", charger.name) // falls back to operator
    assertEquals(PlaceCategory.CHARGING, charger.category)
    assertEquals(listOf("CCS 300 kW", "CHAdeMO 50 kW"), charger.sockets)
    assertEquals(300.0, charger.maxPowerKw!!, 0.01)
  }

  @Test
  fun parses_power_values() {
    assertEquals(22.0, Place.parsePowerKw("22 kW")!!, 0.0)
    assertEquals(11.0, Place.parsePowerKw("11000 W")!!, 0.0)
    assertEquals(3.7, Place.parsePowerKw("3,7kW")!!, 0.001)
    assertNull(Place.parsePowerKw(""))
  }

  @Test
  fun parses_photon_json() {
    val json =
        """{"features":[{"geometry":{"coordinates":[5.110332,52.090862],"type":"Point"},"type":"Feature",
          "properties":{"osm_type":"N","osm_id":42,"osm_key":"shop","osm_value":"supermarket","name":"Albert Heijn",
          "street":"Stationshal","housenumber":"12","city":"Utrecht"}}]}"""
    val p = PhotonClient.parse(json).single()
    assertEquals("node/42", p.id)
    assertEquals(PlaceCategory.GROCERIES, p.category)
    assertEquals("Stationshal 12, Utrecht", p.address)
    assertEquals(52.090862, p.lat, 1e-9)
  }

  @Test
  fun france_prices_match_osm_station_by_position() {
    val json =
        """{"results":[{"id":1,"geom":{"lon":2.35,"lat":48.85},"gazole_prix":1.689,"e10_prix":1.729,
          "gazole_maj":"2026-11-16T08:00:00+00:00","sp98_prix":null}]}"""
    val records = FranceFuelPrices.parse(json)
    assertEquals(mapOf("Diesel" to 1.689, "E10" to 1.729), records.single().second.perLitre)
    val station = Place("node/9", "TotalEnergies", PlaceCategory.FUEL, 48.8504, 2.3502)
    val far = Place("node/10", "Esso", PlaceCategory.FUEL, 48.86, 2.35)
    val matched = FuelPriceRepository(emptyList()).match(listOf(station, far), records)
    assertEquals(setOf("node/9"), matched.keys)
  }

  @Test
  fun no_price_source_in_the_netherlands() {
    val inFrance = FranceFuelPrices.Area::inFrance
    assertFalse(inFrance(LatLng(52.09, 5.11))) // Utrecht
    assertTrue(inFrance(LatLng(48.85, 2.35))) // Paris
    assertTrue(inFrance(LatLng(50.63, 3.06))) // Lille
    assertTrue(inFrance(LatLng(48.58, 7.75))) // Strasbourg
    assertFalse(inFrance(LatLng(50.85, 5.69))) // Maastricht
    assertFalse(inFrance(LatLng(50.85, 4.35))) // Brussels
    assertFalse(inFrance(LatLng(46.2, 7.36))) // Sion, Switzerland
  }

  @Test
  fun route_boxes_cover_the_next_40_km_in_a_few_small_queries() {
    val c = RouteCorridor(route())
    val boxes = c.boxes(0.0, 40_000.0, padM = 2000.0)
    assertEquals(3, boxes.size) // 25 km route in 10 km steps
    // Every route point is inside some box.
    route().forEach { p -> assertTrue(boxes.any { p.lat in it.s..it.n && p.lon in it.w..it.e }) }
    // Each box stays small (well under 15 x 15 km).
    boxes.forEach { assertTrue((it.n - it.s) < 0.14 && (it.e - it.w) < 0.22) }
  }

  @Test
  fun cache_expires() {
    var t = 0L
    val cache = TtlCache<String, String>(ttlMs = 1000, maxEntries = 2) { t }
    cache["a"] = "1"
    assertEquals("1", cache["a"])
    t = 1500
    assertNull(cache["a"])
    cache["b"] = "2"; cache["c"] = "3"; cache["d"] = "4"
    assertNull(cache["b"]) // evicted, oldest
    assertEquals("4", cache["d"])
  }
}
