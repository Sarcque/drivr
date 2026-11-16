package app.drivr.places

import java.util.Locale
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Places from OpenStreetMap through the Overpass API: free, open, no key. Two public instances
 * are tried in turn. For a real release, run your own (docker image wiktorn/overpass-api) or
 * bundle POIs in the offline map; the query format stays the same.
 */
class OverpassClient(
    private val http: OkHttpClient,
    private val endpoints: List<String> = DEFAULT_ENDPOINTS,
) {
  /** Places of [category] within [radiusM] of a point. */
  fun nearby(category: PlaceCategory, at: LatLng, radiusM: Int = 5000): List<Place> =
      run(buildQuery(category, "around:$radiusM,${fmt(at.lat)},${fmt(at.lon)}"))

  /**
   * Places of [category] within [corridorM] of a polyline (Overpass treats `around` with several
   * coordinates as a line), e.g. the next 80 km of the route.
   */
  fun alongLine(category: PlaceCategory, line: List<LatLng>, corridorM: Int = 1500): List<Place> {
    require(line.isNotEmpty())
    val coords = line.joinToString(",") { "${fmt(it.lat)},${fmt(it.lon)}" }
    return run(buildQuery(category, "around:$corridorM,$coords"))
  }

  private fun run(query: String): List<Place> {
    var last: Exception? = null
    for (url in endpoints) {
      try {
        val req = Request.Builder().url(url).post(FormBody.Builder().add("data", query).build()).build()
        http.newCall(req).execute().use { res ->
          check(res.isSuccessful) { "Overpass HTTP ${res.code}" }
          return parse(res.body.string())
        }
      } catch (e: Exception) {
        last = e
      }
    }
    throw last ?: IllegalStateException("No Overpass endpoint")
  }

  companion object {
    val DEFAULT_ENDPOINTS =
        listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
        )

    private fun fmt(d: Double) = String.format(Locale.ROOT, "%.5f", d)

    fun buildQuery(category: PlaceCategory, area: String, limit: Int = 200): String {
      val parts = category.osmFilters.joinToString("") { "nwr$it($area);" }
      return "[out:json][timeout:25];($parts);out center tags $limit;"
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
