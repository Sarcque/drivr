package app.drivr.traffic

import android.util.Log
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Live border-check reports from the Autobahn GmbH des Bundes open traffic API
 * (verkehr.autobahn.de, the same data as the official "Autobahn App"; JSON, no key). One call per
 * German motorway that the route enters Germany on; answers are cached for [cacheMs].
 *
 * There is no public feed of where the police stand: German checks are only visible here once
 * they cause a queue that the motorway company reports. Dutch-side queues come from NDW.
 */
class BorderReportsClient(
    private val http: OkHttpClient,
    private val baseUrl: String = "https://verkehr.autobahn.de/o/autobahn",
    private val cacheMs: Long = 5 * 60_000L,
) {
  private class Cached(val at: Long, val reports: List<BorderReport>, val controls: List<ControlReport>)

  private val cache = ConcurrentHashMap<String, Cached>()

  /**
   * Reports for [crossings]. Null when no road could be fetched (offline or the API is down), so
   * the screen can say "no live info" instead of implying there is no check.
   */
  suspend fun reports(crossings: List<BorderCrossing>, moreRoads: List<String> = emptyList()): LiveChecks? =
      withContext(Dispatchers.IO) {
        val roads = (crossings.map { it.road }.filter { it.startsWith("A") } + moreRoads).distinct().take(MAX_ROADS)
        if (roads.isEmpty()) return@withContext LiveChecks(emptyList(), emptyList())
        val results = coroutineScope { roads.map { road -> async { runCatching { road(road) }.onFailure { Log.w(TAG, "Autobahn $road failed", it) }.getOrNull() } }.awaitAll() }
        if (results.all { it == null }) null
        else results.filterNotNull().let { r -> LiveChecks(r.flatMap { it.reports }, r.flatMap { it.controls }) }
      }

  private fun road(road: String): Cached {
    val now = System.currentTimeMillis()
    cache[road]?.takeIf { now - it.at < cacheMs }?.let { return it }
    val request = Request.Builder().url("$baseUrl/$road/services/warning").header("Accept", "application/json").build()
    val body =
        http.newCall(request).execute().use { r ->
          check(r.isSuccessful) { "HTTP ${r.code}" }
          r.body.string()
        }
    val controls = ArrayList<ControlReport>()
    val reports = BorderChecks.parseAutobahnWarnings(body, Instant.ofEpochMilli(now), controls = controls)
    return Cached(now, reports, controls).also { cache[road] = it }
  }

  companion object {
    private const val TAG = "BorderReports"
    /** At most this many roads per trip start: a handful of small requests. */
    private const val MAX_ROADS = 8
  }
}
