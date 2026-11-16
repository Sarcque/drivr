package app.drivr.traffic

import java.time.Instant
import org.xmlpull.v1.XmlPullParser

/**
 * Streams NDW's DATEX II v3 situation feed (actueel_beeld.xml.gz, ~4 MB unzipped) and keeps only
 * what a driver needs: accidents, jams, broken-down vehicles, obstructions and closures. Roadworks
 * planning, speed management and similar records are skipped.
 *
 * Takes any XmlPullParser (Android's own on device, kxml2 in unit tests), non-namespace-aware.
 */
object NdwParser {

  fun parse(parser: XmlPullParser): List<TrafficEvent> {
    val out = ArrayList<TrafficEvent>()
    var rec: Builder? = null
    val path = ArrayDeque<String>()
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
      when (event) {
        XmlPullParser.START_TAG -> {
          val name = local(parser.name)
          path.addLast(name)
          if (name == "situationRecord") {
            rec = Builder(parser.getAttributeValue(null, "id") ?: "", local(parser.getAttributeValue(null, "xsi:type") ?: ""))
          }
        }
        XmlPullParser.TEXT -> {
          val r = rec
          if (r != null && path.isNotEmpty()) r.text(path, parser.text.trim())
        }
        XmlPullParser.END_TAG -> {
          val name = path.removeLastOrNull()
          if (name == "situationRecord") {
            rec?.build()?.let(out::add)
            rec = null
          }
        }
      }
      event = parser.next()
    }
    return out
  }

  private fun local(name: String) = name.substringAfter(':')

  private class Builder(val id: String, val type: String) {
    var subtype: String? = null
    var lat: Double? = null
    var lon: Double? = null
    var bearing: Double? = null
    val line = ArrayList<LatLon>()
    var delay: Double? = null
    var queue: Double? = null
    var start: Instant? = null
    var end: Instant? = null

    fun text(path: ArrayDeque<String>, t: String) {
      if (t.isEmpty()) return
      when (path.last()) {
        "latitude" -> if (path.contains("pointCoordinates")) lat = t.toDoubleOrNull()
        "longitude" -> if (path.contains("pointCoordinates")) lon = t.toDoubleOrNull()
        "bearing" -> bearing = t.toDoubleOrNull()
        // Only the first linear location: later itinerary entries repeat it in other encodings.
        "posList" -> if (line.isEmpty()) line += parsePosList(t)
        "delayTimeValue" -> delay = t.toDoubleOrNull()
        "queueLength" -> queue = t.toDoubleOrNull()
        "overallStartTime" -> start = runCatching { Instant.parse(t) }.getOrNull()
        "overallEndTime" -> end = runCatching { Instant.parse(t) }.getOrNull()
        "abnormalTrafficType",
        "roadOrCarriagewayOrLaneManagementType",
        "vehicleObstructionType",
        "obstructionType",
        "accidentType" -> subtype = t
      }
    }

    fun build(): TrafficEvent? {
      val kind =
          when (type) {
            "Accident" -> TrafficKind.ACCIDENT
            "AbnormalTraffic" ->
                if (subtype == "stationaryTraffic" || subtype == "queuingTraffic") TrafficKind.JAM
                else TrafficKind.SLOW
            "VehicleObstruction" ->
                if (subtype == "brokenDownVehicle") TrafficKind.BROKEN_DOWN else TrafficKind.OBSTRUCTION
            "GeneralObstruction" -> TrafficKind.OBSTRUCTION
            "RoadOrCarriagewayOrLaneManagement" ->
                when (subtype) {
                  "carriagewayClosures", "roadClosed" -> TrafficKind.ROAD_CLOSED
                  "laneClosures" -> TrafficKind.LANE_CLOSED
                  else -> return null
                }
            else -> return null
          }
      val geometry =
          if (line.size >= 2) line
          else {
            val la = lat ?: line.firstOrNull()?.lat ?: return null
            val lo = lon ?: line.firstOrNull()?.lon ?: return null
            listOf(LatLon(la, lo))
          }
      return TrafficEvent(id, kind, geometry, bearing, delay, queue, start, end)
    }
  }

  /** DATEX II posList is "lat lon lat lon ...". */
  internal fun parsePosList(s: String): List<LatLon> {
    val n = s.split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }
    return (0 until n.size / 2).map { LatLon(n[2 * it], n[2 * it + 1]) }
  }
}
