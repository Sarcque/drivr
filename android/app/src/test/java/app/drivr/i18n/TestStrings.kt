package app.drivr.i18n

import app.drivr.R
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * The app's strings.xml files read straight from src/main/res, so plain JVM tests (no Android
 * resources) get the same text as the app. Also used to check that every language is complete.
 */
class XmlStrings(val folder: String) : StringSource {
  val strings: Map<String, String>
  val plurals: Map<String, Map<String, String>>

  init {
    val file = File(RES, "$folder/strings.xml")
    val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
    val s = mutableMapOf<String, String>()
    val p = mutableMapOf<String, Map<String, String>>()
    val root = doc.documentElement
    val kids = root.childNodes
    for (i in 0 until kids.length) {
      val e = kids.item(i) as? Element ?: continue
      val name = e.getAttribute("name")
      when (e.tagName) {
        "string" -> s[name] = unescape(e.textContent)
        "plurals" -> {
          val items = e.getElementsByTagName("item")
          p[name] = (0 until items.length).associate {
            val item = items.item(it) as Element
            item.getAttribute("quantity") to unescape(item.textContent)
          }
        }
      }
    }
    strings = s
    plurals = p
  }

  override val locale: Locale = Locale.forLanguageTag(strings.getValue("locale_tag"))

  override fun get(id: Int, args: Array<out Any?>): String {
    val raw = strings[nameOf(stringIds, id)] ?: error("No string ${nameOf(stringIds, id)} in $folder")
    return if (args.isEmpty()) raw else String.format(locale, raw, *args)
  }

  override fun plural(id: Int, count: Int, args: Array<out Any?>): String {
    val forms = plurals[nameOf(pluralIds, id)] ?: error("No plural ${nameOf(pluralIds, id)} in $folder")
    // One/other is all Drivr's languages need for whole counts of one or more.
    val raw = (if (count == 1) forms["one"] else null) ?: forms.getValue("other")
    return String.format(locale, raw, *args)
  }

  companion object {
    val RES = File("src/main/res")

    private fun ids(cls: Class<*>): Map<Int, String> = cls.fields.associate { it.getInt(null) to it.name }

    val stringIds: Map<Int, String> by lazy { ids(R.string::class.java) }
    val pluralIds: Map<Int, String> by lazy { ids(R.plurals::class.java) }

    private fun nameOf(ids: Map<Int, String>, id: Int) = ids[id] ?: error("Unknown resource id $id")

    /** Every translated folder: values-nl, values-de, ... */
    fun translations(): List<String> =
        RES.listFiles()!!.map { it.name }.filter { it.startsWith("values-") && File(RES, "$it/strings.xml").exists() }.sorted()

    /** Android resource escapes: \' \" \n \\ and a leading \@ or \?. */
    fun unescape(raw: String): String {
      val out = StringBuilder()
      var i = 0
      while (i < raw.length) {
        val c = raw[i]
        if (c == '\\' && i + 1 < raw.length) {
          val n = raw[i + 1]
          out.append(if (n == 'n') '\n' else if (n == 't') '\t' else n)
          i += 2
        } else {
          out.append(c)
          i++
        }
      }
      return out.toString()
    }
  }
}

/** Installs English text for tests; call from a test class's init block. */
object TestStrings {
  val english by lazy { XmlStrings("values") }

  fun install(source: StringSource = english) {
    L10n.source = source
  }
}
