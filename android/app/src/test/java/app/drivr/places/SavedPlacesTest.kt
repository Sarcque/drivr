package app.drivr.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavedPlacesTest {
  private fun p(id: String, name: String = id, lat: Double = 52.0, lon: Double = 5.0) = Place(id, name, null, lat, lon)

  @Test
  fun recents_newest_first_without_duplicates_and_capped() {
    var s = SavedPlaces()
    (1..10).forEach { s = s.withRecent(p("node/$it", lat = 52.0 + it * 0.01)) }
    s = s.withRecent(p("node/5", lat = 52.05))
    assertEquals(SavedPlaces.MAX_RECENT, s.recent.size)
    assertEquals("node/5", s.recent.first().id)
    assertEquals(1, s.recent.count { it.id == "node/5" })
  }

  @Test
  fun the_same_dropped_pin_twice_is_one_recent() {
    val a = Place("pin/52.10000,5.10000", "Stationsplein 1", null, 52.1, 5.1)
    val b = Place("pin/52.10001,5.10002", "Stationsplein 1", null, 52.10001, 5.10002)
    assertEquals(1, SavedPlaces().withRecent(a).withRecent(b).recent.size)
  }

  @Test
  fun json_round_trip_keeps_what_routing_needs() {
    val place = Place("way/9", "IKEA Utrecht", PlaceCategory.SHOPS, 52.07, 5.08, tags = mapOf("shop" to "furniture"), address = "Utrecht")
    val back = SavedPlacesStore.fromJson(SavedPlacesStore.toJson(place))!!
    assertEquals(place.copy(tags = emptyMap()), back)
    val noAddress = SavedPlacesStore.fromJson(SavedPlacesStore.toJson(Place("pin/1,2", "Pin", null, 1.0, 2.0)))!!
    assertNull(noAddress.address)
    assertNull(noAddress.category)
  }
}
