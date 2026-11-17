package app.drivr.traffic

import app.drivr.i18n.L10n
import app.drivr.R
import java.time.Instant

enum class TrafficKind(@androidx.annotation.StringRes val labelRes: Int) {
  ACCIDENT(R.string.traffic_accident),
  JAM(R.string.traffic_jam),
  SLOW(R.string.traffic_slow),
  BROKEN_DOWN(R.string.traffic_broken_down),
  OBSTRUCTION(R.string.traffic_obstruction),
  ROAD_CLOSED(R.string.traffic_road_closed),
  LANE_CLOSED(R.string.traffic_lane_closed);

  val label: String get() = L10n.s(labelRes)
}

data class LatLon(val lat: Double, val lon: Double)

/**
 * One live event from NDW (Nationaal Dataportaal Wegverkeer), the Dutch government's free open
 * traffic data. Points for accidents and obstructions, lines for jams and closures.
 */
data class TrafficEvent(
    val id: String,
    val kind: TrafficKind,
    val geometry: List<LatLon>,
    /** Direction of travel affected, degrees from north, when NDW gives one (point events). */
    val bearingDeg: Double? = null,
    val delaySeconds: Double? = null,
    val queueLengthM: Double? = null,
    val start: Instant? = null,
    val end: Instant? = null,
) {
  val isLine
    get() = geometry.size >= 2

  fun isActive(now: Instant): Boolean =
      (start == null || !start.isAfter(now)) && (end == null || end.isAfter(now))
}
