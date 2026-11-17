package app.drivr.cameras

import app.drivr.places.LatLng
import app.drivr.places.OverpassClient
import app.drivr.places.RouteCorridor
import java.util.Locale
import kotlin.math.abs
import org.json.JSONObject

enum class CameraKind(val label: String) {
  SPEED("Speed camera"),
  RED_LIGHT("Red-light camera"),
  /** Average-speed section (trajectcontrole): start and end points. */
  SECTION("Average speed check"),
}

/** A fixed enforcement point or section from OpenStreetMap. */
data class SpeedCamera(
    val id: String,
    val kind: CameraKind,
    val at: LatLng,
    /** End of an average-speed section. */
    val end: LatLng? = null,
    val maxSpeedKmh: Int? = null,
    /** Direction the camera faces/enforces, degrees from north, when tagged. */
    val directionDeg: Double? = null,
)

/** A camera on the route ahead. For sections, [endM] is where it ends. */
data class CameraAhead(val camera: SpeedCamera, val distanceM: Double, val endM: Double? = null) {
  /** Inside an average-speed section right now. */
  val inside: Boolean get() = distanceM <= 0 && endM != null && endM > 0
}

/**
 * Fixed speed cameras, red-light cameras and average-speed sections from OpenStreetMap
 * (`highway=speed_camera`, `type=enforcement` relations), fetched once per route over Overpass.
 * Free, no key. Mobile police checks are not in any open dataset; they need driver reports (see
 * the README's roadmap).
 *
 * Legal note: warning of fixed cameras is legal in the Netherlands and Belgium; Germany and
 * Switzerland forbid it while driving, France only allows "danger zones". The app should switch
 * this off by country before a public release.
 */
class SpeedCameraClient(private val overpass: OverpassClient) {
  fun alongRoute(route: List<LatLng>, corridorM: Int = 50): List<SpeedCamera> {
    if (route.size < 2) return emptyList()
    // Simplify to within 15 m of the road, and query in chunks so each query stays small.
    val line = RouteCorridor(route).simplify(15.0)
    return line.chunked(400).flatMapIndexed { i, chunk ->
      val withJoin = if (i > 0) listOf(line[i * 400 - 1]) + chunk else chunk
      if (withJoin.size < 2) emptyList() else parse(overpass.rawBlocking(query(withJoin, corridorM)))
    }.distinctBy { it.id }
  }

