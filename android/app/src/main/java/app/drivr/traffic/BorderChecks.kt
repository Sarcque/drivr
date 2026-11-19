package app.drivr.traffic

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import org.json.JSONArray
import org.json.JSONObject

/**
 * A road crossing between the Netherlands and Germany where the German federal police
 * (Bundespolizei) have run stationary checks since September 2024 (extended to March 2027).
 * Coordinates are where Valhalla's OSM road graph changes country on that road (found by routing
 * across it and reading the edge admin data), so they sit exactly on the road the route uses.
 *
 * Checks are not staffed around the clock and the police do not publish when or where they stand,
 * so a crossing on the route only means "a check is possible here". A delay is shown only when a
 * live source reports one (see [BorderReport]).
 */
data class BorderCrossing(
    val id: String,
    /** Place drivers know it by, e.g. "Elten". */
    val name: String,
    /** Road on the German side, e.g. "A3"; the Autobahn GmbH feed is asked for this road. */
    val road: String,
    val at: LatLon,
    /** Direction of travel into Germany at the crossing, degrees from north. */
    val intoGermanyBearing: Double,
    /** Big motorway crossing with a fixed checkpoint (as opposed to spot checks on small roads). */
    val stationary: Boolean = true,
    /**
     * Words in an Autobahn GmbH direction ("Arnheim -> Oberhausen") that mean "coming from the
     * Netherlands": a jam right at the crossing in that direction is the queue for the check.
     */
    val fromNl: List<String> = emptyList(),
) {
  /** "Elten (A3)". */
  val label: String get() = "$name ($road)"
}

/** Which way a route goes over a [BorderCrossing]. */
enum class CrossingDirection { INTO_GERMANY, INTO_NETHERLANDS }

/** A crossing on a route: where along it, and which way. */
data class CrossingOnRoute(val crossing: BorderCrossing, val alongM: Double, val direction: CrossingDirection)

/** Where the live report came from: always shown on screen with its age. */
enum class BorderSource { AUTOBAHN_GMBH, NDW }

/**
 * A live report of a border check (or the queue in front of it), from an official public feed.
 * [delaySeconds] is null when the report names the check but gives no delay.
 */
data class BorderReport(
    val crossingId: String,
    val source: BorderSource,
    val delaySeconds: Double?,
    /** When the situation started, per the source. */
    val since: Instant?,
    /** When we fetched it. Age on screen is from this, so stale data looks stale. */
    val fetchedAt: Instant,
    val text: String,
    /** The source says it is a border check (not just a queue right at the border). */
    val namesCheck: Boolean = false,
)

/** What the route-choice card says about border checks on one route. */
data class BorderInfo(
    /** The crossing this route uses where a check is possible (into Germany), or null. */
    val crossing: BorderCrossing?,
    /** The best live report for it, or null when nothing is reported (or we're offline). */
    val report: BorderReport? = null,
    /** This route was asked to go around this crossing's checkpoint. */
    val avoids: BorderCrossing? = null,
    /** False when the live sources couldn't be reached (offline): "no live info", not "no check". */
    val liveChecked: Boolean = true,
    /** Other reported checks (police, traffic) on this route. */
    val controlsOnRoute: List<ControlReport> = emptyList(),
    /** How many reported checks this route was asked to go around. */
    val avoidsControls: Int = 0,
)

/**
 * A reported check that isn't a border check (police or traffic check on a motorway), from the
 * Autobahn GmbH feed: the only open, official, live source for these we found. Dutch police and
 * the Marechaussee publish none.
 */
data class ControlReport(val at: LatLon, val since: Instant?, val fetchedAt: Instant, val text: String)

/** Everything the live feeds said, for one trip start. */
data class LiveChecks(val border: List<BorderReport>, val controls: List<ControlReport>)

object BorderChecks {

