package app.drivr.places

import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Free-text search ("Albert Heijn", "IKEA Utrecht", "Stationsplein 1") with Photon, Komoot's open
 * geocoder over OSM: free, no key, search-as-you-type, results biased to where you are.
 * Self-hostable (github.com/komoot/photon) for a real release.
 */
class PhotonClient(
    private val http: OkHttpClient,
    private val baseUrl: String = "https://photon.komoot.io/api/",
) {
  fun search(query: String, near: LatLng?, limit: Int = 15): List<Place> {
    http.newCall(Request.Builder().url(searchUrl(query, near, limit)).header("User-Agent", USER_AGENT).build()).execute().use {
      check(it.isSuccessful) { "Search HTTP ${it.code}" }
      return parse(it.body.string())
    }
  }

  private fun searchUrl(query: String, near: LatLng?, limit: Int) =
      baseUrl.toHttpUrl().newBuilder().apply {
        addQueryParameter("q", query)
        addQueryParameter("limit", limit.toString())
        near?.let {
          addQueryParameter("lat", String.format(Locale.ROOT, "%.5f", it.lat))
          addQueryParameter("lon", String.format(Locale.ROOT, "%.5f", it.lon))
        }
        // Photon supports default/en/de/fr; "default" gives local names (Dutch in NL).
        val lang = Locale.getDefault().language
        if (lang in setOf("en", "de", "fr")) addQueryParameter("lang", lang)
      }.build()

  /**
   * Places of a category around a point, from Photon's reverse geocoder filtered by OSM tag. A
   * second, independent source next to Overpass: a different server, usually answering in well
   * under a second. Results carry only the main tag (no fuel types or opening hours).
   */
  suspend fun nearby(category: PlaceCategory, at: LatLng, radiusKm: Double, limit: Int = 20): List<Place> {
    val url =
        reverseUrl().newBuilder().apply {
          addQueryParameter("lat", String.format(Locale.ROOT, "%.5f", at.lat))
          addQueryParameter("lon", String.format(Locale.ROOT, "%.5f", at.lon))
          addQueryParameter("radius", String.format(Locale.ROOT, "%.1f", radiusKm))
          addQueryParameter("limit", limit.toString())
          osmTags(category).forEach { addQueryParameter("osm_tag", it) }
        }.build()
    return http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).await {
      if (!it.isSuccessful) throw PlacesException.http(it.code, url.toString())
      parse(it.body.string()).filter { p -> p.category == category || category == PlaceCategory.SHOPS }
    }
  }

  private fun reverseUrl() = baseUrl.removeSuffix("/").removeSuffix("api").removeSuffix("/").plus("/reverse").toHttpUrl()

  /** Typed search without blocking a thread. */
  suspend fun searchAsync(query: String, near: LatLng?, limit: Int = 15): List<Place> {
    val call = http.newCall(Request.Builder().url(searchUrl(query, near, limit)).header("User-Agent", USER_AGENT).build())
    return call.await {
      if (!it.isSuccessful) throw PlacesException.http(it.code, baseUrl)
      parse(it.body.string())
    }
  }

  companion object {
    /** Photon `osm_tag` filters (key:value) for each category. */
    fun osmTags(category: PlaceCategory): List<String> =
        when (category) {
          PlaceCategory.FUEL -> listOf("amenity:fuel")
          PlaceCategory.CHARGING -> listOf("amenity:charging_station")
          PlaceCategory.FOOD -> listOf("amenity:restaurant", "amenity:fast_food")
          PlaceCategory.COFFEE -> listOf("amenity:cafe")
          PlaceCategory.GROCERIES -> listOf("shop:supermarket", "shop:convenience")
          PlaceCategory.SHOPS -> listOf("shop:mall", "shop:department_store", "shop:bakery", "shop:chemist", "shop:doityourself", "shop:hardware", "shop:electronics")
          PlaceCategory.PARKING -> listOf("amenity:parking")
          PlaceCategory.REST -> listOf("highway:services", "highway:rest_area", "amenity:toilets")
        }

    const val USER_AGENT = "Drivr/0.2 (open-source navigation)"

    fun parse(json: String): List<Place> {
      val features = JSONObject(json).optJSONArray("features") ?: return emptyList()
      return (0 until features.length()).mapNotNull { i ->
        val f = features.getJSONObject(i)
        val p = f.getJSONObject("properties")
        val c = f.getJSONObject("geometry").getJSONArray("coordinates")
        val key = p.optString("osm_key")
        val value = p.optString("osm_value")
        val tags = if (key.isNotEmpty()) mapOf(key to value) else emptyMap()
        val street = listOf(p.optString("street"), p.optString("housenumber")).filter { it.isNotEmpty() }.joinToString(" ")
        val locality = p.optString("city").ifEmpty { p.optString("locality") }.ifEmpty { p.optString("county") }
        val name = p.optString("name").ifEmpty { street }.ifEmpty { return@mapNotNull null }
        val typeLetter = p.optString("osm_type")
        val type = when (typeLetter) { "N" -> "node"; "W" -> "way"; "R" -> "relation"; else -> "osm" }
        Place(
            id = "$type/${p.optLong("osm_id")}",
            name = name,
            category = PlaceCategory.fromTags(tags),
            lat = c.getDouble(1),
            lon = c.getDouble(0),
            tags = tags,
            address = listOf(street, locality).filter { it.isNotEmpty() && it != name }.joinToString(", ").ifEmpty { null },
        )
      }
    }
  }
}
