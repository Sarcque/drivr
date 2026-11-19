package app.drivr.settings

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RoutePreferencesTest {
  @After fun reset() = RoutePreferences.set(Settings())

  private val body = """{"locations":[],"costing":"auto","costing_options":{"auto":{"shortest":false}},"units":"kilometers"}"""

  @Test
  fun nothing_to_avoid_leaves_the_request_alone() {
    RoutePreferences.set(Settings())
    assertEquals(JSONObject(body).toString(), RoutePreferences.applyTo(JSONObject(body)).toString())
  }

  @Test
  fun avoid_settings_become_valhalla_costing_options() {
    RoutePreferences.set(Settings(avoidTolls = true, avoidFerries = true))
    val auto = RoutePreferences.applyTo(JSONObject(body)).getJSONObject("costing_options").getJSONObject("auto")
    assertEquals(0.0, auto.getDouble("use_tolls"), 0.0)
    assertEquals(0.0, auto.getDouble("use_ferries"), 0.0)
    assertFalse(auto.has("use_highways"))
    assertFalse(auto.getBoolean("shortest")) // existing options kept
  }

  @Test
  fun motorways_without_existing_costing_options() {
    RoutePreferences.set(Settings(avoidMotorways = true))
    val out = RoutePreferences.applyTo(JSONObject("""{"costing":"auto"}"""))
    assertEquals(0.0, out.getJSONObject("costing_options").getJSONObject("auto").getDouble("use_highways"), 0.0)
  }
}
