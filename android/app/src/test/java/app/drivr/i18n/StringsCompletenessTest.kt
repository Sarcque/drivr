package app.drivr.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every language has every string, with the same placeholders, so no screen falls back to English. */
class StringsCompletenessTest {
  private val english = XmlStrings("values")
  private val placeholder = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdf]""")

  private fun placeholders(text: String) = placeholder.findAll(text).map { it.value }.sorted().toList()

  @Test
  fun all_offered_languages_are_translated() {
    val folders = XmlStrings.translations()
    val offered = AppLanguage.SUPPORTED.map { it.first }.filter { it != "en" }.map { "values-$it" }
    assertEquals(offered.sorted(), folders)
  }

  @Test
  fun every_key_exists_in_every_language() {
    for (folder in XmlStrings.translations()) {
      val t = XmlStrings(folder)
      val englishKeys = english.strings.keys - "app_name"
      assertEquals("strings missing in $folder", emptySet<String>(), englishKeys - t.strings.keys)
      assertEquals("strings only in $folder", emptySet<String>(), t.strings.keys - englishKeys)
      assertEquals("plurals in $folder", english.plurals.keys, t.plurals.keys)
      for ((name, forms) in t.plurals) assertTrue("$folder $name needs an 'other' form", "other" in forms)
    }
  }

  @Test
  fun placeholders_match_english() {
    for (folder in XmlStrings.translations()) {
      val t = XmlStrings(folder)
      for ((name, en) in english.strings) {
        val tr = t.strings[name] ?: continue
        assertEquals("$folder/$name", placeholders(en), placeholders(tr))
      }
      for ((name, en) in english.plurals) {
        val tr = t.plurals[name] ?: continue
        assertEquals("$folder/$name other", placeholders(en.getValue("other")), placeholders(tr.getValue("other")))
      }
    }
  }

  @Test
  fun translations_are_not_left_in_english() {
    // Words that are the same in every language (units, brand names) are fine; whole sentences aren't.
    for (folder in XmlStrings.translations()) {
      val t = XmlStrings(folder)
      val same = english.strings.filter { (k, v) -> v.length > 25 && t.strings[k] == v }.keys
      assertEquals("$folder looks untranslated", emptySet<String>(), same)
    }
  }

  @Test
  fun lane_prompts_in_dutch() {
    TestStrings.install(XmlStrings("values-nl"))
    try {
      val s = uniffi.ferrostar.LaneInfo(false, listOf("straight"), null)
      val r = uniffi.ferrostar.LaneInfo(true, listOf("slight right"), "slight right")
      val lanes = listOf(s, s, s, s, r)
      assertEquals("Nu naar rechts · tot 4 rijstroken", app.drivr.ui.laneAdvice(lanes, 1500.0, motorway = true)!!.text)
      assertEquals("Neem de rechterrijstrook", app.drivr.ui.laneHint(lanes))
      assertEquals("1,2 km", app.drivr.ui.formatDistance(1234.0))
      assertEquals("nl-NL", L10n.valhallaLanguage())
    } finally {
      TestStrings.install()
    }
  }
}