  companion object {
    fun query(line: List<LatLng>, corridorM: Int): String {
      val coords = line.joinToString(",") { String.format(Locale.ROOT, "%.5f,%.5f", it.lat, it.lon) }
      return "[out:json][timeout:40];" +
          "node[highway=speed_camera](around:$corridorM,$coords)->.cams;" +
          "rel[type=enforcement][enforcement~\"^(maxspeed|average_speed|traffic_signals)$\"](around:$corridorM,$coords)->.enf;" +
          ".cams out;.enf out body;node(r.enf);out;"
    }

    fun parse(json: String): List<SpeedCamera> {
      val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
      val nodes = HashMap<Long, Pair<LatLng, Map<String, String>>>()
      val rels = ArrayList<JSONObject>()
      for (i in 0 until elements.length()) {
        val e = elements.getJSONObject(i)
        when (e.getString("type")) {
          "node" -> nodes[e.getLong("id")] = LatLng(e.getDouble("lat"), e.getDouble("lon")) to tags(e)
          "relation" -> rels += e
        }
      }
      val out = LinkedHashMap<String, SpeedCamera>()
      val usedAsDevice = HashSet<Long>()
      for (r in rels) {
        val t = tags(r)
        val members = r.optJSONArray("members") ?: continue
        fun role(name: String) =
            (0 until members.length()).map { members.getJSONObject(it) }
                .firstOrNull { it.optString("role") == name && it.optString("type") == "node" }
                ?.getLong("ref")
        val from = role("from")
        val to = role("to")
        val device = role("device")
        device?.let { usedAsDevice += it }
        val max = speed(t["maxspeed"])
        val id = "relation/${r.getLong("id")}"
        when (t["enforcement"]) {
          "average_speed" -> {
            val a = from?.let { nodes[it]?.first } ?: continue
            val b = to?.let { nodes[it]?.first } ?: continue
            out[id] = SpeedCamera(id, CameraKind.SECTION, a, end = b, maxSpeedKmh = max)
          }
          else -> {
            val at = (device ?: from)?.let { nodes[it]?.first } ?: continue
            val kind = if (t["enforcement"] == "traffic_signals") CameraKind.RED_LIGHT else CameraKind.SPEED
            out[id] = SpeedCamera(id, kind, at, maxSpeedKmh = max)
          }
        }
      }
      for ((nid, n) in nodes) {
        val (at, t) = n
        if (t["highway"] != "speed_camera" || nid in usedAsDevice) continue
        val id = "node/$nid"
        out[id] = SpeedCamera(id, CameraKind.SPEED, at, maxSpeedKmh = speed(t["maxspeed"]), directionDeg = t["direction"]?.toDoubleOrNull())
      }
      return out.values.toList()
    }

    /**
     * Cameras on the route ahead, nearest first. A camera counts when it is within [toleranceM]
     * of the road and (if its direction is tagged) faces our way, so the other carriageway's
     * cameras stay quiet. Sections count from their start; once inside, distance is 0.
     */
    fun ahead(route: List<LatLng>, cameras: List<SpeedCamera>, toleranceM: Double = 40.0): List<CameraAhead> {
      if (route.size < 2) return emptyList()
      val c = RouteCorridor(route)
      return cameras.mapNotNull { cam ->
        if (cam.kind == CameraKind.SECTION && cam.end != null) {
          val end = c.position(cam.end)
          if (end.offsetM > toleranceM) return@mapNotNull null
          val start = c.position(cam.at)
          when {
            start.offsetM <= toleranceM && start.alongM < end.alongM -> CameraAhead(cam, start.alongM, end.alongM)
            // Start is behind us (not on the remaining route): we're inside the section.
            start.offsetM > toleranceM && RouteCorridor.distanceM(cam.at, route.first()) <
                RouteCorridor.distanceM(cam.at, cam.end) -> CameraAhead(cam, 0.0, end.alongM)
            else -> null
          }
        } else {
          val p = c.position(cam.at)
          if (p.offsetM > toleranceM || p.alongM <= 0.0) return@mapNotNull null
          val dir = cam.directionDeg
          if (dir != null && angleDiff(dir, c.bearingAt(p.alongM)) > 70) return@mapNotNull null
          CameraAhead(cam, p.alongM)
        }
      }.sortedBy { it.distanceM }
    }

    private fun angleDiff(a: Double, b: Double): Double {
      val d = abs(a - b) % 360
      return if (d > 180) 360 - d else d
    }

    private fun tags(e: JSONObject): Map<String, String> {
      val t = e.optJSONObject("tags") ?: return emptyMap()
      return t.keys().asSequence().associateWith { t.getString(it) }
    }

    private fun speed(raw: String?): Int? = raw?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull()
  }
}

/**
 * Average speed through a section so far: distance driven since entering it over the time taken.
 * Feed it every location update; it resets when the section changes or ends.
 */
class SectionTracker {
  private var sectionId: String? = null
  private var entryEndM = 0.0
  private var entryMs = 0L

  /** Returns the average km/h since entering [ahead]'s section, or null when not in one yet. */
  fun update(ahead: CameraAhead?, nowMs: Long): Double? {
    if (ahead == null || !ahead.inside) {
      sectionId = null
      return null
    }
    val left = ahead.endM ?: return null
    if (ahead.camera.id != sectionId) {
      sectionId = ahead.camera.id
      entryEndM = left
      entryMs = nowMs
      return null
    }
    val dt = (nowMs - entryMs) / 1000.0
    val driven = entryEndM - left
    if (dt < 5 || driven < 50) return null
    return driven / dt * 3.6
  }
}
