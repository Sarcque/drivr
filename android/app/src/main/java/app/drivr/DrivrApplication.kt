package app.drivr

import android.app.Application
import uniffi.ferrostar.createFerrostarLogger

class DrivrApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    AppModule.init(this)
    createFerrostarLogger()
  }
}
