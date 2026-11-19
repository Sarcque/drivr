package app.drivr.voice

/** What Drivr says (and shows) back after a command. */
data class VoiceReply(
    val text: String,
    /** False when it couldn't do it (no home set, not driving …): shown in the muted colour. */
    val ok: Boolean = true,
    /** Drivr asked a yes/no question: a plain "yes"/"no" counts for a few seconds, no wake phrase. */
    val asks: Boolean = false,
)

/** Carries out a command; the view model does the real work. */
fun interface VoiceActions {
  fun run(command: VoiceCommand): VoiceReply
}

/** What the voice card on the map shows. */
sealed interface VoiceUi {
  /** Not listening: the setting is off, no permission, no speech model yet, or Drivr is in the background. */
  data object Off : VoiceUi

  /** Listening for "Hey Drivr" (the small mic chip). */
  data object Waiting : VoiceUi

  /** Heard "Hey Drivr": listening for the command. [heard] is what came in so far. */
  data class Awake(val heard: String, val until: Long) : VoiceUi

  /** The answer, shown for a few seconds. [heard] is the command as recognised. */
  data class Answer(val heard: String, val reply: VoiceReply, val until: Long) : VoiceUi

  /** "What can I say?" or a command it didn't understand: examples in the app language. */
  data class Help(val heard: String, val until: Long) : VoiceUi
}

/**
 * The "Hey Drivr" state machine, fed with what the recogniser hears. No Android in here, so the
 * whole flow is unit tested: wake phrase, command in the same breath or the next, the yes/no
 * follow-up, timeouts.
 */
class VoiceAssistant(
    var grammar: CommandGrammar,
    private val actions: VoiceActions,
    private val now: () -> Long = System::currentTimeMillis,
) {
  var ui: VoiceUi = VoiceUi.Off
    private set

  /** Until when a bare "yes" / "no" answers Drivr's question. */
  private var followUpUntil = 0L

  fun setListening(on: Boolean) {
    ui = if (on) (if (ui == VoiceUi.Off) VoiceUi.Waiting else ui) else VoiceUi.Off
    if (!on) followUpUntil = 0L
  }

  /** Words so far in the current utterance. Returns true when the wake phrase just arrived. */
  fun onPartial(text: String): Boolean {
    if (ui == VoiceUi.Off || text.isBlank()) return false
    val heard = grammar.parse(text)
    val awake = ui as? VoiceUi.Awake
    return when {
      heard.woke && awake == null -> {
        ui = VoiceUi.Awake(heard.rest, now() + AWAKE_MS)
        true
      }
      awake != null && heard.rest.isNotEmpty() -> {
        ui = awake.copy(heard = heard.rest, until = maxOf(awake.until, now() + 2_000))
        false
      }
      else -> false
    }
  }

  /** A finished utterance. Returns true when Drivr just woke up and is waiting for the command. */
  fun onResult(text: String): Boolean {
    if (ui == VoiceUi.Off) return false
    val t = now()
    expire(t)
    val heard = grammar.parse(text)
    val wasAwake = ui is VoiceUi.Awake
    return when {
      heard.woke && heard.command != null -> run(heard)
      heard.woke -> {
        val justWoke = !wasAwake
        ui = VoiceUi.Awake("", t + AWAKE_MS)
        justWoke
      }
      wasAwake && heard.command != null -> run(heard)
      wasAwake && heard.rest.isNotEmpty() -> {
        ui = VoiceUi.Help(heard.rest, t + HELP_MS)
        false
      }
      t < followUpUntil && (heard.command == VoiceCommand.YES || heard.command == VoiceCommand.NO) -> run(heard)
      else -> false
    }
  }

  /** Tap on the mic chip: same as saying "Hey Drivr". */
  fun tapToTalk() {
    if (ui == VoiceUi.Off) return
    ui = if (ui is VoiceUi.Awake) VoiceUi.Waiting else VoiceUi.Awake("", now() + AWAKE_MS)
  }

  /** Close the card (tap on it). */
  fun dismiss() {
    if (ui != VoiceUi.Off) ui = VoiceUi.Waiting
  }

  /** An answer that came later (e.g. after looking for a faster route). */
  fun show(reply: VoiceReply) {
    if (ui == VoiceUi.Off) return
    val t = now()
    if (reply.asks) followUpUntil = t + FOLLOW_UP_MS
    ui = VoiceUi.Answer("", reply, t + if (reply.asks) FOLLOW_UP_MS else ANSWER_MS)
  }

  /** Drops the card once its time is up. */
  fun expire(t: Long = now()) {
    val until =
        when (val u = ui) {
          is VoiceUi.Awake -> u.until
          is VoiceUi.Answer -> u.until
          is VoiceUi.Help -> u.until
          else -> return
        }
    if (t >= until) ui = VoiceUi.Waiting
  }

  private fun run(heard: Heard): Boolean {
    val t = now()
    val cmd = heard.command ?: return false
    followUpUntil = 0L
    when (cmd) {
      VoiceCommand.CANCEL -> ui = VoiceUi.Waiting
      VoiceCommand.HELP -> ui = VoiceUi.Help("", t + HELP_MS)
      else -> {
        val reply = actions.run(cmd)
        if (reply.asks) followUpUntil = t + FOLLOW_UP_MS
        ui = VoiceUi.Answer(heard.rest, reply, t + if (reply.asks) FOLLOW_UP_MS else ANSWER_MS)
      }
    }
    return false
  }

  companion object {
    /** How long it listens for the command after "Hey Drivr". */
    const val AWAKE_MS = 7_000L
    const val ANSWER_MS = 5_000L
    const val HELP_MS = 9_000L
    const val FOLLOW_UP_MS = 9_000L
  }
}
