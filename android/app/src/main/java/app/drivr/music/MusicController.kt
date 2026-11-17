package app.drivr.music

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What is playing right now, in whichever app. */
data class NowPlaying(
    val title: String,
    val artist: String?,
    val art: Bitmap?,
    val playing: Boolean,
    val appLabel: String,
    val packageName: String,
    val canSkip: Boolean = true,
)

/** Notification access (needed to see other apps' players) and the current player, if any. */
data class MusicState(val hasAccess: Boolean = false, val nowPlaying: NowPlaying? = null)

/**
 * Reads and controls whatever app is playing media (Spotify, YouTube Music, a podcast app...)
 * through Android's MediaSessionManager. No login and no API keys: the user grants notification
 * access once, and every player that publishes a media session works.
 */
class MusicController(private val context: Context) {
  private val sessions = context.getSystemService(MediaSessionManager::class.java)
  private val listener = ComponentName(context, MusicListenerService::class.java)
  private val main = Handler(Looper.getMainLooper())
  private val _state = MutableStateFlow(MusicState())
  val state: StateFlow<MusicState> = _state.asStateFlow()

  private var current: MediaController? = null
  private var watching = false

  private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list.orEmpty()) }
  private val callback =
      object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() = refresh()
      }

  init {
    instance = this
  }

  fun hasAccess(): Boolean = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

  /** Re-check access and the active players; call when the app returns to the foreground. */
  fun refresh() {
    if (!hasAccess()) {
      stopWatching()
      _state.value = MusicState(hasAccess = false)
      return
    }
    val list =
        try {
          if (!watching) {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, listener, main)
            watching = true
          }
          sessions.getActiveSessions(listener)
        } catch (_: SecurityException) {
          // Access was revoked between the check and the call.
          stopWatching()
          _state.value = MusicState(hasAccess = false)
          return
        }
    pick(list)
  }

  fun playPause() {
    val c = current ?: return
    if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
  }

  fun next() = current?.transportControls?.skipToNext()

  fun previous() = current?.transportControls?.skipToPrevious()

  /** Opens the playing app (e.g. Spotify) for playlists and search. */
  fun openPlayer() {
    val c = current
    val intent = c?.sessionActivity
    if (intent != null) {
      runCatching { intent.send() }
      return
    }
    val pkg = c?.packageName ?: return
    context.packageManager.getLaunchIntentForPackage(pkg)?.let {
      context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
  }

  /** The system screen where the user turns on notification access for Drivr. */
  fun accessSettingsIntent(): Intent {
    val intent =
        if (android.os.Build.VERSION.SDK_INT >= 30) {
          Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
              .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener.flattenToString())
        } else {
          Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
    return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  }

  private fun pick(list: List<MediaController>) {
    // Prefer a player that is playing; otherwise the most recent one (first in the list).
    val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
    if (best?.sessionToken != current?.sessionToken) {
      current?.unregisterCallback(callback)
      current = best
      best?.registerCallback(callback, main)
    }
    publish()
  }

  private fun publish() {
    val c = current
    val meta = c?.metadata
    val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
    _state.value =
        MusicState(
            hasAccess = true,
            nowPlaying =
                if (c == null || title.isNullOrBlank()) null
                else
                    NowPlaying(
                        title = title,
                        artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                        art = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART),
                        playing = c.playbackState?.state == PlaybackState.STATE_PLAYING,
                        appLabel = appLabel(c.packageName),
                        packageName = c.packageName,
                        canSkip = (c.playbackState?.actions ?: 0L) and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
                    ),
        )
  }

  private fun appLabel(pkg: String): String =
      runCatching { context.packageManager.run { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() } }.getOrDefault(pkg)

  private fun stopWatching() {
    if (watching) runCatching { sessions.removeOnActiveSessionsChangedListener(sessionsChanged) }
    watching = false
    current?.unregisterCallback(callback)
    current = null
  }

  companion object {
    @Volatile internal var instance: MusicController? = null
  }
}
