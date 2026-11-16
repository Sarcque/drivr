package app.drivr.cameras

import android.util.Log
import app.drivr.traffic.LatLon
import app.drivr.traffic.RouteIndex
import app.drivr.traffic.angleDiff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fixed speed cameras and section controls (trajectcontrole) from OpenStreetMap.
 *
 * In the Netherlands warning drivers about *fixed* cameras is legal (radar detectors are not), and
 * the Openbaar Ministerie publishes where fixed cameras and section controls are. OSM mappers keep
 * those as `highway=speed_camera` nodes and `type=enforcement` relations. Mobile police controls
 * have no free live source: Flitsmeister and Waze crowd-source them into closed databases.
 */
sealed interface Enforcement {
  val id: String

  /** A fixed camera at one spot. */
  data class Camera(
      override val id: String,
      val at: LatLon,
      val maxspeedKmh: Int?,
      /** Viewing direction in degrees when mapped; null = unknown or both ways. */
      val directionDeg: Double?,
      val kind: String = "speed",
  ) : Enforcement

  /** Section control: average speed is measured between two points. */
  data class Section(
      override val id: String,
      val from: LatLon,
      val to: LatLon,
      val maxspeedKmh: Int?,
  ) : Enforcement
}

/** An enforcement point on the route ahead. */
data class EnforcementAhead(
    val enforcement: Enforcement,
    val distanceM: Double,
    /** Section control: distance to the end point. */
    val endDistanceM: Double? = null,
    val sectionLengthM: Double? = null,
) {
  val isInsideSection
    get() = enforcement is Enforcement.Section && distanceM <= 0.0 && endDistanceM != null
}

object OverpassEnforcementParser {
  private val json = Json { ignoreUnknownKeys = true }

  fun parse(body: String): List<Enforcement> {
    val elements = json.parseToJsonElement(body).jsonObject["elements"]?.jsonArray ?: return emptyList()
    val nodes = HashMap<Long, LatLon>()
    val out = ArrayList<Enforcement>()
    val inSection = HashSet<Long>()
    val objs = elements.map { it.jsonObject }
    for (e in objs) {
      if (e.str("type") == "node") {
        val id = e["id"]?.jsonPrimitive?.longOrNull ?: continue
        val lat = e["lat"]?.jsonPrimitive?.doubleOrNull ?: continue
        val lon = e["lon"]?.jsonPrimitive?.doubleOrNull ?: continue
        nodes[id] = LatLon(lat, lon)
      }
    }
    for (e in objs.filter { it.str("type") == "relation" }) {
      val tags = e["tags"]?.jsonObject ?: continue
      if (tags.str("type") != "enforcement") continue
      val kind = tags.str("enforcement")
      if (kind != "average_speed" && kind != "maxspeed") continue
      val members = e["members"]?.jsonArray?.map { it.jsonObject }.orEmpty()
      val from = members.firstOrNull { it.str("role") == "from" }?.ref()?.let(nodes::get)
      val to = members.firstOrNull { it.str("role") == "to" }?.ref()?.let(nodes::get)
      if (kind == "average_speed") {
        // Section cameras are shown as the section, not as separate cameras.
        members.filter { it.str("role") == "device" }.mapNotNull { it.ref() }.forEach(inSection::add)
      }
      if (kind == "average_speed" && from != null && to != null) {
        out += Enforcement.Section("r${e["id"]}", from, to, tags.str("maxspeed")?.toIntOrNull())
      }
    }
    for (e in objs.filter { it.str("type") == "node" }) {
      val tags = e["tags"]?.jsonObject ?: continue
      if (tags.str("highway") != "speed_camera") continue
      val id = e["id"]?.jsonPrimitive?.longOrNull ?: continue
      if (id in inSection) continue
      val at = nodes[id] ?: continue
      out +=
          Enforcement.Camera(
              id = "n$id",
              at = at,
              maxspeedKmh = tags.str("maxspeed")?.filter { it.isDigit() }?.toIntOrNull(),
              directionDeg = tags.str("direction")?.let { directionDegrees(it) },
              kind = if (tags.str("enforcement") == "traffic_signals") "red light" else "speed",
          )
    }
    return out
  }

  private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull

  private fun JsonObject.ref() = this["ref"]?.jsonPrimitive?.longOrNull

  private fun directionDegrees(s: String): Double? =
      s.toDoubleOrNull()
          ?: mapOf("N" to 0.0, "NE" to 45.0, "E" to 90.0, "SE" to 135.0, "S" to 180.0, "SW" to 225.0, "W" to 270.0, "NW" to 315.0)[s.uppercase()]
}

/** Fetches enforcement for the area around a route, once per route, from a public Overpass server. */
class SpeedCameraRepository(
    private val http: OkHttpClient,
    private val endpoint: String = "https://overpass-api.de/api/interpreter",
) {
  suspend fun forRoute(route: List<LatLon>): List<Enforcement> =
      withContext(Dispatchers.IO) {
        if (route.isEmpty()) return@withContext emptyList()
        val pad = 0.01
        val s = route.minOf { it.lat } - pad
        val n = route.maxOf { it.lat } + pad
        val w = route.minOf { it.lon } - pad
        val e = route.maxOf { it.lon } + pad
        val bbox = "$s,$w,$n,$e"
        val query =
            "[out:json][timeout:25];(node[\"highway\"=\"speed_camera\"]($bbox);" +
                "relation[\"type\"=\"enforcement\"]($bbox););out body;>;out skel qt;"
        runCatching {
              val req = Request.Builder().url(endpoint).post(FormBody.Builder().add("data", query).build()).build()
              http.newCall(req).execute().use { r ->
                check(r.isSuccessful) { "HTTP ${r.code}" }
                OverpassEnforcementParser.parse(r.body.string())
              }
            }
            .onFailure { Log.w("SpeedCameras", "Overpass request failed", it) }
            .getOrDefault(emptyList())
      }
}

object RouteEnforcement {
  fun ahead(route: List<LatLon>, all: List<Enforcement>, toleranceM: Double = 30.0): List<EnforcementAhead> {
    val index = RouteIndex(route)
    if (!index.isUsable) return emptyList()
    val out = ArrayList<EnforcementAhead>()
    for (en in all) {
      when (en) {
        is Enforcement.Camera -> {
          val hit = index.locate(en.at) ?: continue
          if (hit.offset > toleranceM) continue
          val dir = en.directionDeg
          // A camera facing the car points opposite our travel direction; facing away (rear
          // camera) matches it. We accept both, and drop only ones clearly across the road.
          if (dir != null && angleDiff(dir, hit.routeBearing) in 60.0..120.0) continue
          out += EnforcementAhead(en, hit.along)
        }
        is Enforcement.Section -> {
          val to = index.locate(en.to) ?: continue
          if (to.offset > toleranceM) continue
          val from = index.locate(en.from) ?: continue
          val length = index.polylineLength(listOf(en.from, en.to)).coerceAtLeast(to.along - from.along)
          when {
            from.offset <= toleranceM && to.along > from.along ->
                out += EnforcementAhead(en, from.along, to.along, to.along - from.along)
            // Start is behind us: we're inside if the distance back to it fits the section.
            from.offset > toleranceM &&
                to.along < length &&
                index.distanceFromStartTo(listOf(en.from)) <= (length - to.along) * 1.3 + toleranceM ->
                out += EnforcementAhead(en, 0.0, to.along, length)
          }
        }
      }
    }
    return out.sortedBy { it.distanceM }
  }
}
