package app.drivr.places

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** One step of a progressive search: results so far, and whether more are coming. */
data class PlacesUpdate(val results: List<PlaceResult>, val done: Boolean, val note: String? = null)

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

  /**
   * Places of a category in two steps, so the driver sees something within a second or two:
   * 1. A few places within ~3 km of the car (small, fast query), with route info where they fit.
   * 2. When navigating: the next [aheadM] of the route, as a few bounding boxes, filtered to the
   *    corridor and merged in driving order. If step 2 fails, step 1's results stay.
   * Not navigating and nothing close: one wider nearby search.
   */
  fun byCategory(
      category: PlaceCategory,
      here: LatLng,
      route: List<LatLng>?,
      aheadM: Double = 40_000.0,
  ): Flow<PlacesUpdate> = flow {
    val corridor = corridorFor(category).toDouble()
    val nearRadius = if (category == PlaceCategory.PARKING) 1000 else 3000
    val navigating = route != null && route.size >= 2
    val near =
        runCatching { overpass.nearby(category, here, nearRadius, limit = 20) }
            .getOrElse { e -> if (!navigating) throw e else emptyList() }
    val nearRanked = rankNearby(near, here, route, corridor)
    if (!navigating) {
      if (nearRanked.size >= 3) {
        emit(PlacesUpdate(nearRanked, done = true))
      } else {
        emit(PlacesUpdate(nearRanked, done = false))
        val wide = overpass.nearby(category, here, 10_000, limit = 20)
        emit(PlacesUpdate(rankNearby(wide, here, null, corridor), done = true))
      }
      return@flow
    }
    emit(PlacesUpdate(nearRanked, done = false))
    val boxes = RouteCorridor(route!!).boxes(0.0, aheadM, padM = corridor)
    val along = runCatching { overpass.inBoxes(category, boxes) }
    along.onSuccess { found ->
      val merged = rank(near + found, here, route, corridor)
      emit(PlacesUpdate(merged, done = true))
    }
    along.onFailure {
      emit(PlacesUpdate(nearRanked, done = true, note = "Showing places near you; the route search timed out."))
    }
  }.flowOn(Dispatchers.IO)

  /** Near the car: on-route places first (in driving order), then the rest by distance. */
  private fun rankNearby(places: List<Place>, here: LatLng, route: List<LatLng>?, corridorM: Double): List<PlaceResult> {
    val all = rank(places, here, route, null)
    val onRoute = all.filter { it.offRouteM != null && it.offRouteM <= corridorM && (it.alongRouteM ?: 0.0) > 0 }
        .sortedBy { it.alongRouteM }
    return onRoute + (all - onRoute.toSet()).map { it.copy(alongRouteM = null, offRouteM = null, detourS = null) }
        .sortedBy { it.distanceM }
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
