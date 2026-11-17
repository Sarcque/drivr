package app.drivr.places

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The race behind "find fuel": first useful answer wins, a dead server can't stall it. */
class FirstUsefulTest {
  private fun src(delayMs: Long, headStart: Long = 0, result: () -> List<String>): Pair<Long, suspend () -> List<String>> =
      headStart to { delay(delayMs); result() }

  @Test
  fun backup_source_wins_when_the_first_hangs() = runBlocking {
    val t0 = System.currentTimeMillis()
    val r = firstUseful(listOf(src(60_000) { listOf("overpass") }, src(50, headStart = 200) { listOf("photon") }), { it.isNotEmpty() })
    assertEquals(listOf("photon"), r)
    assertTrue(System.currentTimeMillis() - t0 < 2_000)
  }

  @Test
  fun backup_starts_at_once_when_the_first_fails() = runBlocking {
    val t0 = System.currentTimeMillis()
    val r = firstUseful(listOf(src(10) { throw PlacesException("HTTP 429") }, src(10, headStart = 5_000) { listOf("photon") }), { it.isNotEmpty() })
    assertEquals(listOf("photon"), r)
    assertTrue(System.currentTimeMillis() - t0 < 2_000)
  }

  @Test
  fun empty_only_after_everyone_answered() = runBlocking {
    val r = firstUseful(listOf(src(10) { emptyList() }, src(300) { listOf("late but real") }), { it.isNotEmpty() })
    assertEquals(listOf("late but real"), r)
    val none = firstUseful(listOf(src(10) { emptyList() }, src(20) { emptyList<String>() }), { it.isNotEmpty() })
    assertEquals(emptyList<String>(), none)
  }

  @Test
  fun all_failed_names_the_causes() = runBlocking {
    val e =
        runCatching {
              firstUseful(
                  listOf(src(5) { throw java.net.UnknownHostException("x") }, src(5) { throw java.net.UnknownHostException("y") }),
                  { it.isNotEmpty() })
            }
            .exceptionOrNull()
    assertTrue(e is PlacesException)
    assertEquals("No internet connection", e!!.message)
  }
}
