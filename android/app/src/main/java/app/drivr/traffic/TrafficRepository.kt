package app.drivr.traffic

import android.util.Log
import android.util.Xml
import java.time.Instant
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser

/**
 * Live Dutch traffic from NDW open data: free, no key, government source (accidents, jams,
 * broken-down vehicles, closures). The feed is refreshed by NDW every minute; we poll every
 * [intervalMs] while the app is open. About 340 KB per download.
 */
class TrafficRepository(
    private val http: OkHttpClient,
    private val url: String = NDW_SITUATIONS_URL,
    private val intervalMs: Long = 3 * 60_000L,
) {
  private val _events = MutableStateFlow<List<TrafficEvent>>(emptyList())
  val events: StateFlow<List<TrafficEvent>> = _events.asStateFlow()

  private val _updatedAt = MutableStateFlow<Instant?>(null)
  val updatedAt: StateFlow<Instant?> = _updatedAt.asStateFlow()

  private var job: Job? = null

  fun start(scope: CoroutineScope) {
    if (job?.isActive == true) return
    job =
        scope.launch(Dispatchers.IO) {
          while (isActive) {
            runCatching { fetch() }
                .onSuccess {
                  _events.value = it
                  _updatedAt.value = Instant.now()
                }
                .onFailure { Log.w(TAG, "Traffic update failed", it) }
            delay(intervalMs)
          }
        }
  }

  fun stop() {
    job?.cancel()
    job = null
  }

  private fun fetch(): List<TrafficEvent> {
    val request = Request.Builder().url(url).build()
    http.newCall(request).execute().use { response ->
      check(response.isSuccessful) { "HTTP ${response.code}" }
      GZIPInputStream(response.body.byteStream()).use { input ->
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, "UTF-8")
        val now = Instant.now()
        return NdwParser.parse(parser).filter { it.isActive(now) }
      }
    }
  }

  companion object {
    private const val TAG = "TrafficRepository"
    const val NDW_SITUATIONS_URL = "https://opendata.ndw.nu/actueel_beeld.xml.gz"
  }
}
