package app.drivr.places

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A place, ranked for the driver: how far, and (when navigating) where along the route. */
data class PlaceResult(
    val place: Place,
    /** Straight-line distance from the car. */
    val distanceM: Double,
    /** Distance along the route to the point nearest the place; null when not navigating. */
    val alongRouteM: Double? = null,
    /** How far the place is off the route. */
    val offRouteM: Double? = null,
    /** Estimated extra time to stop there (see [RouteCorridor.estimateDetourS]). */
    val detourS: Double? = null,
    val prices: FuelPrices? = null,
)

/**
 * One entry point for "find me fuel / food / a supermarket", near the car or along the route.
 * OSM via Overpass for categories, Photon for typed searches, official feeds for fuel prices.
 */
class PlacesRepository(
    private val overpass: OverpassClient,
    private val photon: PhotonClient,
    private val prices: FuelPriceRepository,
) {
  /** The country whose official price feed covers [at], or null where none exists (e.g. NL). */
  fun priceCountry(at: LatLng): String? = prices.sourceFor(at)?.country

  /** Places of a category: along the next [aheadM] of [route] when navigating, else around [here]. */
  suspend fun byCategory(
      category: PlaceCategory,
      here: LatLng,
      route: List<LatLng>?,
      aheadM: Double = 100_000.0,
  ): List<PlaceResult> =
      withContext(Dispatchers.IO) {
        val places =
            if (route != null && route.size >= 2) {
              val line = RouteCorridor(route).sample(aheadM)
              overpass.alongLine(category, line, corridorFor(category))
            } else {
              overpass.nearby(category, here, if (category == PlaceCategory.PARKING) 2000 else 6000)
            }
        rank(places, here, route, corridorFor(category).toDouble())
      }

  /** Typed search ("Albert Heijn", "IKEA"), biased to the car's position. */
  suspend fun search(text: String, here: LatLng, route: List<LatLng>?): List<PlaceResult> =
      withContext(Dispatchers.IO) {
        val places = photon.search(text, here)
        // Typed searches can be anywhere, so keep everything; just add route info where it fits.
        rank(places, here, route, corridorM = null)
      }

  fun rank(
      places: List<Place>,
      here: LatLng,
      route: List<LatLng>?,
      corridorM: Double?,
  ): List<PlaceResult> {
    val fuel = places.filter { it.category == PlaceCategory.FUEL }
    val fuelPrices = if (fuel.isEmpty()) emptyMap() else prices.pricesFor(fuel, here)
    return rankPlaces(places, here, route, corridorM, fuelPrices)
  }

  companion object {
    fun corridorFor(category: PlaceCategory): Int =
        when (category) {
          PlaceCategory.FUEL, PlaceCategory.CHARGING, PlaceCategory.REST -> 2000
          PlaceCategory.PARKING -> 600
          else -> 1200
        }
  }
}

/**
 * Ranks places for the driver. With a route and a corridor: only places within the corridor and
 * ahead of the car, in driving order. Without a route: nearest first. With a route but no
 * corridor (typed search): source order, with route info added where a place is near the route.
 */
fun rankPlaces(
    places: List<Place>,
    here: LatLng,
    route: List<LatLng>?,
    corridorM: Double?,
    fuelPrices: Map<String, FuelPrices> = emptyMap(),
): List<PlaceResult> {
  val corridor = route?.takeIf { it.size >= 2 }?.let { RouteCorridor(it) }
  val nearRouteM = corridorM ?: 2000.0
  val results =
      places.distinctBy { it.id }.map { p ->
        val at = LatLng(p.lat, p.lon)
        val pos = corridor?.position(at)?.takeIf { it.offsetM <= nearRouteM }
        PlaceResult(
            place = p,
            distanceM = RouteCorridor.distanceM(here, at),
            alongRouteM = pos?.alongM,
            offRouteM = pos?.offsetM,
            detourS = pos?.offsetM?.let { RouteCorridor.estimateDetourS(it) },
            prices = fuelPrices[p.id],
        )
      }
  return when {
    // Along the route: in driving order, skipping what's already behind (first 200 m).
    corridor != null && corridorM != null ->
        results.filter { (it.alongRouteM ?: 0.0) > 200 }.sortedBy { it.alongRouteM }
    corridorM != null -> results.sortedBy { it.distanceM }
    else -> results // Photon's own relevance order
  }
}
