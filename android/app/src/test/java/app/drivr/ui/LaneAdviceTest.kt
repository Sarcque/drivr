package app.drivr.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.ferrostar.LaneInfo

class LaneAdviceTest {
  private val s = LaneInfo(false, listOf("straight"), null)
  private val r = LaneInfo(true, listOf("slight right"), "slight right")
  private val l = LaneInfo(true, listOf("slight left"), "slight left")
  /** Five lanes, only the rightmost goes to the exit (the Baarn screenshot). */
  private val baarn = listOf(s, s, s, s, r)

  @Test
  fun counts_worst_case_lane_changes() {
    assertEquals(4, laneChangesNeeded(baarn))
    assertEquals(2, laneChangesNeeded(listOf(s, s, r, r)))
    assertEquals(1, laneChangesNeeded(listOf(l, s)))
    assertEquals(0, laneChangesNeeded(listOf(r, r)))
  }

  @Test
  fun starts_early_enough_for_every_change() {
    // 4 changes: all but one before the last 500 m at 400 m each, plus 400 m slack.
    assertEquals(2100.0, laneChangeStartM(4), 0.1)
    assertEquals(900.0, laneChangeStartM(1), 0.1)
    // Never more than one change left inside the final stretch.
    for (n in 1..6) {
      val changesBeforeFinal = (laneChangeStartM(n) - FINAL_STRETCH_M) / METRES_PER_LANE_CHANGE
      assertTrue("n=$n", changesBeforeFinal >= n - 1)
    }
  }

  @Test
  fun progressive_prompts_for_a_motorway_exit() {
    // 3.5 km out: heads-up (start is at 2.1 km, heads-up 1.5 km before that).
    val far = laneAdvice(baarn, 3500.0, motorway = true)!!
    assertEquals("Keep right soon · up to 4 lanes", far.text)
    assertFalse(far.urgent)
    // 1.5 km out: move now.
    val now = laneAdvice(baarn, 1500.0, motorway = true)!!
    assertEquals("Move right now · up to 4 lanes", now.text)
    assertTrue(now.urgent)
    // Last 300 m: the plain lane hint.
    assertEquals("Use the right lane", laneAdvice(baarn, 300.0, motorway = true)!!.text)
    // Far away: just the hint.
    assertEquals("Use the right lane", laneAdvice(baarn, 9000.0, motorway = true)!!.text)
  }

  @Test
  fun left_side_and_no_change_needed() {
    assertEquals("Move left now · 1 lane", laneAdvice(listOf(l, s), 700.0, motorway = false)!!.text)
    assertEquals(null, laneAdvice(listOf(r, r), 700.0, motorway = true))
  }
}
