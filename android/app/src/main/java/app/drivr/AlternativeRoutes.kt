package app.drivr

import app.drivr.traffic.LatLon
import app.drivr.traffic.RouteChoice
import app.drivr.traffic.ScoredRoute
import app.drivr.traffic.TrafficEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
  suspend fun routes(from: UserLocation, waypoints: List<Waypoint>): List<Route> =
      withContext(Dispatchers.IO) {
        val request =
            when (val r = adapter.generateRequest(from, waypoints)) {
              is RouteRequest.HttpPost ->
                  Request.Builder()
                      .url(r.url)
                      .apply { r.headers.forEach { (k, v) -> header(k, v) } }
                      .post(r.body.toRequestBody((r.headers["Content-Type"] ?: "application/json").toMediaType()))
                      .build()
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

/** Scores [routes] with [events], drops near-duplicates, and returns them quickest first. */
fun rankRoutes(routes: List<Route>, events: List<TrafficEvent>): List<RouteOption> {
  val via = RouteChoice.viaLabels(routes.map { r -> r.steps.map { RouteChoice.stepRoad(it.roadName, it.instruction, it.distance) } })
  val scored =
      routes.mapIndexed { i, r ->
        val geom = r.geometry.map { LatLon(it.lat, it.lng) }
        val (delay, closures) = RouteChoice.traffic(geom, events)
        RouteOption(r, ScoredRoute(r.steps.sumOf { it.duration }, r.distance, delay, closures, via[i]))
      }
  return RouteChoice.distinct(scored.sortedBy { it.score.totalSeconds }) { it.geometry }
}
