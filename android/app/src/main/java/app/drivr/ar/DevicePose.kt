package app.drivr.ar

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/** Camera tilt from the gravity sensor. Heading comes from GPS course, not from here. */
data class Tilt(val pitchDownDeg: Double, val rollDeg: Double)

/**
 * Pure maths, kept separate so it can be unit tested: gravity (device axes, pointing up, as
 * Android's TYPE_GRAVITY reports it) plus display rotation -> how far the back camera looks below
 * the horizon and how far the image is rolled.
 */
fun tiltFromGravity(gx: Float, gy: Float, gz: Float, displayRotation: Int): Tilt {
  val n = sqrt(gx * gx + gy * gy + gz * gz).takeIf { it > 1e-3f } ?: return Tilt(0.0, 0.0)
  // The back camera looks along device -Z; world up in device axes is g / |g|.
  val pitchDown = Math.toDegrees(asin((gz / n).toDouble().coerceIn(-1.0, 1.0)))
  // Gravity in screen axes, so roll is relative to how the image is shown.
  val (sx, sy) =
      when (displayRotation) {
        Surface.ROTATION_90 -> gy to -gx
        Surface.ROTATION_180 -> -gx to -gy
        Surface.ROTATION_270 -> -gy to gx
        else -> gx to gy
      }
  val roll = -Math.toDegrees(atan2(sx.toDouble(), sy.toDouble()))
  return Tilt(pitchDown, roll)
}

/** Smoothed camera tilt, live while in composition. */
@Composable
fun rememberDeviceTilt(): State<Tilt> {
  val context = LocalContext.current
  val view = LocalView.current
  val tilt = remember { mutableStateOf(Tilt(8.0, 0.0)) }
  DisposableEffect(Unit) {
    val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val sensor =
        sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    val g = FloatArray(3)
    var primed = false
    val listener =
        object : SensorEventListener {
          override fun onSensorChanged(e: SensorEvent) {
            // Low-pass: car vibration and braking shouldn't make the overlay jump.
            val a = if (primed) 0.08f else 1f
            for (i in 0..2) g[i] += a * (e.values[i] - g[i])
            primed = true
            tilt.value = tiltFromGravity(g[0], g[1], g[2], view.display?.rotation ?: 0)
          }

          override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
    sensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
    onDispose { sm.unregisterListener(listener) }
  }
  return tilt
}
