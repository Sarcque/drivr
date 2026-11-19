package app.drivr.voice

import android.media.AudioManager
import android.media.ToneGenerator

/** The short "I'm listening" tone after "Hey Drivr" (no audio file, no extra permission). */
object Earcon {
  private val tone: ToneGenerator? by lazy { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60) }.getOrNull() }

  fun wake() {
    runCatching { tone?.startTone(ToneGenerator.TONE_PROP_ACK, 150) }
  }
}
