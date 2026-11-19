package app.drivr.traffic

import kotlin.math.cos
import kotlin.math.hypot

/**
 * One way to get there, scored with live traffic. Valhalla's own time has no live traffic, so
 * [trafficSeconds] adds what NDW reports on this route's roads (jams, slow traffic) and [closures]
 * counts reported road closures on it.
 */
data class ScoredRoute(
    val baseSeconds: Double,
    val distanceM: Double,
    val trafficSeconds: Double,
    val closures: Int,
    /** The road that sets this route apart from the others, e.g. "A2"; null if none stands out. */
    val via: String? = null,
    /** Border check on this route (or the one it goes around), with any live report. */
    val border: BorderInfo? = null,
    /** Waiting at the border check reported live and not already in [trafficSeconds]. */
    val borderDelaySeconds: Double = 0.0,
) {
  /** Expected time with traffic. A closure costs a lot: it usually means a long detour. */
  val totalSeconds: Double
    get() = baseSeconds + trafficSeconds + borderDelaySeconds + closures * CLOSURE_PENALTY_S

  companion object {
    const val CLOSURE_PENALTY_S = 20 * 60.0
  }
}

/** A route's step, reduced to what the labels need. */
data class StepRoad(val name: String?, val distanceM: Double)

/**
 * Compares routes with live traffic: which is quickest now, what sets each apart ("via A2"), and
 * whether a new route is worth switching to mid-trip.
 */
object RouteChoice {
  /** Average speeds used when NDW gives a jam's length but no delay. */
  private const val FREE_FLOW_MS = 100 / 3.6
  private const val JAM_MS = 15 / 3.6
  private const val SLOW_MS = 40 / 3.6

  /** A switch must save at least this much, and [MIN_SAVING_FRACTION] of the trip, to be offered. */
  const val MIN_SAVING_S = 3 * 60.0
  const val MIN_SAVING_FRACTION = 0.05

  /** Delay from the traffic on [route] and the number of closures on it. */
  fun traffic(route: List<LatLon>, events: List<TrafficEvent>): Pair<Double, Int> {
    if (route.size < 2 || events.isEmpty()) return 0.0 to 0
    var delay = 0.0
    var closures = 0
    val ahead = RouteTraffic.ahead(route, events)
    // Stretches with moving (if slow) traffic reported on them.
    val moving =
        ahead.filter { it.event.kind != TrafficKind.ROAD_CLOSED && it.endM != null }.map { it.distanceM..it.endM!! }
    for (a in ahead) {
      if (a.event.kind == TrafficKind.ROAD_CLOSED) {
        // A closure only blocks us if it covers the road we drive (both ends on the route), and
        // NDW doesn't also report a jam there: traffic queuing through it means it's a lane or
        // shoulder closure, not the whole road (seen on the A27 at Gorinchem).
        val end = a.endM ?: continue
        if (moving.none { it.start < end && a.distanceM < it.endInclusive }) closures++
        continue
      }
      delay += delayOf(a)
    }
    return delay to closures
  }

  /** NDW's reported delay, or an estimate from the length still ahead of us when it gives none. */
  fun delayOf(a: TrafficAhead): Double {
    val e = a.event
    e.delaySeconds?.let { reported ->
      // Inside the jam already: only the part still ahead counts.
      val len = a.lengthM
      val left = a.endM
      return if (a.inside && len != null && left != null && len > 0) reported * (left / len).coerceIn(0.0, 1.0)
      else reported
    }
    val length =
        when {
          a.endM != null -> (a.endM - a.distanceM.coerceAtLeast(0.0)).coerceAtLeast(0.0)
          else -> e.queueLengthM ?: return 0.0
        }
    val speed =
        when (e.kind) {
          TrafficKind.JAM -> JAM_MS
          TrafficKind.SLOW -> SLOW_MS
          else -> return 0.0
        }
    return length / speed - length / FREE_FLOW_MS
  }

  /**
   * For each route, the road name that is most its own: long on this route, short or absent on
   * the others. Prefers road numbers (A2, N201) over street names, which drivers recognise.
   */
  fun viaLabels(routes: List<List<StepRoad>>): List<String?> {
    val byName =
        routes.map { steps ->
          steps.filter { !it.name.isNullOrBlank() }.groupBy { primaryName(it.name!!) }.mapValues { (_, s) -> s.sumOf { it.distanceM } }
        }
    return byName.mapIndexed { i, mine ->
      mine.entries
          .map { (name, d) ->
            val elsewhere = byName.withIndex().filter { it.index != i }.maxOfOrNull { it.value[name] ?: 0.0 } ?: 0.0
            val bonus = if (ROAD_NUMBER.matches(name)) 1.5 else 1.0
            Triple(name, (d - elsewhere) * bonus, d - elsewhere)
          }
          .filter { it.third > 1000 }
          .maxByOrNull { it.second }
          ?.first
    }
  }

