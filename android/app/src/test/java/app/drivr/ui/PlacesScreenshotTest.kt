package app.drivr.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.drivr.PlacesState
import app.drivr.RouteStatus
import app.drivr.places.FuelPrices
import app.drivr.places.LatLng
import app.drivr.places.Place
import app.drivr.places.PlaceCategory
import app.drivr.places.PlaceResult
import app.drivr.places.RouteCorridor
import app.drivr.places.rankPlaces
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test

/**
 * Places screens, full phone size. The map backgrounds are Drivr's own style rendered with
 * MapLibre GL over OpenFreeMap tiles; the places are real OSM POIs (names, positions) from those
 * tiles along Valhalla's Utrecht → Amersfoort route. Ranking runs through the app's own code.
 */
class PlacesScreenshotTest {
  @get:Rule
  val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, showSystemUi = false)

  private val origin = LatLng(52.08935, 5.11200)

  private fun text(name: String) = javaClass.getResource("/places/$name")!!.readText()

  private val route: List<LatLng> by lazy {
    val arr = JSONArray(text("route_utrecht_amersfoort.json"))
    (0 until arr.length()).map { arr.getJSONArray(it).let { c -> LatLng(c.getDouble(1), c.getDouble(0)) } }
  }

  private fun pois(key: String, category: PlaceCategory, tags: Map<String, String>): List<Place> {
    val arr = JSONObject(text("osm_pois_utrecht_a28.json")).getJSONArray(key)
    return (0 until arr.length()).map { i ->
      val o = arr.getJSONObject(i)
      Place("node/$key$i", o.getString("name"), category, o.getDouble("lat"), o.getDouble("lon"), tags)
    }
  }

  @Composable
  private fun Screen(bg: String, content: @Composable BoxScope.() -> Unit) {
    val bmp = remember(bg) { BitmapFactory.decodeStream(javaClass.getResourceAsStream("/places/$bg")).asImageBitmap() }
    Box(Modifier.fillMaxSize()) {
      Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
      content()
    }
  }

  @Composable
  private fun <T> remember(key: Any, calc: () -> T): T = androidx.compose.runtime.remember(key, calc)

  @Test
  fun browse_with_search_and_categories() {
    paparazzi.snapshot {
      Screen("bg_browse.png") {
        Column(Modifier.align(Alignment.TopCenter).padding(horizontal = 12.dp, vertical = 36.dp)) {
          PlacesSearchBar("", {})
          Spacer(Modifier.height(10.dp))
          CategoryChips(null, {})
        }
        StartPanel(RouteStatus.Idle, {}, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
      }
    }
  }

  @Test
  fun fuel_along_route() {
    val results = rankPlaces(pois("fuel", PlaceCategory.FUEL, emptyMap()), origin, route, 2000.0)
    val state = PlacesState(open = true, category = PlaceCategory.FUEL, alongRoute = true, results = results)
    paparazzi.snapshot {
      Screen("bg_fuel_route.png") {
        PlacesSheet(state, {}, {}, {}, Modifier.align(Alignment.BottomCenter), maxListHeight = 470.dp)
      }
    }
  }

  @Test
  fun fuel_station_add_stop() {
    // Position, distance along the route and the extra time are real (Valhalla: 1703 s via this
    // Esso vs 1662 s direct). Fuel types and hours are sample tags for the screenshot.
    val esso =
        Place(
            "node/esso", "Esso", PlaceCategory.FUEL, 52.126645, 5.36496,
            mapOf(
                "amenity" to "fuel", "brand" to "Esso", "fuel:diesel" to "yes", "fuel:octane_95" to "yes",
                "fuel:e10" to "yes", "fuel:adblue" to "yes", "opening_hours" to "24/7", "shop" to "convenience",
                "toilets" to "yes"),
            address = "Amersfoort")
    val r = rankPlaces(listOf(esso), origin, route, 2000.0).single()
    paparazzi.snapshot {
      Screen("bg_detail.png") {
        PlaceDetailCard(r, navigating = true, exactDetourS = 1703.478 - 1661.833, onAddStop = {}, onDrive = {}, onBack = {},
            modifier = Modifier.align(Alignment.BottomCenter))
      }
    }
  }

  @Test
  fun search_by_name() {
    val ah = pois("albertHeijn", PlaceCategory.GROCERIES, emptyMap())
        .map { it.copy(address = "Utrecht") }
    val results = rankPlaces(ah, origin, null, 1600.0).filter { it.distanceM < 1600 }
    val state = PlacesState(open = true, query = "Albert Heijn", results = results)
    paparazzi.snapshot {
      Screen("bg_search.png") {
        Column(Modifier.align(Alignment.TopCenter).padding(horizontal = 12.dp, vertical = 36.dp)) {
          PlacesSearchBar("Albert Heijn", {})
        }
        PlacesSheet(state, {}, {}, {}, Modifier.align(Alignment.BottomCenter), maxListHeight = 470.dp)
      }
    }
  }

  @Test
  fun price_card_france_sample() {
    // Sample prices: shows how a station looks where the government publishes prices (France).
    val station =
        Place("node/fr", "TotalEnergies Access", PlaceCategory.FUEL, 48.85, 2.35,
            mapOf("fuel:diesel" to "yes", "fuel:e10" to "yes", "fuel:octane_98" to "yes", "opening_hours" to "24/7"),
            address = "Paris")
    val r =
        PlaceResult(station, distanceM = 2300.0,
            prices = FuelPrices(mapOf("Diesel" to 1.639, "E10" to 1.689, "Super 98" to 1.799), Instant.now(), "prix-carburants.gouv.fr"))
    paparazzi.snapshot {
      Box(Modifier.fillMaxSize()) {
        PlaceDetailCard(r, navigating = false, exactDetourS = null, onAddStop = {}, onDrive = {}, onBack = {},
            priceCountry = "France", modifier = Modifier.align(Alignment.BottomCenter))
      }
    }
  }
}
