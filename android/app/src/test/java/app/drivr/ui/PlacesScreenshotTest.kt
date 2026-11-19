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
  init {
    app.drivr.i18n.TestStrings.install()
  }

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
        QuickPanel(app.drivr.places.SavedPlaces(), {}, {}, {}, {}, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
      }
    }
  }

  @Test
  fun fuel_along_route() {
    val results = rankPlaces(pois("fuel", PlaceCategory.FUEL, emptyMap()), origin, route, 2000.0)
    val state = PlacesState(open = true, category = PlaceCategory.FUEL, alongRoute = true, results = results)
    paparazzi.snapshot {
      Screen("bg_fuel_route.png") {
        PlacesSheet(state, {}, {}, {}, Modifier.align(Alignment.BottomCenter))
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
        PlacesSheet(state, {}, {}, {}, Modifier.align(Alignment.BottomCenter))
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

  // ---- Driving cards: jam countdown and speed cameras (sample events on the A28) ----

  private fun drivingScreen(top: @Composable () -> Unit) {
    paparazzi.snapshot {
      Screen("bg_detail.png") {
        Column(Modifier.align(Alignment.BottomCenter).padding(12.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
          top()
          TripCards(speedLimitKmh = 100, speedKmh = 24, distanceRemainingM = 9_400.0, durationRemainingS = 900.0,
              trafficDelayS = 420.0, onEnd = null, now = java.time.LocalTime.of(9, 12))
        }
      }
    }
  }

  @Test
  fun jam_countdown() {
    val jam = app.drivr.traffic.TrafficEvent("j", app.drivr.traffic.TrafficKind.JAM,
        listOf(app.drivr.traffic.LatLon(52.12, 5.30), app.drivr.traffic.LatLon(52.13, 5.36)), delaySeconds = 420.0)
    val ahead = app.drivr.traffic.TrafficAhead(jam, 0.0, endM = 1_800.0, lengthM = 4_300.0)
    drivingScreen { JamCountdownCard(ahead) }
  }

  @Test
  fun average_speed_section() {
    val cam = app.drivr.cameras.SpeedCamera("s", app.drivr.cameras.CameraKind.SECTION, LatLng(52.12, 5.30),
        end = LatLng(52.13, 5.36), maxSpeedKmh = 100)
    drivingScreen {
      CameraAheadCard(app.drivr.cameras.CameraAhead(cam, 0.0, 3_200.0), averageKmh = 97.4)
    }
  }

  @Test
  fun speed_camera_ahead() {
    val cam = app.drivr.cameras.SpeedCamera("c", app.drivr.cameras.CameraKind.SPEED, LatLng(52.12, 5.30), maxSpeedKmh = 100)
    drivingScreen { CameraAheadCard(app.drivr.cameras.CameraAhead(cam, 450.0), averageKmh = null) }
  }

  @Test
  fun fuel_first_results_while_refining() {
    // What the driver sees ~1 s after tapping Fuel: the closest few, route still being checked.
    val near = rankPlaces(pois("fuel", PlaceCategory.FUEL, emptyMap()), origin, null, 3000.0).filter { it.distanceM < 3000 }
    val state = PlacesState(open = true, category = PlaceCategory.FUEL, alongRoute = true, refining = true, results = near)
    paparazzi.snapshot {
      Screen("bg_fuel_route.png") { PlacesSheet(state, {}, {}, {}, Modifier.align(Alignment.BottomCenter)) }
    }
  }

  @Test
  fun search_error_with_retry() {
    val state = PlacesState(open = true, category = PlaceCategory.FUEL, error = "Search didn't answer in time. Check your connection and try again.")
    paparazzi.snapshot {
      Screen("bg_browse.png") { PlacesSheet(state, {}, {}, {}, Modifier.align(Alignment.BottomCenter)) }
    }
  }

  @Test
  fun map_buttons_fold_into_menu_when_short() {
    // Only ~2 buttons fit: the third action folds into the "⋯" menu instead of overlapping.
    paparazzi.snapshot {
      Screen("bg_detail.png") {
        MapButtonColumn(
            listOf(MapAction("Search", MapIcon.Search) {}, MapAction("Overview", MapIcon.Overview) {}, MapAction("Recenter", MapIcon.Recenter) {}),
            emptyList(),
            Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).height(120.dp),
        )
      }
    }
  }
}
