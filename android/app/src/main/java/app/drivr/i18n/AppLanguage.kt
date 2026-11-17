package app.drivr.i18n

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * The app language: the phone's language by default (which on Android 13+ includes the per-app
 * language in system settings), or a language picked in Drivr's own settings.
 */
object AppLanguage {
  /** Language tags Drivr is translated into, with each language's own name. Add new ones here. */
  val SUPPORTED: List<Pair<String, String>> =
      listOf(
          "en" to "English",
          "nl" to "Nederlands",
          "de" to "Deutsch",
          "fr" to "Français",
          "es" to "Español",
      )

  private const val PREFS = "drivr_settings"
  private const val KEY = "language"

  /** The picked language tag, or "" to follow the phone. */
  fun chosen(context: Context): String =
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""

  /** A context whose resources are in the picked language (unchanged when following the phone). */
  fun wrap(base: Context): Context {
    val tag = chosen(base)
    if (tag.isBlank()) return base
    val locale = Locale.forLanguageTag(tag)
    Locale.setDefault(locale)
    val config = Configuration(base.resources.configuration)
    config.setLocales(LocaleList(locale))
    return base.createConfigurationContext(config)
  }

  /**
   * Saves the choice and restarts Drivr, so the map, the voice and the turn instructions (which
   * come from the routing server in the chosen language) all switch together.
   */
  fun set(activity: Activity, tag: String) {
    if (tag == chosen(activity)) return
    activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).commit()
    val restart =
        activity.packageManager.getLaunchIntentForPackage(activity.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    if (restart == null) {
      activity.recreate()
      return
    }
    activity.startActivity(restart)
    activity.finishAffinity()
    Runtime.getRuntime().exit(0)
  }
}
