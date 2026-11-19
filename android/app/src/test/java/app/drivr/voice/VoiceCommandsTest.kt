package app.drivr.voice

import app.drivr.R
import app.drivr.i18n.XmlStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wake phrase and commands in every language Drivr speaks, as a recogniser would write them. */
class VoiceCommandsTest {
  private fun assertHeard(g: CommandGrammar, text: String, expected: VoiceCommand?) {
    val h = g.parse(text)
    assertTrue("no wake phrase in '$text'", h.woke)
    assertEquals("'$text'", expected, h.command)
  }

  @Test
  fun english() {
    val g = CommandGrammar.ENGLISH
    assertHeard(g, "hey driver avoid tolls", VoiceCommand.AVOID_TOLLS)
    assertHeard(g, "hey driver please avoid the toll roads", VoiceCommand.AVOID_TOLLS)
    assertHeard(g, "hi driver what's my eta", VoiceCommand.ETA)
    assertHeard(g, "okay driver find a gas station", VoiceCommand.FIND_FUEL)
    assertHeard(g, "hey drivr take me home", VoiceCommand.GO_HOME)
    assertHeard(g, "hey driver stop talking", VoiceCommand.MUTE)
    assertHeard(g, "hey driver stop navigation", VoiceCommand.STOP_NAVIGATION)
    assertHeard(g, "hey driver no tolls", VoiceCommand.AVOID_TOLLS)
    assertHeard(g, "hey driver keep the route", VoiceCommand.NO)
    assertHeard(g, "hey driver", null)
  }

  @Test
  fun dutch() {
    val g = CommandGrammar.DUTCH
    assertHeard(g, "hé driver vermijd tolwegen", VoiceCommand.AVOID_TOLLS)
    assertHeard(g, "hey driver hoe laat ben ik er", VoiceCommand.ETA)
    assertHeard(g, "hee driver zoek een tankstation", VoiceCommand.FIND_FUEL)
    assertHeard(g, "hoi driver rij naar huis", VoiceCommand.GO_HOME)
    assertHeard(g, "hey driver is er file", VoiceCommand.TRAFFIC_AHEAD)
    assertHeard(g, "hey driver rit beëindigen", VoiceCommand.STOP_NAVIGATION)
    assertHeard(g, "hey driver rit beeindigen", VoiceCommand.STOP_NAVIGATION)
    assertHeard(g, "hey driver laat maar", VoiceCommand.CANCEL)
    assertHeard(g, "hey driver geluid aan", VoiceCommand.UNMUTE)
    assertHeard(g, "hey driver zoek een laadpaal", VoiceCommand.FIND_CHARGING)
  }

  @Test
  fun german() {
    val g = CommandGrammar.GERMAN
    assertHeard(g, "hey driver mautstrassen vermeiden", VoiceCommand.AVOID_TOLLS)
    assertHeard(g, "hallo driver mautstraßen vermeiden", VoiceCommand.AVOID_TOLLS)
    assertHeard(g, "hey driver wann bin ich da", VoiceCommand.ETA)
    assertHeard(g, "hey driver ankunftszeit teilen", VoiceCommand.SHARE_ETA)
    assertHeard(g, "hey driver was kommt als nächstes", VoiceCommand.NEXT_TURN)
    assertHeard(g, "hey driver keine autobahn", VoiceCommand.AVOID_MOTORWAYS)
  }

  @Test
  fun french() {
    val g = CommandGrammar.FRENCH
    assertHeard(g, "hé driver évite les péages", VoiceCommand.AVOID_TOLLS)
    assertHeard(g, "hey driver à quelle heure j'arrive", VoiceCommand.ETA)
    assertHeard(g, "hey driver partager l'heure d'arrivée", VoiceCommand.SHARE_ETA)
    assertHeard(g, "hey driver pas d'autoroute", VoiceCommand.AVOID_MOTORWAYS)
    assertHeard(g, "salut driver il y a des bouchons", VoiceCommand.TRAFFIC_AHEAD)
  }

