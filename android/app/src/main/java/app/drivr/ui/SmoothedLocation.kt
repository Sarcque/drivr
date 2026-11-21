package app.drivr.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import app.drivr.location.DisplayAnimator
import com.stadiamaps.ferrostar.core.NavigationUiState
import com.stadiamaps.ferrostar.core.isNavigating
import kotlin.math.abs
import uniffi.ferrostar.CourseOverGround
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.RouteDeviation
import uniffi.ferrostar.UserLocation

/**
 * The car's position for drawing, updated every screen frame: carried forward between fixes and
 * moving along the route (see [DisplayAnimator]). Puck and camera both use this, so they move
 * together and smoothly instead of jumping once a second.
 */
@Composable
fun rememberSmoothedLocation(ui: NavigationUiState): State<UserLocation?> {
  val animator = remember { DisplayAnimator() }
  val out = remember { mutableStateOf(ui.location) }
  val latest = rememberUpdatedState(ui.location)
  val followRoute = ui.isNavigating() && ui.routeDeviation is RouteDeviation.NoDeviation
  val route = ui.routeGeometry.takeIf { ui.isNavigating() }

  LaunchedEffect(ui.location, route, followRoute) {
    animator.setRoute(route?.map { it.lat to it.lng }, route)
    val loc = ui.location ?: return@LaunchedEffect
    animator.onFix(
        loc.coordinates.lat, loc.coordinates.lng,
        loc.speed?.value,
        loc.courseOverGround?.degrees?.toDouble(),
        System.nanoTime() / 1_000_000,
        followRoute,
    )
  }

  LaunchedEffect(animator) {
    while (true) {
      withFrameMillis {
        // Frame time is on System.nanoTime, like the fix arrival times above.
        val pose = animator.frame(System.nanoTime() / 1_000_000) ?: return@withFrameMillis
        val base = latest.value ?: out.value ?: return@withFrameMillis
        val prev = out.value
        val bearing = pose.bearingDeg?.let { CourseOverGround(it.roundToUShort(), null) } ?: base.courseOverGround
        // Standing still and settled: no new value, no recomposition, no battery spent.
        if (prev != null && abs(prev.coordinates.lat - pose.lat) < 1e-8 && abs(prev.coordinates.lng - pose.lon) < 1e-8 &&
            prev.courseOverGround == bearing) return@withFrameMillis
        out.value = base.copy(coordinates = GeographicCoordinate(pose.lat, pose.lon), courseOverGround = bearing)
      }
    }
  }
  return out
}

private fun Double.roundToUShort(): UShort = (Math.round(this).toInt().mod(360)).toUShort()

/**
 * Runs [content] with [ui] whose location is the smoothed one. The route geometry is left out of
 * it, so Ferrostar's map doesn't snap and animate the position a second time (its own 1 s
 * animation lags a second behind the car and the puck didn't use it); the route line takes the
 * real state.
 */
@Composable
fun WithSmoothedLocation(ui: NavigationUiState, content: @Composable (NavigationUiState) -> Unit) {
  val smoothed by rememberSmoothedLocation(ui)
  content(ui.copy(location = smoothed ?: ui.location, routeGeometry = null))
}
