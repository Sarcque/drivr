package app.drivr.i18n

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.util.Locale

/** Where translated text comes from: Android resources in the app, the XML files in unit tests. */
interface StringSource {
  /** The language the text is in, for numbers ("1,2 km" in Dutch) and the voice. */
  val locale: Locale

  fun get(@StringRes id: Int, args: Array<out Any?>): String

  fun plural(@PluralsRes id: Int, count: Int, args: Array<out Any?>): String
}

class ResourceStrings(private val res: Resources) : StringSource {
  override val locale: Locale = Locale.forLanguageTag(res.getString(app.drivr.R.string.locale_tag))

  override fun get(id: Int, args: Array<out Any?>): String =
      if (args.isEmpty()) res.getString(id) else res.getString(id, *args)

  override fun plural(id: Int, count: Int, args: Array<out Any?>): String =
      res.getQuantityString(id, count, *args)
}

/**
 * Translated text for code outside composables (view model, formatters, lane advice). Composables
 * use stringResource; both read the same res/values-xx/strings.xml files.
 */
object L10n {
  @Volatile var source: StringSource? = null

  private fun src(): StringSource = source ?: error("L10n.source not set (DrivrApplication sets it)")

  fun s(@StringRes id: Int, vararg args: Any?): String = src().get(id, args)

  /** Plural: the count also fills the first placeholder unless other args are given. */
  fun p(@PluralsRes id: Int, count: Int, vararg args: Any?): String =
      src().plural(id, count, if (args.isEmpty()) arrayOf(count) else args)

  val locale: Locale get() = source?.locale ?: Locale.getDefault()

  /** String.format in the app language, so decimals use the right separator. */
  fun format(pattern: String, vararg args: Any?): String = String.format(locale, pattern, *args)

  /**
   * The language for Valhalla's turn instructions, which Ferrostar reads out. Valhalla knows all
   * of Drivr's languages; English follows the phone's region for en-US vs en-GB wording.
   */
  fun valhallaLanguage(system: Locale = Locale.getDefault()): String {
    val l = locale
    return if (l.language == "en") (if (system.language == "en" && system.country == "US") "en-US" else "en-GB")
    else l.toLanguageTag()
  }
}
