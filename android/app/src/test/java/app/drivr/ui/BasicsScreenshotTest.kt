package app.drivr.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.drivr.PlanField
import app.drivr.PlanState
import app.drivr.RouteStart
import app.drivr.i18n.L10n
import app.drivr.places.Place
import app.drivr.places.PlaceCategory
import app.drivr.places.SavedPlaces
import app.drivr.settings.Settings
import app.drivr.traffic.ScoredRoute
import com.android.resources.Density
import java.time.LocalTime
import org.junit.After
import org.junit.Rule
import org.junit.Test
import uniffi.ferrostar.ManeuverModifier
import uniffi.ferrostar.ManeuverType
import uniffi.ferrostar.VisualInstruction
import uniffi.ferrostar.VisualInstructionContent

/**
 * The new basics at the size of Joris's phone: the start screen with home/work/recents (no more
 * test-drive card), the route planner from another address, picking a start, the drive screen
 * with the voice button and Share, the end-trip question, and the new settings.
 *
 * bg_plan.png is Drivr's style rendered with MapLibre GL around Valhalla's three real routes from
 * Jaarbeursplein, Utrecht to Amersfoort (requested 19 Nov 2026): A28 35 min / 28.2 km, A27+A1
 * 46 min / 44.9 km, N234 48 min / 30.7 km. The card shows those numbers.
 */
class BasicsScreenshotTest {
  init {
    app.drivr.i18n.TestStrings.install()
  }

  private val device = DeviceConfig.PIXEL_6.copy(screenWidth = 1220, screenHeight = 2712, xdpi = 480, ydpi = 480, density = Density.XXHIGH)

  @get:Rule
  val paparazzi = Paparazzi(deviceConfig = device, showSystemUi = false, maxPercentDifference = 0.1)

  @After
  fun englishAgain() = app.drivr.i18n.TestStrings.install()

  private val jaarbeurs = Place("node/1", "Jaarbeursplein 6", null, 52.08865, 5.10820, address = "Utrecht")
  private val amersfoort = Place("node/2", "Amersfoort Centraal", null, 52.15353, 5.37349, address = "Stationsplein, Amersfoort")
  private val saved =
      SavedPlaces(
          home = Place("way/3", "Lijsterlaan 12", null, 52.21, 5.96, address = "Apeldoorn"),
          work = Place("node/4", "Jaarbeursplein 6", null, 52.08865, 5.10820, address = "Utrecht"),
          recent = listOf(
              amersfoort,
              Place("node/5", "IKEA Utrecht", PlaceCategory.SHOPS, 52.07, 5.08, address = "Utrecht"),
              Place("node/6", "Baarn", null, 52.21, 5.29),
          ),
      )
  private val routes =
      listOf(
          ScoredRoute(2125.6, 28_241.0, 0.0, 0, "A28"),
          ScoredRoute(2747.6, 44_855.0, 0.0, 0, "A27, A1"),
          ScoredRoute(2889.7, 30_655.0, 0.0, 0, "N234"),
      )

  @Composable
  private fun Map(bg: String, content: @Composable BoxScope.() -> Unit) {
    val bmp = remember { BitmapFactory.decodeStream(javaClass.getResourceAsStream("/places/$bg")).asImageBitmap() }
    Box(Modifier.fillMaxSize()) {
      Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
      content()
    }
  }

