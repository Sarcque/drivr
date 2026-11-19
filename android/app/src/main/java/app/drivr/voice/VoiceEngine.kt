package app.drivr.voice

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * One offline speech model per language: Vosk's small models (Apache-2.0, ~40 MB each), from
 * alphacephei.com, the Vosk project's own download site. Downloaded once when voice commands are
 * switched on; after that recognition runs entirely on the phone and no audio leaves it.
 */
enum class SpeechModel(val language: String, val dir: String, val sizeMb: Int) {
  EN("en", "vosk-model-small-en-us-0.15", 40),
  NL("nl", "vosk-model-small-nl-0.22", 39),
  DE("de", "vosk-model-small-de-0.15", 45),
  FR("fr", "vosk-model-small-fr-0.22", 41),
  ES("es", "vosk-model-small-es-0.42", 39);

  val url: String get() = "https://alphacephei.com/vosk/models/$dir.zip"

  companion object {
    fun forLanguage(tag: String): SpeechModel =
        entries.firstOrNull { it.language == tag.substringBefore('-').lowercase() } ?: EN
  }
}

sealed interface ModelState {
  data object Missing : ModelState

  /** [fraction] 0..1, or null while the size is unknown. */
  data class Downloading(val fraction: Float?) : ModelState

  data object Ready : ModelState

  data class Failed(val reason: String) : ModelState
}

/**
 * Microphone → Vosk, only while Drivr is on screen and voice commands are on. The recogniser is
 * limited to Drivr's phrases (a grammar), which makes a small model accurate and cheap enough to
 * run continuously, and keeps random speech from turning into commands.
 */
class VoiceEngine(context: Context, private val http: OkHttpClient) {
  private val root = File(context.filesDir, "speech")
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var downloadJob: Job? = null

  private val _state = MutableStateFlow<ModelState>(ModelState.Missing)
  val state: StateFlow<ModelState> = _state.asStateFlow()

  var model: SpeechModel = SpeechModel.EN
    private set

  /** Is the speech engine itself (native library) usable on this phone? */
  private val nativeOk: Boolean by lazy {
    runCatching { org.vosk.LibVosk.setLogLevel(org.vosk.LogLevel.WARNINGS) }
        .onFailure { Log.w(TAG, "Vosk not available on this device", it) }
        .isSuccess
  }

  private var loaded: Pair<SpeechModel, org.vosk.Model>? = null
  private var service: org.vosk.android.SpeechService? = null
  private var startJob: Job? = null

  /** Point at the app language's model; reports whether it is already on the phone. */
  fun useLanguage(tag: String) {
    model = SpeechModel.forLanguage(tag)
    if (_state.value !is ModelState.Downloading) _state.value = if (isReady(model)) ModelState.Ready else ModelState.Missing
  }

  private fun dirOf(m: SpeechModel) = File(root, m.dir)

  private fun isReady(m: SpeechModel) = File(dirOf(m), READY_MARKER).exists()

  /** Fetches and unpacks the current language's model, if it isn't there yet. */
  fun download() {
    val m = model
    if (isReady(m)) {
      _state.value = ModelState.Ready
      return
    }
    if (downloadJob?.isActive == true) return
    _state.value = ModelState.Downloading(null)
    downloadJob =
        scope.launch {
          try {
            fetch(m)
            _state.value = if (m == model) ModelState.Ready else (if (isReady(model)) ModelState.Ready else ModelState.Missing)
          } catch (e: Exception) {
            Log.w(TAG, "Speech model download failed", e)
            _state.value = ModelState.Failed(e.message ?: e.javaClass.simpleName)
          }
        }
  }

