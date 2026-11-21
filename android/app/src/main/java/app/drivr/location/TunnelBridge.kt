package app.drivr.location

/**
 * Keeps the car moving along the route when GPS drops out (tunnels, underpasses, a parking deck),
 * like a car navigator: from the last real fix it carries on along the route at the last speed,
 * one predicted fix a second, until real fixes come back.
 *
 * Only while navigating a route, only when the car was on that route and moving, and for at most
 * [maxMs] (a long stop in a tunnel would otherwise run the car ahead of itself).
 */
class TunnelBridge(
    private val startAfterMs: Long = 2_500,
    private val maxMs: Long = 120_000,
) {
  private var track: RouteTrack? = null
  private var trackKey: Any? = null
  private var anchor: Fix? = null
  private var anchorProgress = 0.0

  /** The route being driven, or null when not navigating (then nothing is predicted). */
  fun setRoute(points: List<Pair<Double, Double>>?, key: Any? = points) {
    if (key == trackKey) return
    trackKey = key
    track = points?.takeIf { it.size >= 2 }?.let(::RouteTrack)
    anchor = null
  }

  /** A real (measured) fix came in. */
  fun onRealFix(fix: Fix) {
    val t = track ?: return run { anchor = null }
    val p = t.project(fix.lat, fix.lon, near = anchor?.let { anchorProgress })
    if (p.distanceM > 40.0) {
      anchor = null
      return
    }
    anchor = fix
    anchorProgress = p.progressM
  }

  /** A predicted fix for [nowMs], or null when there's nothing to bridge. */
  fun predict(nowMs: Long): Fix? {
    val t = track ?: return null
    val a = anchor ?: return null
    val gap = nowMs - a.timeMs
    if (gap < startAfterMs || gap > maxMs) return null
    val speed = a.speedMps ?: return null
    if (speed < 4.0) return null
    val progress = (anchorProgress + speed * gap / 1000.0).coerceAtMost(t.lengthM)
    val (lat, lon) = t.positionAt(progress)
    return Fix(
        lat = lat,
        lon = lon,
        // Good enough for Ferrostar to keep advancing the turn instructions (it wants ≤ 32 m),
        // rough enough that it never decides we left the route (it checks at ≤ 15 m).
        accuracyM = 20.0,
        speedMps = speed,
        bearingDeg = t.bearingAt(progress),
        timeMs = nowMs,
        predicted = true,
    )
  }
}
