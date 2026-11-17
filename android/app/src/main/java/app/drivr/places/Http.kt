package app.drivr.places

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
              429 -> "Too many requests to ${OverpassClient.hostOf(url)} (HTTP 429)"
              in 500..599 -> "${OverpassClient.hostOf(url)} is busy (HTTP $code)"
              else -> "${OverpassClient.hostOf(url)} answered HTTP $code"
            })

    /** One message for a set of failures (all mirrors), naming the most telling cause. */
    fun from(errors: List<Exception>): PlacesException {
      val offline = errors.isNotEmpty() && errors.all { it is UnknownHostException }
      if (offline) return PlacesException("No internet connection", errors.first())
      val reasons = errors.map { reason(it) }.distinct()
      return PlacesException("OpenStreetMap servers didn't answer: " + reasons.joinToString("; "), errors.firstOrNull())
    }

    fun reason(e: Throwable): String =
        when (e) {
          is PlacesException -> e.message ?: "error"
          is UnknownHostException -> "no internet"
          is SocketTimeoutException -> "timed out"
          is IOException -> e.message?.takeIf { it.isNotBlank() } ?: "network error"
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