  /**
   * NL → DE crossings on the routes Valhalla actually takes (probed Nov 2026). The A1/A30 at Bad
   * Bentheim is missing on purpose: the public Valhalla server routes Hengelo → Osnabrück over the
   * local Bentheimerstraat, so no route uses it today.
   */
  val NL_DE: List<BorderCrossing> =
      listOf(
          BorderCrossing("elten-a3", "Elten", "A3", LatLon(51.901117, 6.162930), 147.0, fromNl = listOf("Arnheim", "Arnhem", "Emmerich-Elten", "Elten")),
          BorderCrossing("venlo-a40", "Venlo", "A40", LatLon(51.384019, 6.216336), 117.0, fromNl = listOf("Venlo", "Straelen")),
          BorderCrossing("venlo-a61", "Venlo-Zuid", "A61", LatLon(51.333058, 6.169540), 114.0, fromNl = listOf("Venlo", "Kaldenkirchen")),
          BorderCrossing("vetschau-a4", "Bocholtz", "A4", LatLon(50.818581, 6.024023), 147.0, fromNl = listOf("Heerlen", "Vetschau")),
          BorderCrossing("gennep-a57", "Gennep", "A57", LatLon(51.674718, 6.034317), 92.0, fromNl = listOf("Nimwegen", "Nijmegen", "Gennep")),
          BorderCrossing("bunde-a280", "Bad Nieuweschans", "A280", LatLon(53.179830, 7.224914), 69.0, fromNl = listOf("Groningen", "Bad Nieuweschans")),
          BorderCrossing("elmpt-a52", "Elmpt", "A52", LatLon(51.200481, 6.070984), 67.0, fromNl = listOf("Roermond")),
          BorderCrossing("gronau-b54", "Glanerbrug", "B54", LatLon(52.193417, 6.962282), 114.0, stationary = false),
      )

  /** A route within this distance of a crossing point goes through it. */
  private const val ON_ROUTE_M = 60.0

  /**
   * The checkpoint area kept out of the "around the check" route: a square this many metres out.
   * Small on purpose: Valhalla caps all exclude polygons together at 10 km of outline, and the
   * crossing point lies on the road, so 150 m covers both carriageways.
   */
  const val AVOID_RADIUS_M = 150.0

  /** Valhalla's limit on the summed outline of all exclude polygons, with a margin. */
  private const val MAX_EXCLUDE_OUTLINE_M = 9_500.0

  /** Reports naming a border check this far (straight line) from a crossing count for it. */
  private const val REPORT_RADIUS_M = 12_000.0

  /** A plain jam this close to a crossing, coming from the Netherlands, is the queue for the check. */
  private const val QUEUE_RADIUS_M = 4_000.0

  /** Reports older than this are dropped, whatever the source says. */
  const val MAX_REPORT_AGE_S = 3 * 3600L

