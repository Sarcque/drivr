package app.drivr.voice

import java.text.Normalizer

/** What the driver can ask for after "Hey Drivr". */
enum class VoiceCommand {
  MUTE,
  UNMUTE,
  AVOID_TOLLS,
  AVOID_MOTORWAYS,
  FASTER_ROUTE,
  GO_HOME,
  GO_WORK,
  FIND_FUEL,
  FIND_CHARGING,
  FIND_PARKING,
  SHARE_ETA,
  STOP_NAVIGATION,
  ETA,
  NEXT_TURN,
  TRAFFIC_AHEAD,
  /** Answers a question on screen: end the trip, take the faster route, go with the route choice. */
  YES,
  NO,
  /** "Never mind": close the listening card. */
  CANCEL,
  HELP,
}

/** What one recognised utterance means. */
data class Heard(
    /** The wake phrase was in it. */
    val woke: Boolean,
    /** The command in it (after the wake phrase, if there was one), or null. */
    val command: VoiceCommand?,
    /** The words after the wake phrase, normalised (for "I heard …" on screen). */
    val rest: String,
)

/**
 * Wake phrases and commands per language, and the parser for what the recogniser heard.
 *
 * The phrases are written the way the language spells them (accents, ß, apostrophes) because they
 * also become the recogniser's grammar, which has to use the speech model's own words. Matching
 * ignores case, accents and punctuation, and allows extra words in between ("avoid the tolls
 * please" still matches "avoid tolls").
 */
