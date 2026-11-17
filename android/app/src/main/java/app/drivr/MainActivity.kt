package app.drivr

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import app.drivr.ui.NavigationScene
import com.stadiamaps.ferrostar.core.AndroidTtsStatusListener
import java.util.Locale

class MainActivity : ComponentActivity(), AndroidTtsStatusListener {
  private val viewModel: DrivrViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    AppModule.ttsObserver.statusObserver = this
    AppModule.ferrostarCore.spokenInstructionObserver = AppModule.ttsObserver

    enableEdgeToEdge()
    setContent {
      MaterialTheme(colorScheme = darkColorScheme()) { Surface { NavigationScene(viewModel) } }
    }
  }

  override fun onStart() {
    super.onStart()
    AppModule.ttsObserver.start()
  }

  override fun onResume() {
    super.onResume()
    // Picks up notification access granted in system settings, and the current player.
    AppModule.music.refresh()
  }

  override fun onDestroy() {
    super.onDestroy()
    AppModule.ttsObserver.shutdown()
  }

  override fun onTtsInitialized(tts: TextToSpeech?, status: Int) {
    tts?.language = Locale.getDefault()
  }

  override fun onTtsSpeakError(utteranceId: String, status: Int) {
    Log.e("MainActivity", "TTS error for $utteranceId: $status")
  }

  override fun onTtsShutdownAndRelease() {}
}
