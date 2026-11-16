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
    val url =
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
    http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use {
      check(it.isSuccessful) { "Search HTTP ${it.code}" }
      return parse(it.body.string())
    }
  }

  companion object {
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
