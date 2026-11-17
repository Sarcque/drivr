package app.drivr

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import app.drivr.i18n.AppLanguage
import app.drivr.i18n.L10n
import app.drivr.i18n.ResourceStrings
import uniffi.ferrostar.createFerrostarLogger

class DrivrApplication : Application() {
  override fun attachBaseContext(base: Context) {
    super.attachBaseContext(AppLanguage.wrap(base))
  }

  override fun onCreate() {
    super.onCreate()
    L10n.source = ResourceStrings(resources)
    AppModule.init(this)
    createFerrostarLogger()
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    // Following the phone's language: pick up a change made while Drivr is running.
    if (AppLanguage.chosen(this).isBlank()) L10n.source = ResourceStrings(resources)
  }
}
