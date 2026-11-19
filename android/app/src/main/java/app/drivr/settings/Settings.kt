package app.drivr.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Units(@androidx.annotation.StringRes val labelRes: Int) {
  METRIC(app.drivr.R.string.units_metric),
  IMPERIAL(app.drivr.R.string.units_imperial),
}

/** Everything the driver can switch. Defaults favour a calm screen: AR off, sound on. */
data class Settings(
    /** AR junction view available at all (camera). Off by default. */
    val arEnabled: Boolean = false,
    /** Open AR by itself ~300 m before each turn. Off by default. */
    val arAuto: Boolean = false,
    val voice: Boolean = true,
    val speedCameras: Boolean = true,
    val trafficOnMap: Boolean = true,
    val trafficAlerts: Boolean = true,
    val buildings3d: Boolean = true,
    val units: Units = Units.METRIC,
    /** Music card reachable from the driving menu. Off by default to keep the drive calm. */
    val musicWhileDriving: Boolean = false,
    /** Offer a quicker way when a jam is ahead (the driver decides; never switches by itself). */
    val fasterRoutes: Boolean = true,
    /** Into Germany over a border checkpoint: offer a way around it and show live reports. */
    val borderChecks: Boolean = true,
    /** Always drive the route around border checkpoints, even when it is slower. Off by default. */
    val alwaysAvoidBorderChecks: Boolean = false,
    /** Always route around reported checks (police/traffic), even when slower. Off by default. */
    val alwaysAvoidChecks: Boolean = false,
)

/** Settings kept in SharedPreferences, exposed as a flow. */
class SettingsStore(context: Context) {
  private val prefs: SharedPreferences = context.getSharedPreferences("drivr_settings", Context.MODE_PRIVATE)
  private val _state = MutableStateFlow(read())
  val state: StateFlow<Settings> = _state.asStateFlow()

  fun update(change: (Settings) -> Settings) {
    val next = change(_state.value).let { if (!it.arEnabled) it.copy(arAuto = false) else it }
    _state.value = next
    prefs.edit()
        .putBoolean("arEnabled", next.arEnabled)
        .putBoolean("arAuto", next.arAuto)
        .putBoolean("voice", next.voice)
        .putBoolean("speedCameras", next.speedCameras)
        .putBoolean("trafficOnMap", next.trafficOnMap)
        .putBoolean("trafficAlerts", next.trafficAlerts)
        .putBoolean("buildings3d", next.buildings3d)
        .putString("units", next.units.name)
        .putBoolean("musicWhileDriving", next.musicWhileDriving)
        .putBoolean("fasterRoutes", next.fasterRoutes)
        .putBoolean("borderChecks", next.borderChecks)
        .putBoolean("alwaysAvoidBorderChecks", next.alwaysAvoidBorderChecks)
        .putBoolean("alwaysAvoidChecks", next.alwaysAvoidChecks)
        .apply()
    DisplayUnits.imperial = next.units == Units.IMPERIAL
  }

  private fun read(): Settings {
    val d = Settings()
    return Settings(
            arEnabled = prefs.getBoolean("arEnabled", d.arEnabled),
            arAuto = prefs.getBoolean("arAuto", d.arAuto),
            voice = prefs.getBoolean("voice", d.voice),
            speedCameras = prefs.getBoolean("speedCameras", d.speedCameras),
            trafficOnMap = prefs.getBoolean("trafficOnMap", d.trafficOnMap),
            trafficAlerts = prefs.getBoolean("trafficAlerts", d.trafficAlerts),
            buildings3d = prefs.getBoolean("buildings3d", d.buildings3d),
            musicWhileDriving = prefs.getBoolean("musicWhileDriving", d.musicWhileDriving),
            fasterRoutes = prefs.getBoolean("fasterRoutes", d.fasterRoutes),
            borderChecks = prefs.getBoolean("borderChecks", d.borderChecks),
            alwaysAvoidBorderChecks = prefs.getBoolean("alwaysAvoidBorderChecks", d.alwaysAvoidBorderChecks),
            alwaysAvoidChecks = prefs.getBoolean("alwaysAvoidChecks", d.alwaysAvoidChecks),
            units = runCatching { Units.valueOf(prefs.getString("units", d.units.name)!!) }.getOrDefault(d.units),
        )
        .also { DisplayUnits.imperial = it.units == Units.IMPERIAL }
  }
}

/** Read by the formatters, so every distance on screen follows the setting. */
object DisplayUnits {
  @Volatile var imperial: Boolean = false
}