  private val safe = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 36.dp)

  /** Start screen: search on top, Route + home/work/recents at the bottom, no demo card. */
  @Test
  fun start_screen_saved_places() {
    paparazzi.snapshot {
      Map("bg_browse.png") {
        StackedOverlay(
            safe,
            top = {
              Row(verticalAlignment = Alignment.CenterVertically) {
                PlacesSearchBar("", {}, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                SettingsButton({})
              }
              CategoryChips(null, {})
            },
            buttons = { m -> MapButtonColumn(listOf(MapAction("Follow", MapIcon.Recenter) {}), emptyList(), m) },
            bottom = { QuickPanel(saved, {}, {}, {}, {}) },
        )
      }
    }
  }

  @Composable
  private fun Planner(plan: PlanState, fromHere: Boolean) {
    Map("bg_plan.png") {
      StackedOverlay(
          safe,
          top = { PlanFieldsCard(plan, {}, {}, {}) },
          buttons = { m -> MapButtonColumn(listOf(MapAction("Follow", MapIcon.Recenter) {}), emptyList(), m) },
          bottom = { PlanRoutesCard(routes, 0, fromHere, {}, {}) },
      )
    }
  }

  /** From another address: routes on the map, and "Preview drive" instead of Start. */
  @Test
  fun planner_from_address() {
    paparazzi.snapshot { Planner(PlanState(open = true, from = RouteStart.At(jaarbeurs), to = amersfoort), fromHere = false) }
  }

  @Test
  fun planner_from_address_nl() {
    app.drivr.i18n.TestStrings.install(app.drivr.i18n.XmlStrings("values-nl"))
    paparazzi.unsafeUpdateConfig(deviceConfig = device.copy(locale = "nl"))
    paparazzi.snapshot { Planner(PlanState(open = true, from = RouteStart.At(jaarbeurs), to = amersfoort), fromHere = false) }
  }

  @Test
  fun planner_from_here() {
    paparazzi.snapshot { Planner(PlanState(open = true, to = amersfoort), fromHere = true) }
  }

  /** Choosing the start: your location, a point on the map, home, work, recents. */
  @Test
  fun planner_choose_start() {
    val plan = PlanState(open = true, to = amersfoort, editing = PlanField.FROM)
    paparazzi.snapshot {
      Map("bg_browse.png") {
        StackedOverlay(
            safe,
            top = { PlanSearchField(PlanField.FROM, "", {}, {}, autoFocus = false) },
            buttons = {},
            bottom = { PlanSuggestions(plan, saved, {}, {}, {}) },
        )
      }
    }
  }

  @Test
  fun planner_pick_on_map() {
    paparazzi.snapshot {
      Map("bg_browse.png") {
        StackedOverlay(safe, top = { MapPickHint(PlanField.FROM, {}) }, buttons = {}, bottom = {})
      }
    }
  }

  private val turn =
      VisualInstruction(
          primaryContent = VisualInstructionContent("Catharijnesingel", ManeuverType.TURN, ManeuverModifier.RIGHT, null, null, emptyList()),
          secondaryContent = null,
          subContent = null,
          triggerDistanceBeforeManeuver = 400.0,
      )

  @Composable
  private fun Driving(muted: Boolean, menuOpen: Boolean, speedKmh: Int = 48) {
    Map("bg_drive.png") {
      StackedOverlay(
          safe,
          top = { InstructionBanner(turn, 180.0) },
          buttons = { m ->
            MapButtonColumn(
                listOf(
                    MapAction("Search", MapIcon.Search) {},
                    MapAction("Overview", MapIcon.Overview) {},
                    if (muted) MapAction(L10n.s(app.drivr.R.string.map_unmute), MapIcon.Muted) {}
                    else MapAction(L10n.s(app.drivr.R.string.map_mute), MapIcon.Sound) {},
                ),
                listOf("Turn list" to {}, L10n.s(app.drivr.R.string.menu_share_eta) to {}, "Settings" to {}, "End trip" to {}),
                m,
                initiallyOpen = menuOpen,
            )
          },
          bottom = {
            TripCards(speedLimitKmh = 30, speedKmh = speedKmh, distanceRemainingM = 27_400.0, durationRemainingS = 2040.0,
                onEnd = null, now = LocalTime.of(9, 58))
          },
      )
    }
  }

  /** Voice button on the map (Share arrival time sits in the ⋯ menu), speed over the limit in red. */
  @Test
  fun driving_voice_speeding() {
    paparazzi.snapshot { Driving(muted = false, menuOpen = false) }
  }

  @Test
  fun driving_muted() {
    paparazzi.snapshot { Driving(muted = true, menuOpen = false, speedKmh = 29) }
  }

  @Test
  fun end_trip_confirm() {
    paparazzi.snapshot {
      Box(Modifier.fillMaxSize()) {
        Driving(muted = false, menuOpen = false, speedKmh = 29)
        ConfirmEndTrip("Amersfoort Centraal", {}, {})
      }
    }
  }

  /** Settings: route preferences, saved places and the developer switch for the test drive. */
  @Test
  fun settings_route_saved_developer() {
    paparazzi.unsafeUpdateConfig(deviceConfig = device.copy(screenHeight = 7200))
    paparazzi.snapshot { SettingsScreen(Settings(avoidTolls = true), {}, {}, saved = saved) }
  }
}
