package app.drivr.places

import app.drivr.i18n.L10n
import app.drivr.R
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/**
 * A search that failed, with a reason a driver can act on ("No internet connection", "OpenStreetMap
 * servers busy") instead of a generic "didn't answer in time".
 */
class PlacesException(message: String, cause: Throwable? = null) : Exception(message, cause) {
  companion object {
    fun http(code: Int, url: String) =
        PlacesException(
            when (code) {
              429 -> L10n.s(R.string.places_err_too_many, OverpassClient.hostOf(url))
              in 500..599 -> L10n.s(R.string.places_err_busy_http, OverpassClient.hostOf(url), code)
              else -> L10n.s(R.string.places_err_http, OverpassClient.hostOf(url), code)
            })

    /** One message for a set of failures (all mirrors), naming the most telling cause. */
    fun from(errors: List<Exception>): PlacesException {
      val offline = errors.isNotEmpty() && errors.all { it is UnknownHostException }
      if (offline) return PlacesException(L10n.s(R.string.places_err_offline), errors.first())
      val reasons = errors.map { reason(it) }.distinct()
      return PlacesException(L10n.s(R.string.places_err_servers, reasons.joinToString("; ")), errors.firstOrNull())
    }

    fun reason(e: Throwable): String =
        when (e) {
          is PlacesException -> e.message ?: L10n.s(R.string.places_reason_error)
          is UnknownHostException -> L10n.s(R.string.places_reason_offline)
          is SocketTimeoutException -> L10n.s(R.string.places_reason_timeout)
          is IOException -> e.message?.takeIf { it.isNotBlank() } ?: L10n.s(R.string.places_reason_network)
          else -> e.message ?: e.javaClass.simpleName
        }
  }
}

/** Runs an OkHttp call without blocking a thread; cancelling the coroutine cancels the request. */
suspend fun <T> Call.await(read: (Response) -> T): T = suspendCancellableCoroutine { cont ->
  cont.invokeOnCancellation { runCatching { cancel() } }
  enqueue(
      object : Callback {
        override fun onFailure(call: Call, e: IOException) {
          if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
          val result = runCatching { response.use(read) }
          if (cont.isActive) result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
        }
      })
}
