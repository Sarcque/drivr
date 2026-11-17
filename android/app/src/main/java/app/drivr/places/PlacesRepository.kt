package app.drivr.places

import app.drivr.i18n.L10n
import app.drivr.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    /** Last good nearby answer per category and ~1 km cell, kept 2 h as a fallback when offline. */
    private val lastGood: TtlCache<String, List<Place>> = TtlCache(ttlMs = 2 * 3600_000L, maxEntries = 60),
    /** Fresh answers reused without asking again. */
    private val fresh: TtlCache<String, List<Place>> = TtlCache(ttlMs = 5 * 60_000L, maxEntries = 60),
) {
  /** The country whose official price feed covers [at], or null where none exists (e.g. NL). */
  fun priceCountry(at: LatLng): String? = prices.sourceFor(at)?.country

  /**
   * Places of a category in steps, so the driver sees something within a second or two and is
   * never left with an endless spinner:
   * 1. Cached places for this spot, at once, if any.
   * 2. Places within a few km of the car: Overpass (mirrors raced) and Photon (a separate server)
   *    race; the first useful answer wins. Hard deadline ~9 s, then a clear error, or the last
   *    good answer for this spot with a note.
   * 3. When navigating: the next [aheadM] of the route, as a few bounding boxes, filtered to the
   *    corridor and merged in driving order. If step 3 fails, the earlier results stay.
   * Not navigating and little close by: one wider nearby search, also optional.
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
    val key = cellKey(category, here, nearRadius)

    val near: List<Place> =
        fresh[key] ?: run {
          lastGood[key]?.let { emit(PlacesUpdate(rankNearby(it, here, route, corridor), done = false)) }
          try {
            nearbyAnySource(category, here, nearRadius).also {
              fresh[key] = it
              lastGood[key] = it
            }
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            val stale = lastGood[key]
            when {
              stale != null -> {
                emit(PlacesUpdate(rankNearby(stale, here, route, corridor), done = true,
                    note = L10n.s(R.string.places_note_stale, PlacesException.reason(e))))
                return@flow
              }
              navigating -> emptyList()
              else -> throw e
            }
          }
        }
    val nearRanked = rankNearby(near, here, route, corridor)
    if (!navigating) {
      if (nearRanked.size >= 3) {
        emit(PlacesUpdate(nearRanked, done = true))
      } else {
        emit(PlacesUpdate(nearRanked, done = false))
        val wide = runCatchingNonCancel { nearbyAnySource(category, here, 10_000) }
        emit(
            wide.fold(
                { PlacesUpdate(rankNearby(near + it, here, null, corridor), done = true) },
                { PlacesUpdate(nearRanked, done = true, note = if (nearRanked.isEmpty()) L10n.s(R.string.places_note_wider_failed, PlacesException.reason(it)) else null) },
            ))
      }
      return@flow
    }
    emit(PlacesUpdate(nearRanked, done = false))
    val boxes = RouteCorridor(route!!).boxes(0.0, aheadM, padM = corridor)
    val along = runCatchingNonCancel { overpass.inBoxes(category, boxes, deadlineMs = 12_000) }
    along.onSuccess { found ->
      val merged = rank(near + found, here, route, corridor)
      emit(PlacesUpdate(merged, done = true))
    }
    along.onFailure {
      emit(PlacesUpdate(nearRanked, done = true, note = L10n.s(R.string.places_note_route_failed, PlacesException.reason(it))))
    }
  }.flowOn(Dispatchers.IO)

  /**
   * Nearby places from whichever source answers first with something useful: Overpass (full
   * details) right away, Photon after a short head start (or at once if Overpass already failed).
   * An empty answer only counts once every source has answered.
   */
  private suspend fun nearbyAnySource(category: PlaceCategory, here: LatLng, radiusM: Int): List<Place> =
      withTimeoutOrNull(NEARBY_DEADLINE_MS) {
        val fromOverpass: suspend () -> List<Place> = {
          overpass.nearby(category, here, radiusM, limit = 20, deadlineMs = 8_000)
        }
        val fromPhoton: suspend () -> List<Place> = {
          photon.nearby(category, here, radiusKm = radiusM / 1000.0, limit = 20)
        }
        firstUseful(
            listOf(0L to fromOverpass, PHOTON_HEAD_START_MS to fromPhoton),
            useful = { it.isNotEmpty() },
        )
      } ?: throw PlacesException(L10n.s(R.string.places_err_no_answer, (NEARBY_DEADLINE_MS / 1000).toInt()))

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
        val places =
            withTimeoutOrNull(8_000) { photon.searchAsync(text, here) }
                ?: throw PlacesException(L10n.s(R.string.places_err_search_slow, 8))
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
    const val NEARBY_DEADLINE_MS = 9_000L
    const val PHOTON_HEAD_START_MS = 2_000L

    fun corridorFor(category: PlaceCategory): Int =
        when (category) {
          PlaceCategory.FUEL, PlaceCategory.CHARGING, PlaceCategory.REST -> 2000
          PlaceCategory.PARKING -> 600
          else -> 1200
        }

    /** Cache key: category, radius and a ~1 km grid cell, so small moves reuse the answer. */
    fun cellKey(category: PlaceCategory, at: LatLng, radiusM: Int): String =
        "${category.name}/$radiusM/${Math.round(at.lat * 100)}/${Math.round(at.lon * 60)}"
  }
}

/** runCatching that never swallows cancellation. */
inline fun <T> runCatchingNonCancel(block: () -> T): Result<T> =
    try {
      Result.success(block())
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Result.failure(e)
    }

/**
 * Runs [sources] (each after its head start, or at once when every earlier one has finished) and
 * returns the first result that is [useful]. If none is, returns the first result that came back
 * at all; if every source failed, throws one [PlacesException] naming the causes.
 */
suspend fun <T> firstUseful(sources: List<Pair<Long, suspend () -> T>>, useful: (T) -> Boolean): T = coroutineScope {
  val winner = CompletableDeferred<T>()
  val finished = java.util.concurrent.atomic.AtomicInteger(0)
  val errors = java.util.concurrent.ConcurrentLinkedQueue<Exception>()
  val fallback = java.util.concurrent.atomic.AtomicReference<Pair<T, Int>?>(null)
  fun settleIfAllDone() {
    if (finished.incrementAndGet() < sources.size) return
    val fb = fallback.get()
    if (fb != null) winner.complete(fb.first) else winner.completeExceptionally(PlacesException.from(errors.toList()))
  }
  val jobs =
      sources.mapIndexed { i, (headStartMs, run) ->
        launch {
          if (headStartMs > 0) withTimeoutOrNull(headStartMs) { while (finished.get() < i) delay(50) }
          if (winner.isCompleted) return@launch
          try {
            val r = run()
            if (useful(r)) winner.complete(r) else fallback.compareAndSet(null, r to i)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            errors += e
          }
          settleIfAllDone()
        }
      }
  try {
    winner.await()
  } finally {
    jobs.forEach { it.cancel() }
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
