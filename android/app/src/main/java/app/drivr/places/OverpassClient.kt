package app.drivr.places

import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Places from OpenStreetMap through the Overpass API: free, open, no key.
 *
 * Built for speed on a phone, and so a slow or busy server can't leave the driver waiting:
 * - small queries (a few km around the car, or bounding boxes along the route);
 * - several public mirrors: the first two are asked at once, the next ones join after a short
 *   head start; the first good answer wins and the other requests are cancelled;
 * - a hard deadline per query (it fails with a clear reason instead of hanging);
 * - answers cached for a few minutes, and the last good answer is kept as a fallback.
 * For a real release, run your own instance (docker image wiktorn/overpass-api) or bundle POIs.
 */
class OverpassClient(
    private val http: OkHttpClient,
    private val endpoints: List<String> = DEFAULT_ENDPOINTS,
    private val cache: TtlCache<String, String> = TtlCache(ttlMs = 10 * 60_000L, maxEntries = 40),
    /** How many mirrors are asked right away; the rest join after [staggerMs]. */
    private val parallel: Int = 2,
    private val staggerMs: Long = 1500,
) {
  /** Up to [limit] places of [category] within [radiusM] of a point. */
  suspend fun nearby(category: PlaceCategory, at: LatLng, radiusM: Int = 3000, limit: Int = 25, deadlineMs: Long = 7000): List<Place> =
      parse(raw(buildQuery(category, listOf("around:$radiusM,${fmt(at.lat)},${fmt(at.lon)}"), limit, timeoutS = 6), deadlineMs))

  /** Places of [category] inside any of [boxes] (south, west, north, east). Bbox queries are cheap. */
  suspend fun inBoxes(category: PlaceCategory, boxes: List<BBox>, limit: Int = 120, deadlineMs: Long = 10_000): List<Place> {
    if (boxes.isEmpty()) return emptyList()
    return parse(raw(buildQuery(category, boxes.map { it.ql() }, limit, timeoutS = 9), deadlineMs))
  }

  /** Runs an Overpass QL query and returns the first good JSON answer within [deadlineMs]. */
  suspend fun raw(query: String, deadlineMs: Long = 10_000): String {
    cache[query]?.let { return it }
    // withTimeoutOrNull, not withTimeout: a timeout must surface as an error, not as a silent
    // cancellation that callers rethrow.
    val result =
        withTimeoutOrNull(deadlineMs) { race(query) }
            ?: throw PlacesException("The OpenStreetMap servers took longer than ${deadlineMs / 1000} s")
    cache[query] = result
    return result
  }

  /** Blocking form for background workers (speed cameras). */
  fun rawBlocking(query: String, deadlineMs: Long = 45_000): String = runBlocking { raw(query, deadlineMs) }

  private suspend fun race(query: String): String = coroutineScope {
    val winner = CompletableDeferred<String>()
    val errors = java.util.concurrent.ConcurrentLinkedQueue<Exception>()
    val jobs =
        endpoints.mapIndexed { i, url ->
          launch {
            // Later mirrors start after a head start, or right away once the earlier ones failed.
            if (i >= parallel) {
              withTimeoutOrNull(staggerMs * (i - parallel + 1)) {
                while (errors.size < i) delay(50)
              }
            }
            if (winner.isCompleted) return@launch
            try {
              winner.complete(post(url, query))
            } catch (e: CancellationException) {
              throw e
            } catch (e: Exception) {
              errors += e
              if (errors.size == endpoints.size) winner.completeExceptionally(PlacesException.from(errors.toList()))
            }
          }
        }
    try {
      winner.await()
    } finally {
      jobs.forEach { it.cancel() }
    }
  }

  private suspend fun post(url: String, query: String): String {
    val request = Request.Builder().url(url).post(FormBody.Builder().add("data", query).build()).build()
    val body = http.newCall(request).await { response ->
      if (!response.isSuccessful) throw PlacesException.http(response.code, url)
      response.body.string()
    }
    // Overpass reports server-side timeouts and overload inside a 200 answer.
    if (body.contains("\"remark\": \"runtime error") || body.contains("\"remark\":\"runtime error")) {
      throw PlacesException("OpenStreetMap server busy (${hostOf(url)})")
    }
    return body
  }

  companion object {
    /** Public Overpass mirrors, all with full planet data. */
    val DEFAULT_ENDPOINTS =
        listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
        )

    /**
     * Speed cameras use the mirrors in another order, so their long background queries never
     * take the slots a driver's fuel search needs on the main server (it allows ~2 per device).
     */
    val BACKGROUND_ENDPOINTS =
        listOf(
            "https://overpass.private.coffee/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
            "https://overpass-api.de/api/interpreter",
        )

    fun hostOf(url: String) = runCatching { java.net.URI(url).host }.getOrNull() ?: url

    private fun fmt(d: Double) = String.format(Locale.ROOT, "%.5f", d)

    fun buildQuery(category: PlaceCategory, areas: List<String>, limit: Int, timeoutS: Int = 10): String {
      val parts = areas.joinToString("") { area -> category.osmFilters.joinToString("") { "nwr$it($area);" } }
      return "[out:json][timeout:$timeoutS];($parts);out center tags qt $limit;"
    }

    /** Overpass JSON → places. Ways and relations come with a `center`. */
    fun parse(json: String): List<Place> {
      val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
      return (0 until elements.length()).mapNotNull { i ->
        val e = elements.getJSONObject(i)
        val tagsJson = e.optJSONObject("tags") ?: return@mapNotNull null
        val tags = tagsJson.keys().asSequence().associateWith { tagsJson.getString(it) }
        val center = e.optJSONObject("center")
        val lat = center?.optDouble("lat") ?: e.optDouble("lat")
        val lon = center?.optDouble("lon") ?: e.optDouble("lon")
        if (lat.isNaN() || lon.isNaN()) return@mapNotNull null
        val category = PlaceCategory.fromTags(tags)
        val name =
            tags["name"] ?: tags["brand"] ?: tags["operator"] ?: category?.let { defaultName(it) }
            ?: return@mapNotNull null
        Place(
            id = "${e.getString("type")}/${e.getLong("id")}",
            name = name,
            category = category,
            lat = lat,
            lon = lon,
            tags = tags,
            address = address(tags),
        )
      }
    }

    private fun defaultName(c: PlaceCategory) =
        when (c) {
          PlaceCategory.FUEL -> "Fuel station"
          PlaceCategory.CHARGING -> "Charging point"
          PlaceCategory.PARKING -> "Parking"
          PlaceCategory.REST -> "Rest area"
          else -> null
        }

    fun address(tags: Map<String, String>): String? {
      val street = listOfNotNull(tags["addr:street"], tags["addr:housenumber"]).joinToString(" ")
      val city = tags["addr:city"] ?: tags["addr:place"]
      return listOf(street, city ?: "").filter { it.isNotBlank() }.joinToString(", ").ifBlank { null }
    }
  }
}

/** A bounding box: south, west, north, east. */
data class BBox(val s: Double, val w: Double, val n: Double, val e: Double) {
  fun ql() = String.format(Locale.ROOT, "%.4f,%.4f,%.4f,%.4f", s, w, n, e)
}

/** A small thread-safe cache whose entries expire after [ttlMs]. */
class TtlCache<K : Any, V : Any>(private val ttlMs: Long, private val maxEntries: Int, private val now: () -> Long = System::currentTimeMillis) {
  private val map = LinkedHashMap<K, Pair<Long, V>>(16, 0.75f, true)

  @Synchronized
  operator fun get(key: K): V? {
    val (t, v) = map[key] ?: return null
    if (now() - t > ttlMs) {
      map.remove(key)
      return null
    }
    return v
  }

  @Synchronized
  operator fun set(key: K, value: V) {
    map[key] = now() to value
    while (map.size > maxEntries) map.remove(map.keys.first())
  }
}