class CommandGrammar(
    /** Greeting words before "Drivr": "hey", "hé", "oye" … */
    val greetings: List<String>,
    val commands: Map<VoiceCommand, List<String>>,
) {
  /** How "Drivr" can come out of a recogniser that has never heard the word. */
  private val names = listOf("drivr", "driver", "drivers", "draiver", "dráiver", "driveur", "drijver")
  private val nameKeys = names.map(::normalise).toSet()
  private val greetingKeys = (greetings + COMMON_GREETINGS).map(::normalise).toSet()
  private val commandKeys = commands.mapValues { (_, phrases) -> phrases.map { normalise(it).split(' ') } }

  /** Phrases for the speech engine: wake phrases, commands, and wake phrase + command in one go. */
  fun recogniserPhrases(): List<String> {
    val wakes = wakePhrases()
    val cmds = commands.values.flatten()
    return (wakes + cmds + wakes.take(3).flatMap { w -> cmds.map { "$w $it" } }).distinct()
  }

  /** "hey driver", "hé driver" … in this language, plus the English ones everybody uses. */
  fun wakePhrases(): List<String> =
      (greetings + COMMON_GREETINGS).distinct().flatMap { g -> listOf("$g driver", "$g drivr") } + listOf("drivr")

  fun parse(text: String): Heard {
    val words = normalise(text).split(' ').filter { it.isNotEmpty() }
    val wakeEnd = wakeEnd(words)
    val rest = if (wakeEnd >= 0) words.drop(wakeEnd) else words
    return Heard(woke = wakeEnd >= 0, command = command(rest), rest = rest.joinToString(" "))
  }

  /**
   * Index just after the wake phrase, or -1. Needs a greeting right before the name ("hey
   * driver"), so the word "driver" in a song or a sentence doesn't wake Drivr; "drivr" itself is
   * not a word anyone says by accident.
   */
  private fun wakeEnd(words: List<String>): Int {
    for (i in words.indices) {
      val w = words[i]
      if (w == "drivr") return i + 1
      if (w in nameKeys && i > 0 && words[i - 1] in greetingKeys) return i + 1
    }
    return -1
  }

  /** The command whose phrase fits best: all its words in order, the longest phrase wins. */
  fun command(words: List<String>): VoiceCommand? {
    if (words.isEmpty()) return null
    var best: VoiceCommand? = null
    var bestLen = 0
    for ((cmd, phrases) in commandKeys) {
      for (p in phrases) {
        if (p.size > bestLen && isSubsequence(p, words)) {
          best = cmd
          bestLen = p.size
        }
      }
    }
    return best
  }

  private fun isSubsequence(phrase: List<String>, words: List<String>): Boolean {
    var i = 0
    for (w in words) if (i < phrase.size && w == phrase[i]) i++
    return i == phrase.size
  }

  companion object {
    /** Said in every language: "Hey Drivr" is the name, "hey" travels. */
    private val COMMON_GREETINGS = listOf("hey", "hi", "hee", "ok", "okay")

    /** Lower case, no accents, apostrophes and punctuation as spaces, single spaces. */
    fun normalise(text: String): String =
        Normalizer.normalize(text.lowercase().replace("ß", "ss"), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    /** The grammar for an app language tag ("nl", "de" …); English for anything else. */
    fun forLanguage(tag: String): CommandGrammar =
        when (tag.substringBefore('-').lowercase()) {
          "nl" -> DUTCH
          "de" -> GERMAN
          "fr" -> FRENCH
          "es" -> SPANISH
          else -> ENGLISH
        }

    val ENGLISH =
        CommandGrammar(
            greetings = listOf("hey", "hi", "hello", "okay"),
            commands =
                mapOf(
                    VoiceCommand.MUTE to listOf("mute", "voice off", "sound off", "be quiet", "stop talking", "quiet"),
                    VoiceCommand.UNMUTE to listOf("unmute", "voice on", "sound on", "speak again", "turn the voice on"),
                    VoiceCommand.AVOID_TOLLS to listOf("avoid tolls", "avoid toll roads", "no tolls", "no toll roads"),
                    VoiceCommand.AVOID_MOTORWAYS to listOf("avoid motorways", "avoid highways", "no motorways", "no highways"),
                    VoiceCommand.FASTER_ROUTE to listOf("faster route", "a faster way", "alternative route", "other route", "alternatives", "is there a faster route"),
                    VoiceCommand.GO_HOME to listOf("go home", "take me home", "drive home", "navigate home", "home"),
                    VoiceCommand.GO_WORK to listOf("go to work", "take me to work", "drive to work", "navigate to work", "work"),
                    VoiceCommand.FIND_FUEL to listOf("fuel", "petrol", "gas station", "petrol station", "find fuel", "find a gas station"),
                    VoiceCommand.FIND_CHARGING to listOf("charging", "charger", "charging station", "find a charger"),
                    VoiceCommand.FIND_PARKING to listOf("parking", "find parking", "where can i park", "car park"),
                    VoiceCommand.SHARE_ETA to listOf("share eta", "share my eta", "share arrival time", "share my arrival time", "send my eta"),
                    VoiceCommand.STOP_NAVIGATION to listOf("stop navigation", "stop navigating", "end trip", "end the trip", "stop the route", "cancel the route", "end navigation"),
                    VoiceCommand.ETA to listOf("eta", "what's my eta", "what is my eta", "when do i arrive", "when will i arrive", "arrival time", "how long", "how far"),
                    VoiceCommand.NEXT_TURN to listOf("next turn", "where do i turn", "what's next", "what is next", "next instruction", "repeat"),
                    VoiceCommand.TRAFFIC_AHEAD to listOf("traffic", "traffic ahead", "any traffic", "how is the traffic", "traffic jams", "jams", "checks", "any checks"),
                    VoiceCommand.YES to listOf("yes", "yeah", "yep", "sure", "do it", "switch", "go"),
                    VoiceCommand.NO to listOf("no", "nope", "keep", "keep my route", "keep the route", "cancel"),
                    VoiceCommand.CANCEL to listOf("never mind", "nothing", "forget it"),
                    VoiceCommand.HELP to listOf("help", "what can i say"),
                ),
        )

    val DUTCH =
        CommandGrammar(
            greetings = listOf("hé", "hee", "hey", "hoi", "hai", "oké"),
            commands =
                mapOf(
                    VoiceCommand.MUTE to listOf("stil", "stilte", "geluid uit", "spraak uit", "stem uit", "stop met praten"),
                    VoiceCommand.UNMUTE to listOf("geluid aan", "spraak aan", "stem aan", "praat weer"),
                    VoiceCommand.AVOID_TOLLS to listOf("vermijd tol", "vermijd tolwegen", "geen tol", "geen tolwegen", "tolwegen vermijden"),
                    VoiceCommand.AVOID_MOTORWAYS to listOf("vermijd snelwegen", "vermijd de snelweg", "geen snelwegen", "geen snelweg", "snelwegen vermijden"),
                    VoiceCommand.FASTER_ROUTE to listOf("snellere route", "andere route", "alternatieve route", "alternatieven", "is er een snellere route"),
                    VoiceCommand.GO_HOME to listOf("naar huis", "rij naar huis", "navigeer naar huis", "breng me naar huis"),
                    VoiceCommand.GO_WORK to listOf("naar werk", "naar het werk", "naar mijn werk", "rij naar mijn werk", "naar kantoor"),
                    VoiceCommand.FIND_FUEL to listOf("tankstation", "tanken", "benzine", "brandstof", "zoek een tankstation"),
                    VoiceCommand.FIND_CHARGING to listOf("laadpaal", "laadstation", "opladen", "laden", "zoek een laadpaal"),
                    VoiceCommand.FIND_PARKING to listOf("parkeren", "parkeerplaats", "parkeergarage", "waar kan ik parkeren"),
                    VoiceCommand.SHARE_ETA to listOf("deel aankomsttijd", "deel mijn aankomsttijd", "aankomsttijd delen", "deel eta"),
                    VoiceCommand.STOP_NAVIGATION to listOf("stop navigatie", "stop de navigatie", "stop de route", "stop de rit", "rit beëindigen", "beëindig de rit", "stoppen met navigeren"),
                    VoiceCommand.ETA to listOf("hoe laat ben ik er", "wanneer ben ik er", "aankomsttijd", "hoe lang nog", "hoe ver nog", "hoe lang duurt het nog"),
                    VoiceCommand.NEXT_TURN to listOf("volgende afslag", "waar moet ik heen", "wat is de volgende", "volgende instructie", "herhaal"),
                    VoiceCommand.TRAFFIC_AHEAD to listOf("verkeer", "file", "files", "is er file", "hoe is het verkeer", "controles", "zijn er controles"),
                    VoiceCommand.YES to listOf("ja", "jawel", "doe maar", "oké", "prima", "wissel", "rijden"),
                    VoiceCommand.NO to listOf("nee", "houden", "huidige route", "deze route houden"),
                    VoiceCommand.CANCEL to listOf("laat maar", "laat maar zitten", "niks", "vergeet het"),
                    VoiceCommand.HELP to listOf("help", "wat kan ik zeggen"),
                ),
        )

    val GERMAN =
        CommandGrammar(
            greetings = listOf("hey", "hei", "hallo", "okay"),
            commands =
                mapOf(
                    VoiceCommand.MUTE to listOf("stumm", "ton aus", "sprache aus", "ruhe", "leise"),
                    VoiceCommand.UNMUTE to listOf("ton an", "ton ein", "sprache an", "sprache ein", "wieder sprechen"),
                    VoiceCommand.AVOID_TOLLS to listOf("maut vermeiden", "keine maut", "mautstraßen vermeiden", "ohne maut"),
                    VoiceCommand.AVOID_MOTORWAYS to listOf("autobahn vermeiden", "autobahnen vermeiden", "keine autobahn", "ohne autobahn"),
                    VoiceCommand.FASTER_ROUTE to listOf("schnellere route", "andere route", "alternative route", "alternativen", "gibt es eine schnellere route"),
                    VoiceCommand.GO_HOME to listOf("nach hause", "fahr nach hause", "navigiere nach hause", "bring mich nach hause"),
                    VoiceCommand.GO_WORK to listOf("zur arbeit", "fahr zur arbeit", "navigiere zur arbeit", "ins büro"),
                    VoiceCommand.FIND_FUEL to listOf("tankstelle", "tanken", "benzin", "sprit", "finde eine tankstelle"),
                    VoiceCommand.FIND_CHARGING to listOf("ladestation", "ladesäule", "laden", "aufladen"),
                    VoiceCommand.FIND_PARKING to listOf("parkplatz", "parken", "parkhaus", "wo kann ich parken"),
                    VoiceCommand.SHARE_ETA to listOf("ankunftszeit teilen", "ankunft teilen", "teile meine ankunftszeit"),
                    VoiceCommand.STOP_NAVIGATION to listOf("navigation beenden", "navigation stoppen", "fahrt beenden", "route beenden", "beende die navigation"),
                    VoiceCommand.ETA to listOf("wann bin ich da", "wann komme ich an", "ankunftszeit", "wie lange noch", "wie weit noch"),
                    VoiceCommand.NEXT_TURN to listOf("nächste abbiegung", "wo muss ich hin", "was kommt als nächstes", "nächste anweisung", "wiederholen"),
                    VoiceCommand.TRAFFIC_AHEAD to listOf("verkehr", "stau", "staus", "gibt es stau", "wie ist der verkehr", "kontrollen", "gibt es kontrollen"),
                    VoiceCommand.YES to listOf("ja", "jawohl", "genau", "mach das", "wechseln", "los"),
                    VoiceCommand.NO to listOf("nein", "behalten", "aktuelle route", "route behalten"),
                    VoiceCommand.CANCEL to listOf("vergiss es", "egal", "nichts"),
                    VoiceCommand.HELP to listOf("hilfe", "was kann ich sagen"),
                ),
        )

    val FRENCH =
        CommandGrammar(
            greetings = listOf("hé", "eh", "salut", "ok"),
            commands =
                mapOf(
                    VoiceCommand.MUTE to listOf("silence", "couper le son", "coupe le son", "voix désactivée", "tais toi"),
                    VoiceCommand.UNMUTE to listOf("remettre le son", "remets le son", "réactiver la voix", "voix activée"),
                    VoiceCommand.AVOID_TOLLS to listOf("éviter les péages", "évite les péages", "sans péage", "pas de péage"),
                    VoiceCommand.AVOID_MOTORWAYS to listOf("éviter les autoroutes", "évite les autoroutes", "sans autoroute", "pas d'autoroute"),
                    VoiceCommand.FASTER_ROUTE to listOf("itinéraire plus rapide", "route plus rapide", "autre itinéraire", "itinéraire bis", "alternatives"),
                    VoiceCommand.GO_HOME to listOf("à la maison", "rentrer à la maison", "rentre à la maison", "chez moi", "on rentre"),
                    VoiceCommand.GO_WORK to listOf("au travail", "aller au travail", "va au travail", "au bureau"),
                    VoiceCommand.FIND_FUEL to listOf("station service", "essence", "carburant", "faire le plein"),
                    VoiceCommand.FIND_CHARGING to listOf("borne de recharge", "recharge", "recharger", "borne"),
                    VoiceCommand.FIND_PARKING to listOf("parking", "se garer", "stationnement", "où me garer"),
                    VoiceCommand.SHARE_ETA to listOf("partager l'heure d'arrivée", "partage mon heure d'arrivée", "partager mon arrivée"),
                    VoiceCommand.STOP_NAVIGATION to listOf("arrêter la navigation", "arrête la navigation", "terminer le trajet", "fin du trajet", "arrêter le guidage"),
                    VoiceCommand.ETA to listOf("heure d'arrivée", "quand est-ce que j'arrive", "à quelle heure j'arrive", "combien de temps", "c'est encore loin"),
                    VoiceCommand.NEXT_TURN to listOf("prochaine direction", "prochain virage", "où dois-je tourner", "et ensuite", "répète"),
                    VoiceCommand.TRAFFIC_AHEAD to listOf("trafic", "circulation", "bouchons", "embouteillages", "contrôles", "il y a des bouchons"),
                    VoiceCommand.YES to listOf("oui", "d'accord", "vas-y", "changer", "ouais"),
                    VoiceCommand.NO to listOf("non", "garder", "garder l'itinéraire", "garde l'itinéraire"),
                    VoiceCommand.CANCEL to listOf("laisse tomber", "rien", "oublie"),
                    VoiceCommand.HELP to listOf("aide", "que puis-je dire", "qu'est-ce que je peux dire"),
                ),
        )

    val SPANISH =
        CommandGrammar(
            greetings = listOf("oye", "ey", "hola"),
            commands =
                mapOf(
                    VoiceCommand.MUTE to listOf("silencio", "silenciar", "quitar el sonido", "voz desactivada", "cállate"),
                    VoiceCommand.UNMUTE to listOf("activar voz", "activar la voz", "activar el sonido", "voz activada"),
                    VoiceCommand.AVOID_TOLLS to listOf("evitar peajes", "evita los peajes", "sin peajes", "evitar los peajes"),
                    VoiceCommand.AVOID_MOTORWAYS to listOf("evitar autopistas", "evita las autopistas", "sin autopistas", "evitar las autopistas"),
                    VoiceCommand.FASTER_ROUTE to listOf("ruta más rápida", "otra ruta", "ruta alternativa", "alternativas", "hay una ruta más rápida"),
                    VoiceCommand.GO_HOME to listOf("a casa", "llévame a casa", "ir a casa", "vamos a casa"),
                    VoiceCommand.GO_WORK to listOf("al trabajo", "llévame al trabajo", "ir al trabajo", "a la oficina"),
                    VoiceCommand.FIND_FUEL to listOf("gasolinera", "gasolina", "combustible", "repostar"),
                    VoiceCommand.FIND_CHARGING to listOf("punto de carga", "cargador", "estación de carga", "cargar el coche"),
                    VoiceCommand.FIND_PARKING to listOf("aparcamiento", "aparcar", "parking", "dónde aparco"),
                    VoiceCommand.SHARE_ETA to listOf("compartir hora de llegada", "compartir la hora de llegada", "comparte mi llegada", "compartir llegada"),
                    VoiceCommand.STOP_NAVIGATION to listOf("detener navegación", "detener la navegación", "terminar viaje", "terminar el viaje", "parar la ruta", "finalizar viaje"),
                    VoiceCommand.ETA to listOf("hora de llegada", "cuándo llego", "a qué hora llego", "cuánto falta", "cuánto queda"),
                    VoiceCommand.NEXT_TURN to listOf("próximo giro", "siguiente giro", "dónde giro", "siguiente instrucción", "repite"),
                    VoiceCommand.TRAFFIC_AHEAD to listOf("tráfico", "atasco", "atascos", "hay tráfico", "controles", "hay controles"),
                    VoiceCommand.YES to listOf("sí", "claro", "de acuerdo", "cambiar", "vamos"),
                    VoiceCommand.NO to listOf("no", "mantener", "mantener la ruta", "quedarme con esta ruta"),
                    VoiceCommand.CANCEL to listOf("olvídalo", "nada", "da igual"),
                    VoiceCommand.HELP to listOf("ayuda", "qué puedo decir"),
                ),
        )
  }
}