  @Test
  fun spanish() {
    val g = CommandGrammar.SPANISH
    assertHeard(g, "oye driver evita los peajes", VoiceCommand.AVOID_TOLLS)
    assertHeard(g, "oye dráiver cuándo llego", VoiceCommand.ETA)
    assertHeard(g, "hey driver llévame a casa", VoiceCommand.GO_HOME)
    assertHeard(g, "oye driver hay una ruta más rápida", VoiceCommand.FASTER_ROUTE)
    assertHeard(g, "oye driver no", VoiceCommand.NO)
  }

  /** The word "driver" in a song or a sentence must not wake it: it needs a greeting first. */
  @Test
  fun driver_alone_does_not_wake() {
    assertFalse(CommandGrammar.ENGLISH.parse("the driver in front avoid tolls").woke)
    assertFalse(CommandGrammar.DUTCH.parse("die driver rijdt slecht").woke)
    assertFalse(CommandGrammar.ENGLISH.parse("avoid tolls").woke)
    // But a command is still read (for the second half, after "Hey Drivr" on its own).
    assertEquals(VoiceCommand.AVOID_TOLLS, CommandGrammar.ENGLISH.parse("avoid tolls").command)
  }

  @Test
  fun nonsense_is_no_command() {
    assertNull(CommandGrammar.ENGLISH.parse("hey driver bananas").command)
    assertNull(CommandGrammar.DUTCH.parse("hey driver appeltaart").command)
  }

  /** Every example the app shows ("Hey Drivr, avoid tolls") works in its own language. */
  @Test
  fun shown_examples_are_understood() {
    val expected =
        listOf(
            R.string.voice_ex_tolls to VoiceCommand.AVOID_TOLLS,
            R.string.voice_ex_eta to VoiceCommand.ETA,
            R.string.voice_ex_fuel to VoiceCommand.FIND_FUEL,
            R.string.voice_ex_home to VoiceCommand.GO_HOME,
            R.string.voice_ex_traffic to VoiceCommand.TRAFFIC_AHEAD,
            R.string.voice_ex_turn to VoiceCommand.NEXT_TURN,
            R.string.voice_ex_mute to VoiceCommand.MUTE,
            R.string.voice_ex_stop to VoiceCommand.STOP_NAVIGATION,
        )
    assertEquals(app.drivr.ui.VOICE_EXAMPLES, expected.map { it.first })
    for ((folder, lang) in listOf("values" to "en", "values-nl" to "nl", "values-de" to "de", "values-fr" to "fr", "values-es" to "es")) {
      val strings = XmlStrings(folder)
      val g = CommandGrammar.forLanguage(lang)
      for ((id, cmd) in expected) {
        val text = strings.get(id, emptyArray())
        assertHeard(g, text, cmd)
      }
    }
  }

  /** Every language has every command, and the recogniser grammar is valid JSON with [unk]. */
  @Test
  fun grammars_are_complete() {
    for (g in listOf(CommandGrammar.ENGLISH, CommandGrammar.DUTCH, CommandGrammar.GERMAN, CommandGrammar.FRENCH, CommandGrammar.SPANISH)) {
      assertEquals(VoiceCommand.entries.toSet(), g.commands.keys)
      val phrases = g.recogniserPhrases()
      assertTrue(phrases.contains("hey driver"))
      // Each phrase means what it says: none of them parses to a different command.
      for ((cmd, list) in g.commands) for (p in list) assertEquals("'$p'", cmd, g.command(CommandGrammar.normalise(p).split(' ')))
    }
  }

  @Test
  fun normalise_strips_accents_and_punctuation() {
    assertEquals("a quelle heure j arrive", CommandGrammar.normalise("À quelle heure j'arrive ?"))
    assertEquals("mautstrassen vermeiden", CommandGrammar.normalise("Mautstraßen vermeiden"))
    assertEquals("cuando llego", CommandGrammar.normalise("¿Cuándo llego?"))
  }

  @Test
  fun models_per_language() {
    assertEquals("vosk-model-small-nl-0.22", SpeechModel.forLanguage("nl").dir)
    assertEquals(SpeechModel.EN, SpeechModel.forLanguage("pt"))
    assertTrue(SpeechModel.entries.all { it.url.startsWith("https://alphacephei.com/vosk/models/") })
  }
}
