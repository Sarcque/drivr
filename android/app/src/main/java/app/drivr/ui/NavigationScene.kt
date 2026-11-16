package app.drivr.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import androidx.core.content.ContextCompat
import app.drivr.BuildConfig
import app.drivr.DrivrViewModel
import app.drivr.RouteStatus
import com.stadiamaps.ferrostar.composeui.config.NavigationViewComponentBuilder
import com.stadiamaps.ferrostar.composeui.config.withCustomOverlayView
import com.stadiamaps.ferrostar.composeui.config.withInstructionsView
import com.stadiamaps.ferrostar.composeui.runtime.KeepScreenOnDisposableEffect
import com.stadiamaps.ferrostar.maplibreui.NavigationMapClickResult
import com.stadiamaps.ferrostar.maplibreui.runtime.NavigationCameraOptions
import com.stadiamaps.ferrostar.maplibreui.runtime.rememberNavigationMapState
import com.stadiamaps.ferrostar.maplibreui.views.DynamicallyOrientingNavigationView
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.style.BaseStyle

/**
 * Driving view camera: low and steep, like looking over the bonnet. The puck sits in the lower
 * third so most of the screen shows the road ahead.
 */
@Composable
fun drivingCameraOptions(): NavigationCameraOptions {
  val config = LocalConfiguration.current
  return NavigationCameraOptions(
      browsingZoom = 15.5,
      navigationZoom = 17.6,
      navigationTilt = 60.0,
      browsingPadding = PaddingValues(0.dp),
      navigationPadding = PaddingValues(top = (config.screenHeightDp * 0.55f).dp),
  )
}

@Composable
fun NavigationScene(viewModel: DrivrViewModel) {
  KeepScreenOnDisposableEffect()
  RequestLocationPermission(viewModel)

  val uiState by viewModel.navigationUiState.collectAsState()
  val routeStatus by viewModel.routeStatus.collectAsState()
  val mapState = rememberNavigationMapState()

  DynamicallyOrientingNavigationView(
      modifier = Modifier.fillMaxSize(),
      baseStyle = BaseStyle.Uri(BuildConfig.MAP_STYLE_URL),
      navigationMapState = mapState,
      navigationCameraOptions = drivingCameraOptions(),
      viewModel = viewModel,
      views =
          NavigationViewComponentBuilder.Default()
              .withInstructionsView { modifier, state ->
                state.visualInstruction?.let {
                  InstructionBanner(
                      instruction = it,
                      distanceToManeuverMeters = state.progress?.distanceToNextManeuver,
                      modifier = modifier,
                  )
                }
              }
              .withCustomOverlayView { modifier ->
                if (!uiState.isNavigating()) {
                  StartPanel(
                      status = routeStatus,
                      onDemoDrive = viewModel::startDemoDrive,
                      modifier = modifier.align(Alignment.BottomCenter),
                  )
                }
              },
      onTapExit = { viewModel.stopNavigation() },
      onMapLongClick = { position, _ ->
        if (!uiState.isNavigating()) viewModel.navigateTo(position)
        NavigationMapClickResult.Consume
      },
      mapOptions =
          MapOptions(
              ornamentOptions = OrnamentOptions(isCompassEnabled = false, isScaleBarEnabled = false)
          ),
  )
}

@Composable
private fun StartPanel(status: RouteStatus, onDemoDrive: () -> Unit, modifier: Modifier) {
  Column(
      modifier =
          modifier
              .navigationBarsPadding()
              .padding(16.dp)
              .fillMaxWidth()
              .clip(RoundedCornerShape(20.dp))
              .background(Color(0xF2161A1F))
              .padding(20.dp)
  ) {
    Text("Drivr", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text(
        "Long-press the map to drive there, or take the test drive through Knooppunt " +
            "Rijnsweerd (Utrecht) with simulated GPS.",
        color = Color(0xFFB8C0C8),
        fontSize = 14.sp,
    )
    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
      Button(
          onClick = onDemoDrive,
          enabled = status !is RouteStatus.Loading,
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2F8CFF)),
      ) {
        Text("Start test drive")
      }
      Spacer(Modifier.width(14.dp))
      when (status) {
        RouteStatus.Loading ->
            CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
        is RouteStatus.Failed ->
            Text(status.message, color = Color(0xFFFF8A80), fontSize = 13.sp, maxLines = 2)
        RouteStatus.Idle -> {}
      }
    }
  }
}

@Composable
private fun RequestLocationPermission(viewModel: DrivrViewModel) {
  val context = LocalContext.current
  val permissions = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      add(Manifest.permission.POST_NOTIFICATIONS)
    }
  }
  val launcher =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.onLocationPermission(it[Manifest.permission.ACCESS_FINE_LOCATION] == true)
      }
  LaunchedEffect(Unit) {
    val granted =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    if (granted) viewModel.onLocationPermission(true) else launcher.launch(permissions.toTypedArray())
  }
}