  /**
   * The road a step drives on. Valhalla leaves the name empty on motorways and puts the number in
   * the instruction ("Take the Knooppunt Empel exit onto A2 toward Utrecht", or "Neem afrit ...
   * naar A2"), so prefer the first road number there; it works in every language.
   */
  fun stepRoad(roadName: String?, instruction: String?, distanceM: Double): StepRoad {
    val number = instruction?.let { INSTRUCTION_ROAD.find(it)?.value }
    return StepRoad(number ?: roadName?.takeIf { it.isNotBlank() }, distanceM)
  }

  private val INSTRUCTION_ROAD = Regex("""\b[AENS]\d{1,3}\b""")

  /** "A27; A28" or "Ring Utrecht (A27)": the road number when there is one, else the first name. */
  internal fun primaryName(raw: String): String {
    val parts = raw.split(';', '/', '(', ')').map { it.trim() }.filter { it.isNotEmpty() }
    return parts.firstOrNull { ROAD_NUMBER.matches(it) } ?: parts.firstOrNull() ?: raw.trim()
  }

  private val ROAD_NUMBER = Regex("""[AENS]\d{1,3}""")

  /**
   * Of [candidates], the one worth switching to from [current] (the route being driven, from the
   * car on), or null. It must save at least [MIN_SAVING_S] and [MIN_SAVING_FRACTION] of the time
   * left, and really be a different way ([currentGeometry] vs. each candidate's).
   */
  fun <T> betterThan(
      current: ScoredRoute,
      currentGeometry: List<LatLon>,
      candidates: List<Pair<T, ScoredRoute>>,
      geometryOf: (T) -> List<LatLon>,
  ): Pair<T, ScoredRoute>? {
    val need = maxOf(MIN_SAVING_S, current.totalSeconds * MIN_SAVING_FRACTION)
    return candidates
        .filter { (_, s) -> current.totalSeconds - s.totalSeconds >= need }
        .filter { (r, _) -> overlap(geometryOf(r), currentGeometry) < SAME_ROUTE_OVERLAP }
        .minByOrNull { it.second.totalSeconds }
  }

  /** Above this share of shared road, two routes count as the same way. */
  const val SAME_ROUTE_OVERLAP = 0.9

  /** Share of [a]'s length (sampled every ~100 m) that lies within [toleranceM] of [b]. */
  fun overlap(a: List<LatLon>, b: List<LatLon>, toleranceM: Double = 30.0): Double {
    if (a.size < 2 || b.size < 2) return 0.0
    val o = a.first()
    val pa = a.map { xy(it, o) }
    val pb = b.map { xy(it, o) }
    var total = 0.0
    var shared = 0.0
    for (i in 1 until pa.size) {
      val s = pa[i - 1]
      val e = pa[i]
      val len = hypot(e[0] - s[0], e[1] - s[1])
      val n = maxOf(1, (len / 100).toInt())
      for (k in 0 until n) {
        val t = (k + 0.5) / n
        val p = doubleArrayOf(s[0] + (e[0] - s[0]) * t, s[1] + (e[1] - s[1]) * t)
        total += len / n
        if (distanceTo(pb, p) <= toleranceM) shared += len / n
      }
    }
    return if (total == 0.0) 1.0 else shared / total
  }

  /**
   * Routes that are really the same way as an earlier (better) one are dropped. [comparable] says
   * whether two routes may be duplicates at all (e.g. not when they cross the border elsewhere).
   */
  fun <T> distinct(ranked: List<T>, geometryOf: (T) -> List<LatLon>): List<T> = distinct(ranked, geometryOf) { _, _ -> true }

  fun <T> distinct(ranked: List<T>, geometryOf: (T) -> List<LatLon>, comparable: (T, T) -> Boolean): List<T> {
    val kept = ArrayList<T>()
    for (r in ranked) {
      val g = geometryOf(r)
      if (kept.none { comparable(r, it) && overlap(g, geometryOf(it)) >= SAME_ROUTE_OVERLAP && overlap(geometryOf(it), g) >= SAME_ROUTE_OVERLAP }) kept += r
    }
    return kept
  }

  private fun distanceTo(poly: List<DoubleArray>, p: DoubleArray): Double {
    var best = Double.MAX_VALUE
    for (i in 0 until poly.size - 1) {
      val a = poly[i]
      val b = poly[i + 1]
      val dx = b[0] - a[0]
      val dy = b[1] - a[1]
      val l2 = dx * dx + dy * dy
      val t = if (l2 == 0.0) 0.0 else (((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / l2).coerceIn(0.0, 1.0)
      val d = hypot(a[0] + dx * t - p[0], a[1] + dy * t - p[1])
      if (d < best) best = d
    }
    return best
  }

  private fun xy(p: LatLon, o: LatLon): DoubleArray {
    val r = 6_371_008.8
    return doubleArrayOf(
        Math.toRadians(p.lon - o.lon) * r * cos(Math.toRadians(o.lat)),
        Math.toRadians(p.lat - o.lat) * r,
    )
  }
}
