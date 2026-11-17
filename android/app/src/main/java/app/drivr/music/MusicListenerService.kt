package app.drivr.music

import android.service.notification.NotificationListenerService

/**
 * Empty on purpose. Android only lets an app see other apps' media sessions (Spotify, YouTube
 * Music, podcasts...) when the user has given it notification access, and that access is granted
 * to a NotificationListenerService. Drivr never reads notifications; it only uses the media
 * sessions through [MusicController].
 */
class MusicListenerService : NotificationListenerService() {
  override fun onListenerConnected() {
    MusicController.instance?.refresh()
  }
}
