package app.drivr.places

import java.util.Locale
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Places from OpenStreetMap through the Overpass API: free, open, no key.
 *
 * Built for speed on a phone: small queries (a few km around the car, or bounding boxes along
 * the route) with a short server timeout, sent to two public mirrors at once; the first good
 * answer wins and the other request is cancelled. Answers are cached for a few minutes. For a
 * real release, run your own instance (docker image wiktorn/overpass-api) or bundle POIs offline.
 */
class OverpassClient(
    private val http: OkHttpClient,
    private val endpoints: List<String> = DEFAULT_ENDPOINTS,
    private val cache: TtlCache<String, String> = TtlCache(ttlMs = 10 * 60_000L, maxEntries = 40),
) {
  /** Up to [limit] places of [category] within [radiusM] of a point. */
  fun nearby(category: PlaceCategory, at: LatLng, radiusM: Int = 3000, limit: Int = 25): List<Place> =
      run(buildQuery(category, listOf("around:$radiusM,${fmt(at.lat)},${fmt(at.lon)}"), limit, timeoutS = 8))

  /** Places of [category] inside any of [boxes] (south, west, north, east). Bbox queries are cheap. */
  fun inBoxes(category: PlaceCategory, boxes: List<BBox>, limit: Int = 120): List<Place> {
    if (boxes.isEmpty()) return emptyList()
    return run(buildQuery(category, boxes.map { it.ql() }, limit, timeoutS = 10))
  }

  private fun run(query: String): List<Place> = parse(raw(query))

  /** Runs an Overpass QL query on all mirrors at once and returns the first good JSON answer. */
  fun raw(query: String): String {
    cache[query]?.let { return it }
    val result = race(query)
    cache[query] = result
    return result
  }

  private fun race(query: String): String {
    val done = java.util.concurrent.CountDownLatch(1)
    val winner = java.util.concurrent.atomic.AtomicReference<String?>(null)
    val errors = java.util.concurrent.ConcurrentLinkedQueue<Exception>()
    val pending = java.util.concurrent.atomic.AtomicInteger(endpoints.size)
    val calls =
        endpoints.map { url ->
          http.newCall(Request.Builder().url(url).post(FormBody.Builder().add("data", query).build()).build())
        }
    calls.forEach { call ->
      call.enqueue(
          object : okhttp3.Callback {
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
              response.use {
                try {
                  check(it.isSuccessful) { "Overpass HTTP ${it.code}" }
                  val body = it.body.string()
                  // Overpass reports server-side timeouts inside a 200 answer.
                  check(!body.contains("\"remark\": \"runtime error")) { "Overpass busy" }
                  if (winner.compareAndSet(null, body)) done.countDown()
                } catch (e: Exception) {
                  fail(e)
                }
              }
            }

            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) = fail(e)

            private fun fail(e: Exception) {
              errors += e
              if (pending.decrementAndGet() == 0) done.countDown()
            }
          })
    }
    try {
      done.await()
    } catch (e: InterruptedException) {
      calls.forEach { it.cancel() }
      throw e
    }
    calls.forEach { it.cancel() }
    return winner.get() ?: throw (errors.firstOrNull() ?: IllegalStateException("No Overpass endpoint"))
  }

  companion object {
    val DEFAULT_ENDPOINTS =
        listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
        )

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
