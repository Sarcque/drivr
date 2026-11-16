package app.drivr.places

import java.time.Instant
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Pump prices at one station, from an official open-data feed. */
data class FuelPrices(
    /** Fuel label (same labels as [Place.FUEL_LABELS]) → euros per litre. */
    val perLitre: Map<String, Double>,
    val updated: Instant?,
    val source: String,
)

/** Where prices come from, for one country. All sources here are free and need no key. */
interface FuelPriceSource {
  val country: String

  fun covers(at: LatLng): Boolean

  /** Stations with prices near [at], keyed by their position (for matching to OSM). */
  fun near(at: LatLng, radiusKm: Int): List<Pair<LatLng, FuelPrices>>
}

/**
 * France: the government's "prix des carburants" feed (every station must report each price
 * change by law; refreshed every 10 minutes). Open licence, no key.
 */
class FranceFuelPrices(private val http: OkHttpClient) : FuelPriceSource {
  override val country = "France"

  override fun covers(at: LatLng): Boolean = Area.inFrance(at)

  /**
   * Rough outline of mainland France and Corsica, to pick the price source. It's a coarse box (it
   * includes western Flanders); the feed simply returns nothing outside France.
   */
  object Area {
    fun inFrance(at: LatLng): Boolean {
      val (lat, lon) = at
      if (lat !in 41.3..51.1 || lon !in -5.2..9.6) return false
      if (lat > 50.2 && lon > 4.2) return false // Belgium
      if (lat > 49.45 && lon > 5.8) return false // Luxembourg, Germany
      if (lat > 48.0 && lon > 8.25) return false // Germany east of the Rhine
      if (lat in 45.9..47.6 && lon > 7.0 && !(lat > 47.4 && lon < 7.6)) return false // Switzerland
      return true
    }
  }

  override fun near(at: LatLng, radiusKm: Int): List<Pair<LatLng, FuelPrices>> {
    val url =
        URL.toHttpUrl().newBuilder()
            .addQueryParameter(
                "where",
                String.format(Locale.ROOT, "within_distance(geom, geom'POINT(%.5f %.5f)', %dkm)", at.lon, at.lat, radiusKm))
            .addQueryParameter("limit", "100")
            .build()
    http.newCall(Request.Builder().url(url).build()).execute().use {
      check(it.isSuccessful) { "Prices HTTP ${it.code}" }
      return parse(it.body.string())
    }
  }

  companion object {
    const val URL =
        "https://data.economie.gouv.fr/api/explore/v2.1/catalog/datasets/" +
            "prix-des-carburants-en-france-flux-instantane-v2/records"

    private val FIELDS =
        listOf(
            "gazole" to "Diesel",
            "sp95" to "Euro 95",
            "e10" to "E10",
            "sp98" to "Super 98",
            "e85" to "E85",
            "gplc" to "LPG",
        )

    fun parse(json: String): List<Pair<LatLng, FuelPrices>> {
      val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
      return (0 until results.length()).mapNotNull { i ->
        val r = results.getJSONObject(i)
        val geom = r.optJSONObject("geom") ?: return@mapNotNull null
        val prices =
            FIELDS.mapNotNull { (key, label) ->
              r.optDouble("${key}_prix").takeIf { !it.isNaN() && it > 0 }?.let { label to it }
            }.toMap()
        if (prices.isEmpty()) return@mapNotNull null
        val updated =
            FIELDS.mapNotNull { (key, _) ->
              r.optString("${key}_maj").takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it) }.getOrNull() }
            }.maxOrNull()
        LatLng(geom.getDouble("lat"), geom.getDouble("lon")) to
            FuelPrices(prices, updated, "prix-carburants.gouv.fr")
      }
    }
  }
}

/**
 * Matches official price records to OSM fuel stations by position (within [matchM]), so a station
 * found through OSM shows its pump prices where the country publishes them.
 *
 * Coverage, honestly: France has a free keyless national feed (wired up here). Spain, Italy and
 * Austria also publish free keyless feeds (not yet wired). Germany's MTS-K feed needs a free key
 * (Tankerkönig). The Netherlands and Belgium publish no per-station prices at all, only national
 * daily averages (CBS), so Drivr shows fuel types, brand and hours there but no prices.
 */
class FuelPriceRepository(private val sources: List<FuelPriceSource>, private val matchM: Double = 120.0) {
  fun sourceFor(at: LatLng): FuelPriceSource? = sources.firstOrNull { it.covers(at) }

  fun pricesFor(stations: List<Place>, near: LatLng): Map<String, FuelPrices> {
    val source = sourceFor(near) ?: return emptyMap()
    val records = runCatching { source.near(near, 25) }.getOrElse { return emptyMap() }
    return match(stations, records)
  }

  fun match(stations: List<Place>, records: List<Pair<LatLng, FuelPrices>>): Map<String, FuelPrices> =
      stations.mapNotNull { s ->
        val here = LatLng(s.lat, s.lon)
        records.minByOrNull { RouteCorridor.distanceM(here, it.first) }
            ?.takeIf { RouteCorridor.distanceM(here, it.first) <= matchM }
            ?.let { s.id to it.second }
      }.toMap()
}
