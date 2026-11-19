package app.drivr

import app.drivr.traffic.BorderChecks
import app.drivr.traffic.BorderCrossing
import app.drivr.traffic.BorderInfo
import app.drivr.traffic.BorderReport
import app.drivr.traffic.ControlReport
import app.drivr.traffic.LatLon
import app.drivr.traffic.RouteChoice
import app.drivr.traffic.ScoredRoute
import app.drivr.traffic.TrafficEvent
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import uniffi.ferrostar.Route
import uniffi.ferrostar.RouteAdapter
import uniffi.ferrostar.RouteRequest
import uniffi.ferrostar.UserLocation
import uniffi.ferrostar.Waypoint

/**
 * Asks Valhalla for the route plus up to [count] alternatives. Kept apart from Ferrostar's own
 * routing so reroutes and detour checks stay quick; only trip starts and jam checks use this.
 */
class AlternativeRoutesClient(
    private val http: OkHttpClient,
    private val adapter: RouteAdapter,
) {
  /**
   * [avoid]: Valhalla `exclude_polygons` (e.g. border checkpoints, see [BorderChecks.avoidPolygons]);
   * with it only the one best route around them is asked for.
   */
  suspend fun routes(from: UserLocation, waypoints: List<Waypoint>, avoid: JSONArray? = null): List<Route> =
      withContext(Dispatchers.IO) {
        val request =
            when (val r = adapter.generateRequest(from, waypoints)) {
              is RouteRequest.HttpPost -> {
                val body =
                    if (avoid == null) r.body
                    else JSONObject(String(r.body)).put("exclude_polygons", avoid).put("alternates", 0).toString().toByteArray()
                Request.Builder()
                    .url(r.url)
                    .apply { r.headers.forEach { (k, v) -> header(k, v) } }
                    .post(body.toRequestBody((r.headers["Content-Type"] ?: "application/json").toMediaType()))
                    .build()
              }
              is RouteRequest.HttpGet ->
                  Request.Builder().url(r.url).apply { r.headers.forEach { (k, v) -> header(k, v) } }.build()
            }
        http.newCall(request).execute().use { response ->
          check(response.isSuccessful) { "HTTP ${response.code}" }
          adapter.parseResponse(response.body.bytes())
        }
      }
}

/** A route and how it scores with live traffic right now. */
data class RouteOption(val route: Route, val score: ScoredRoute) {
  val geometry: List<LatLon> get() = route.geometry.map { LatLon(it.lat, it.lng) }
}

/** Border checks for [rankRoutes]: live reports, and which routes were asked to avoid a checkpoint. */
data class BorderContext(
    /** Live reports; null when the live sources couldn't be reached. */
    val reports: List<BorderReport>?,
    /** For each route given to [rankRoutes], the checkpoint it was routed around, or null. */
    val avoiding: List<BorderCrossing?>,
    val now: Instant = Instant.now(),
    /** Other reported checks (police, traffic), live. */
    val controls: List<ControlReport> = emptyList(),
    /** For each route, how many reported checks it was routed around. */
    val avoidingControls: List<Int> = emptyList(),
)

/**
 * Scores [routes] with [events] (and border checks, when [border] is given), drops near-duplicates,
 * and returns them quickest first. A route that goes over a different border crossing is never
 * a duplicate: that is the whole point of offering it.
 */
fun rankRoutes(routes: List<Route>, events: List<TrafficEvent>, border: BorderContext? = null): List<RouteOption> {
  val via = RouteChoice.viaLabels(routes.map { r -> r.steps.map { RouteChoice.stepRoad(it.roadName, it.instruction, it.distance) } })
  val geometries = routes.map { r -> r.geometry.map { LatLon(it.lat, it.lng) } }
  val checks = if (border == null) emptyList() else geometries.map { BorderChecks.checkOn(it) }
  // Same destination, so a route without the checkpoint that another one has crosses elsewhere.
  val someCheck = checks.firstNotNullOfOrNull { it }?.crossing
  val scored =
      routes.mapIndexed { i, r ->
        val geom = geometries[i]
        val (delay, closures) = RouteChoice.traffic(geom, events)
        var info: BorderInfo? = null
        var borderDelay = 0.0
        if (border != null) {
          val check = checks[i]
          val avoids = border.avoiding.getOrNull(i) ?: if (check == null) someCheck else null
          if (check != null) {
            val ndw = BorderChecks.ndwQueue(geom, check, events, border.now)
            val report = BorderChecks.bestReport(border.reports.orEmpty() + listOfNotNull(ndw), check.crossing.id, border.now)
            borderDelay = BorderChecks.extraDelay(report, ndw?.delaySeconds ?: 0.0)
            info = BorderInfo(check.crossing, report, avoids, liveChecked = border.reports != null)
          } else if (avoids != null) {
            info = BorderInfo(null, null, avoids, liveChecked = border.reports != null)
          }
          val onRoute = BorderChecks.controlsOn(geom, border.controls)
          val avoided = border.avoidingControls.getOrNull(i) ?: 0
          if (onRoute.isNotEmpty() || avoided > 0) {
            info = (info ?: BorderInfo(null, liveChecked = border.reports != null)).copy(controlsOnRoute = onRoute, avoidsControls = avoided)
          }
        }
        RouteOption(r, ScoredRoute(r.steps.sumOf { it.duration }, r.distance, delay, closures, via[i], info, borderDelay))
      }
  return RouteChoice.distinct(scored.sortedBy { it.score.totalSeconds }, { it.geometry }) { a, b ->
    a.score.border?.crossing?.id == b.score.border?.crossing?.id
  }
}
