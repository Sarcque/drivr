package app.drivr.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAssistantTest {
  private var t = 1_000L
  private val ran = mutableListOf<VoiceCommand>()
  private var asks = false
  private val assistant =
      VoiceAssistant(CommandGrammar.ENGLISH, { ran += it; VoiceReply("done $it", asks = asks) }, now = { t }).apply { setListening(true) }

  @Test
  fun off_hears_nothing() {
    assistant.setListening(false)
    assertFalse(assistant.onResult("hey driver mute"))
    assertEquals(VoiceUi.Off, assistant.ui)
    assertTrue(ran.isEmpty())
  }

  @Test
  fun wake_and_command_in_one_breath() {
    assistant.onResult("hey driver avoid tolls")
    assertEquals(listOf(VoiceCommand.AVOID_TOLLS), ran)
    val ui = assistant.ui as VoiceUi.Answer
    assertEquals("done AVOID_TOLLS", ui.reply.text)
    assertEquals("avoid tolls", ui.heard)
  }

  @Test
  fun wake_then_command() {
    assertTrue(assistant.onPartial("hey driver"))
    assertTrue(assistant.ui is VoiceUi.Awake)
    assertFalse("no second beep", assistant.onResult("hey driver"))
    t += 2_000
    assistant.onResult("next turn")
    assertEquals(listOf(VoiceCommand.NEXT_TURN), ran)
  }

  @Test
  fun command_without_wake_is_ignored() {
    assistant.onResult("avoid tolls")
    assertTrue(ran.isEmpty())
    assertEquals(VoiceUi.Waiting, assistant.ui)
  }

  @Test
  fun listening_times_out() {
    assistant.onResult("hey driver")
    t += VoiceAssistant.AWAKE_MS + 1
    assistant.onResult("avoid tolls")
    assertTrue(ran.isEmpty())
    assertEquals(VoiceUi.Waiting, assistant.ui)
  }

  @Test
  fun not_understood_shows_examples() {
    assistant.onResult("hey driver")
    assistant.onResult("bananas")
    assertEquals(VoiceUi.Help("bananas", t + VoiceAssistant.HELP_MS), assistant.ui)
  }

  @Test
  fun bare_yes_answers_only_a_question() {
    asks = true
    assistant.onResult("hey driver end trip")
    asks = false
    t += 3_000
    assistant.onResult("yes")
    assertEquals(listOf(VoiceCommand.STOP_NAVIGATION, VoiceCommand.YES), ran)
    // No question open any more: a bare "yes" in conversation does nothing.
    t += 1_000
    assistant.onResult("yes")
    assertEquals(2, ran.size)
  }

  @Test
  fun yes_after_the_question_expired_is_ignored() {
    asks = true
    assistant.onResult("hey driver end trip")
    t += VoiceAssistant.FOLLOW_UP_MS + 1
    assistant.onResult("yes")
    assertEquals(listOf(VoiceCommand.STOP_NAVIGATION), ran)
  }

  @Test
  fun tap_to_talk_and_cancel() {
    assistant.tapToTalk()
    assertTrue(assistant.ui is VoiceUi.Awake)
    assistant.onResult("never mind")
    assertEquals(VoiceUi.Waiting, assistant.ui)
    assertTrue(ran.isEmpty())
  }

  @Test
  fun help() {
    assistant.onResult("hey driver what can i say")
    assertTrue(assistant.ui is VoiceUi.Help)
  }

  @Test
  fun answer_card_closes_by_itself() {
    assistant.onResult("hey driver mute")
    t += VoiceAssistant.ANSWER_MS
    assistant.expire()
    assertEquals(VoiceUi.Waiting, assistant.ui)
  }
}