  private fun fetch(m: SpeechModel) {
    root.mkdirs()
    val zip = File(root, "${m.dir}.zip.part")
    val response = http.newCall(Request.Builder().url(m.url).build()).execute()
    response.use {
      if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
      val body = it.body
      val total = body.contentLength().takeIf { n -> n > 0 }
      body.byteStream().use { input ->
        zip.outputStream().use { out ->
          val buf = ByteArray(64 * 1024)
          var done = 0L
          var lastReport = 0L
          while (scope.isActive) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            done += n
            if (done - lastReport > 512 * 1024) {
              lastReport = done
              _state.value = ModelState.Downloading(total?.let { t -> (done.toFloat() / t).coerceIn(0f, 1f) })
            }
          }
        }
      }
    }
    val target = dirOf(m)
    val tmp = File(root, "${m.dir}.unpacking")
    tmp.deleteRecursively()
    unzipStripTop(zip, tmp)
    zip.delete()
    target.deleteRecursively()
    if (!tmp.renameTo(target)) throw IOException("Could not move the model into place")
    File(target, READY_MARKER).writeText(m.url)
  }

  /** Unzips [zip] into [into], dropping the archive's top folder; refuses paths outside [into]. */
  private fun unzipStripTop(zip: File, into: File) {
    into.mkdirs()
    val base = into.canonicalPath + File.separator
    ZipInputStream(zip.inputStream().buffered()).use { z ->
      while (true) {
        val e = z.nextEntry ?: break
        val name = e.name.substringAfter('/', "")
        if (name.isEmpty()) continue
        val out = File(into, name)
        if (!out.canonicalPath.startsWith(base)) throw IOException("Bad entry in model zip: ${e.name}")
        if (e.isDirectory) out.mkdirs()
        else {
          out.parentFile?.mkdirs()
          out.outputStream().use { z.copyTo(it) }
        }
      }
    }
  }

  /** Removes every downloaded model (switching the setting off keeps them; this frees the space). */
  fun deleteModels() {
    stop()
    downloadJob?.cancel()
    loaded?.second?.close()
    loaded = null
    root.deleteRecursively()
    _state.value = ModelState.Missing
  }

  fun downloadedMb(): Int =
      (root.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1_000_000).toInt()

  /**
   * Starts listening with [grammar]. [onPartial] and [onResult] get the recognised text on the main
   * thread; [isSpeaking] says when Drivr's own voice is talking, so it doesn't hear itself.
   */
  fun start(
      grammar: CommandGrammar,
      isSpeaking: () -> Boolean,
      onPartial: (String) -> Unit,
      onResult: (String) -> Unit,
      onError: (String) -> Unit,
  ) {
    if (service != null || startJob?.isActive == true) return
    val m = model
    if (!isReady(m)) return
    if (!nativeOk) {
      onError("unsupported")
      return
    }
    startJob =
        scope.launch {
          try {
            val vm = loaded?.takeIf { it.first == m }?.second ?: org.vosk.Model(dirOf(m).path).also {
              loaded?.second?.close()
              loaded = m to it
            }
            val recognizer = org.vosk.Recognizer(vm, SAMPLE_RATE, grammarJson(grammar))
            val s = org.vosk.android.SpeechService(recognizer, SAMPLE_RATE)
            val listener =
                object : org.vosk.android.RecognitionListener {
                  override fun onPartialResult(hypothesis: String?) {
                    if (isSpeaking()) return
                    textOf(hypothesis, "partial")?.let(onPartial)
                  }

                  override fun onResult(hypothesis: String?) {
                    if (isSpeaking()) return
                    textOf(hypothesis, "text")?.let(onResult)
                  }

                  override fun onFinalResult(hypothesis: String?) {}

                  override fun onError(exception: Exception?) {
                    Log.w(TAG, "Recognition error", exception)
                    onError(exception?.message ?: "error")
                  }

                  override fun onTimeout() {}
                }
            if (!s.startListening(listener)) throw IOException("Microphone busy")
            service = s
          } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
          } catch (e: Throwable) {
            Log.w(TAG, "Could not start listening", e)
            kotlinx.coroutines.withContext(Dispatchers.Main) { onError(e.message ?: "error") }
          }
        }
  }

  fun stop() {
    startJob?.cancel()
    startJob = null
    service?.let {
      runCatching { it.stop() }
      runCatching { it.shutdown() }
    }
    service = null
  }

  val listening: Boolean get() = service != null

  companion object {
    private const val TAG = "VoiceEngine"
    private const val READY_MARKER = ".drivr-ready"
    const val SAMPLE_RATE = 16_000f

    /** Vosk's grammar: the phrases, plus [unk] so other speech lands there instead of on a command. */
    fun grammarJson(grammar: CommandGrammar): String =
        JSONArray().apply {
          grammar.recogniserPhrases().forEach { put(it.lowercase().replace("-", " ")) }
          put("[unk]")
        }.toString()

    /** Vosk answers {"partial": "..."} / {"text": "..."}; "[unk]" is noise. */
    internal fun textOf(json: String?, key: String): String? =
        runCatching { JSONObject(json ?: return null).optString(key) }.getOrNull()
            ?.replace("[unk]", " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
  }
}

