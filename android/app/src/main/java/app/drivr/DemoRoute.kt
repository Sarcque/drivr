package app.drivr

import java.time.Instant
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.UserLocation

/**
 * The test drive: Utrecht Centraal onto the A28 through Knooppunt Rijnsweerd towards Amersfoort.
 * Same junction as the web prototype, and it has rich OSM lane tagging (turn:lanes on every
 * approach), so the lane banner has real data to show.
 */
object DemoRoute {
  val origin =
      UserLocation(GeographicCoordinate(52.08935, 5.11200), 6.0, null, Instant.now(), null)

  val destination = GeographicCoordinate(52.15500, 5.38400)
  const val destinationName = "Amersfoort"
}
