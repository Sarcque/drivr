package app.drivr.traffic

import java.time.Instant

enum class TrafficKind(val label: String) {
  ACCIDENT("Accident"),
  JAM("Traffic jam"),
  SLOW("Slow traffic"),
  BROKEN_DOWN("Broken-down vehicle"),
  OBSTRUCTION("Obstruction on the road"),
  ROAD_CLOSED("Road closed"),
  LANE_CLOSED("Lane closed"),
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