  /** Crossings on [route], in driving order, with the direction we cross them. */
  fun crossingsOn(route: List<LatLon>, crossings: List<BorderCrossing> = NL_DE): List<CrossingOnRoute> {
    if (route.size < 2) return emptyList()
    val o = route.first()
    val pts = route.map { xy(it, o) }
    val cum = DoubleArray(pts.size)
    for (i in 1 until pts.size) cum[i] = cum[i - 1] + hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1])
    return crossings
        .mapNotNull { c ->
          val p = xy(c.at, o)
          var best = Double.MAX_VALUE
          var bestAlong = 0.0
          var bestBearing = 0.0
          for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val dx = b[0] - a[0]
            val dy = b[1] - a[1]
            val l2 = dx * dx + dy * dy
            if (l2 == 0.0) continue
            val t = (((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / l2).coerceIn(0.0, 1.0)
            val d = hypot(a[0] + dx * t - p[0], a[1] + dy * t - p[1])
            if (d < best) {
              best = d
              bestAlong = cum[i] + t * kotlin.math.sqrt(l2)
              bestBearing = (Math.toDegrees(atan2(dx, dy)) + 360) % 360
            }
          }
          if (best > ON_ROUTE_M) return@mapNotNull null
          val dir =
              if (angleDiff(bestBearing, c.intoGermanyBearing) < 90) CrossingDirection.INTO_GERMANY
              else CrossingDirection.INTO_NETHERLANDS
          CrossingOnRoute(c, bestAlong, dir)
        }
        .sortedBy { it.alongM }
  }

  /**
   * The crossing on [route] where a German check is possible: the first stationary one we drive
   * into Germany over. (Dutch Marechaussee checks going the other way are spot checks with no
   * fixed place, and queues for them show up as NDW jams anyway.)
   */
  fun checkOn(route: List<LatLon>): CrossingOnRoute? =
      crossingsOn(route).firstOrNull { it.direction == CrossingDirection.INTO_GERMANY && it.crossing.stationary }

  /**
   * Valhalla `exclude_polygons` to route around [crossings]' checkpoints: one small square each,
   * as [lon, lat] rings. All stationary crossings are left out together, so the detour doesn't
   * just move to the next motorway checkpoint.
   */
  fun avoidPolygons(crossings: List<BorderCrossing>, radiusM: Double = AVOID_RADIUS_M): JSONArray {
    val out = JSONArray()
    for (c in crossings) {
      val dLat = radiusM / 111_320.0
      val dLon = radiusM / (111_320.0 * cos(Math.toRadians(c.at.lat)))
      val ring = JSONArray()
      for ((la, lo) in listOf(-1 to -1, -1 to 1, 1 to 1, 1 to -1, -1 to -1)) {
        ring.put(JSONArray().put(c.at.lon + lo * dLon).put(c.at.lat + la * dLat))
      }
      out.put(ring)
    }
    return out
  }

  /** Every stationary crossing, for [avoidPolygons]. */
  val stationary: List<BorderCrossing> get() = NL_DE.filter { it.stationary }

  /**
   * Border-check reports in an Autobahn GmbH `services/warning` answer
   * (https://verkehr.autobahn.de/o/autobahn/{road}/services/warning, the federal motorway
   * company's open traffic data, no key). Kept: warnings whose text mentions a border check
   * ("Grenzkontrolle", "Grenzübergang ... Kontrolle") within [REPORT_RADIUS_M] of a crossing, and
   * jams (with a delay) within [QUEUE_RADIUS_M] of one in the direction coming from the Netherlands
   * (subtitle "Arnheim -> Oberhausen"): that is where the queue for a German check stands.
   */
  fun parseAutobahnWarnings(
      json: String,
      fetchedAt: Instant,
      crossings: List<BorderCrossing> = NL_DE,
      /** When given, other reported checks ("Polizeikontrolle", "Kontrollstelle") are added here. */
      controls: MutableList<ControlReport>? = null,
  ): List<BorderReport> {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
    val list = root.optJSONArray("warning") ?: return emptyList()
    val out = ArrayList<BorderReport>()
    for (i in 0 until list.length()) {
      val w = list.optJSONObject(i) ?: continue
      val text =
          buildList {
                add(w.optString("title"))
                add(w.optString("subtitle"))
                w.optJSONArray("description")?.let { d -> for (k in 0 until d.length()) add(d.optString(k)) }
              }
              .filter { it.isNotBlank() }
              .joinToString(" · ")
      val c = w.optJSONObject("coordinate") ?: continue
      val lat = c.opt("lat")?.toString()?.toDoubleOrNull() ?: continue
      val lon = (c.opt("long") ?: c.opt("lon"))?.toString()?.toDoubleOrNull() ?: continue
      val here = LatLon(lat, lon)
      val crossing = crossings.minByOrNull { metres(it.at, here) } ?: continue
      val away = metres(crossing.at, here)
      val delayMin = w.opt("delayTimeValue")?.toString()?.toDoubleOrNull() ?: DELAY_IN_TEXT.find(text)?.groupValues?.get(1)?.toDoubleOrNull()
      val namesCheck = mentionsBorderCheck(text)
      val queue = delayMin != null && away <= QUEUE_RADIUS_M && comesFromNl(w.optString("subtitle"), crossing)
      if (!(namesCheck && away <= REPORT_RADIUS_M) && !queue) {
        if (controls != null && mentionsControl(text) && !namesCheck) {
          val since = w.optString("startTimestamp").takeIf { it.isNotBlank() }?.let(::parseTime)
          controls += ControlReport(here, since, fetchedAt, text)
        }
        continue
      }
      val since = w.optString("startTimestamp").takeIf { it.isNotBlank() }?.let(::parseTime)
      if (since != null && fetchedAt.epochSecond - since.epochSecond > 24 * 3600 && delayMin == null) continue
      out += BorderReport(crossing.id, BorderSource.AUTOBAHN_GMBH, delayMin?.times(60), since, fetchedAt, text, namesCheck)
    }
    return out
  }

  /** "Verzögerung 25 min" / "Verzögerung: 25 Minuten". */
  private val DELAY_IN_TEXT = Regex("""Verz[öo]gerung[^0-9]{0,12}(\d{1,3})\s*Min""", RegexOption.IGNORE_CASE)

  /** "Arnheim -> Oberhausen": the part before the arrow names a Dutch place (or the border). */
  internal fun comesFromNl(direction: String, c: BorderCrossing): Boolean {
    val from = direction.split("->", "→").firstOrNull()?.trim()?.lowercase() ?: return false
    if (!direction.contains("->") && !direction.contains("→")) return false
    return (c.fromNl + listOf("Niederlande", "Grenze", "NL")).any { from.contains(it.lowercase()) }
  }

  /** "Polizeikontrolle", "Kontrollstelle", "Verkehrskontrolle", "Schwerlastkontrolle"... */
  internal fun mentionsControl(text: String): Boolean = "kontroll" in text.lowercase()

  /** Reported checks within [toleranceM] of [route]. */
  fun controlsOn(route: List<LatLon>, controls: List<ControlReport>, toleranceM: Double = 60.0): List<ControlReport> {
    if (route.size < 2 || controls.isEmpty()) return emptyList()
    val o = route.first()
    val pts = route.map { xy(it, o) }
    return controls.filter { c ->
      val p = xy(c.at, o)
      (0 until pts.size - 1).any { i ->
        val a = pts[i]
        val b = pts[i + 1]
        val dx = b[0] - a[0]
        val dy = b[1] - a[1]
        val l2 = dx * dx + dy * dy
        val t = if (l2 == 0.0) 0.0 else (((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / l2).coerceIn(0.0, 1.0)
        hypot(a[0] + dx * t - p[0], a[1] + dy * t - p[1]) <= toleranceM
      }
    }
  }

  /**
   * The polygons for one "around" request: the [checks] on the routes first, then [controls] on
   * them, then the other stationary crossings nearest to the first check (so the detour doesn't
   * just move to the next motorway checkpoint), as many as fit in Valhalla's outline limit.
   */
  fun avoidFor(checks: List<BorderCrossing>, controls: List<LatLon>): JSONArray {
    val ref = checks.firstOrNull()?.at ?: controls.firstOrNull()
    val others = if (checks.isEmpty() || ref == null) emptyList() else stationary.filter { it !in checks }.sortedBy { metres(it.at, ref) }.map { it.at }
    val points = (checks.map { it.at } + controls + others).distinct()
    val fit = (MAX_EXCLUDE_OUTLINE_M / (8 * AVOID_RADIUS_M)).toInt()
    return avoidPoints(points.take(fit))
  }

  /** Squares around points to keep out of a route, like [avoidPolygons]. */
  fun avoidPoints(points: List<LatLon>, radiusM: Double = AVOID_RADIUS_M): JSONArray =
      avoidPolygons(points.mapIndexed { i, p -> BorderCrossing("p$i", "", "", p, 0.0) }, radiusM)

  /** German motorway numbers a route drives on ("A 3" / "A3" → "A3"), for the Autobahn GmbH feed. */
  fun motorways(stepRoads: List<String?>): List<String> =
      stepRoads.filterNotNull().flatMap { MOTORWAY.findAll(it).map { m -> "A" + m.groupValues[1] }.toList() }.distinct()

  private val MOTORWAY = Regex("""\bA ?(\d{1,3})\b""")

  internal fun mentionsBorderCheck(text: String): Boolean {
    val t = text.lowercase()
    return "grenzkontroll" in t || ("grenz" in t && "kontroll" in t)
  }

  /**
   * Queues that NDW reports in front of [crossing] on the Dutch side (A12 at Zevenaar for Elten),
   * as a report. The German checkpoint is just past the border, so its queue backs up into the
   * Netherlands, where NDW sees it. Already counted in the route's traffic time; this only names it.
   */
  fun ndwQueue(
      route: List<LatLon>,
      at: CrossingOnRoute,
      events: List<TrafficEvent>,
      fetchedAt: Instant,
      beforeM: Double = 10_000.0,
  ): BorderReport? {
    val near =
        RouteTraffic.ahead(route, events).filter { a ->
          a.event.kind == TrafficKind.JAM || a.event.kind == TrafficKind.SLOW
        }.filter { a ->
          val end = a.endM ?: a.distanceM
          end >= at.alongM - beforeM && a.distanceM <= at.alongM + 500
        }
    if (near.isEmpty()) return null
    val delay = near.sumOf { RouteChoice.delayOf(it) }
    if (delay < 60) return null
    val since = near.mapNotNull { it.event.start }.minOrNull()
    return BorderReport(at.crossing.id, BorderSource.NDW, delay, since, fetchedAt, "NDW")
  }

  /**
   * The report to show for [crossingId]: the one with the biggest delay that is fresh enough.
   * Old ones are dropped: a two-hour-old queue says little about now.
   */
  fun bestReport(reports: List<BorderReport>, crossingId: String, now: Instant): BorderReport? =
      reports
          .filter { it.crossingId == crossingId }
          .filter { now.epochSecond - it.fetchedAt.epochSecond <= MAX_REPORT_AGE_S }
          .maxWithOrNull(compareBy<BorderReport> { it.delaySeconds ?: -1.0 }.thenBy { it.fetchedAt })

  /**
   * Extra waiting time at the check that the route's traffic time doesn't have yet: the reported
   * delay, minus what NDW already put in (both describe the same queue).
   */
  fun extraDelay(report: BorderReport?, ndwAlreadyCounted: Double): Double {
    if (report == null || report.source == BorderSource.NDW) return 0.0
    return ((report.delaySeconds ?: 0.0) - ndwAlreadyCounted).coerceAtLeast(0.0)
  }

  private val TIME =
      DateTimeFormatterBuilder()
          .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
          .optionalStart()
          .appendPattern("[XXX][XX][X]")
          .optionalEnd()
          .parseDefaulting(ChronoField.OFFSET_SECONDS, 3600)
          .toFormatter()

  internal fun parseTime(s: String): Instant? = runCatching { OffsetDateTime.parse(s.trim(), TIME).toInstant() }.getOrNull()

  internal fun metres(a: LatLon, b: LatLon): Double {
    val p = xy(b, a)
    return hypot(p[0], p[1])
  }

  private fun angleDiff(a: Double, b: Double): Double {
    val d = abs(a - b) % 360
    return if (d > 180) 360 - d else d
  }

  private fun xy(p: LatLon, o: LatLon): DoubleArray {
    val r = 6_371_008.8
    return doubleArrayOf(
        Math.toRadians(p.lon - o.lon) * r * cos(Math.toRadians(o.lat)),
        Math.toRadians(p.lat - o.lat) * r,
    )
  }
}
