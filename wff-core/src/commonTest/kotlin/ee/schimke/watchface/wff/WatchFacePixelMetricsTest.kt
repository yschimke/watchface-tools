package ee.schimke.watchface.wff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchFacePixelMetricsTest {
  @Test
  fun ambientOprRespectsCircularMaskAndWoP7Threshold() {
    val w = 10
    val h = 10
    val black = IntArray(w * h) { 0xFF000000.toInt() }
    val blackReport = WatchFacePixelMetrics.measureAmbient(black, w, h, circular = true)
    assertEquals(0, blackReport.litPixels)
    assertTrue(blackReport.totalMaskPixels in 70..85)
    assertEquals(0.0, blackReport.onPixelRatio)
    assertEquals(0.0, blackReport.averageLuminance)
    assertFalse(blackReport.exceedsWoP7Limit)

    // White pixels in the corners outside the watch circle must not count toward circular OPR.
    val cornersLit = black.copyOf()
    cornersLit[0] = 0xFFFFFFFF.toInt()
    cornersLit[w - 1] = 0xFFFFFFFF.toInt()
    cornersLit[(h - 1) * w] = 0xFFFFFFFF.toInt()
    cornersLit[w * h - 1] = 0xFFFFFFFF.toInt()
    val cornerReport = WatchFacePixelMetrics.measureAmbient(cornersLit, w, h, circular = true)
    assertEquals(0, cornerReport.litPixels)
    assertFalse(cornerReport.exceedsWoP7Limit)

    // Lighting the center 4x4 block (16 pixels out of ~80 in the circle) exceeds 15%.
    val bright = black.copyOf()
    for (y in 3..6) {
      for (x in 3..6) {
        bright[y * w + x] = 0xFFFFFFFF.toInt()
      }
    }
    val brightReport = WatchFacePixelMetrics.measureAmbient(bright, w, h, circular = true)
    assertEquals(16, brightReport.litPixels)
    assertTrue(brightReport.onPixelRatio > 0.15)
    assertTrue(brightReport.exceedsWoP7Limit)
    assertTrue(brightReport.averageLuminance > 0.15)
  }

  @Test
  fun burnInCountsOnlyPixelsLitAcrossAllFrames() {
    val w = 4
    val h = 4
    val frame1 = IntArray(w * h) { 0xFF000000.toInt() }
    val frame2 = IntArray(w * h) { 0xFF000000.toInt() }
    // (1,1) lit in both frames; (2,1) only in frame1; (1,2) only in frame2.
    frame1[1 * w + 1] = 0xFFFFFFFF.toInt()
    frame1[1 * w + 2] = 0xFFFFFFFF.toInt()
    frame2[1 * w + 1] = 0xFFFFFFFF.toInt()
    frame2[2 * w + 1] = 0xFFFFFFFF.toInt()

    val report = WatchFacePixelMetrics.measureBurnIn(listOf(frame1, frame2), w, h, circular = false)
    assertEquals(2, report.frameCount)
    assertEquals(16, report.totalMaskPixels)
    assertEquals(1, report.alwaysLitPixels)
    assertEquals(1.0 / 16.0, report.alwaysLitRatio)
    assertEquals(2.0 / 16.0, report.maxOnPixelRatio)
    assertEquals(2.0 / 16.0, report.averageOnPixelRatio)
  }

  @Test
  fun frameDiffComputesDirtyPixelsAndTightBoundingBox() {
    val w = 10
    val h = 10
    val prev = IntArray(w * h) { 0xFF000000.toInt() }
    val next = prev.copyOf()
    next[2 * w + 3] = 0xFFFF0000.toInt()
    next[5 * w + 6] = 0xFF00FF00.toInt()

    val identical = WatchFacePixelMetrics.measureFrameDiff(prev, prev, w, h, circular = false)
    assertEquals(0, identical.dirtyPixels)
    assertEquals(-1, identical.dirtyLeft)
    assertEquals(0.0, identical.dirtyBoundsRatio)

    val diff = WatchFacePixelMetrics.measureFrameDiff(prev, next, w, h, circular = false)
    assertEquals(2, diff.dirtyPixels)
    assertEquals(0.02, diff.dirtyRatio)
    assertEquals(3, diff.dirtyLeft)
    assertEquals(2, diff.dirtyTop)
    assertEquals(6, diff.dirtyRight)
    assertEquals(5, diff.dirtyBottom)
    // 4x4 bounding box = 16 / 100
    assertEquals(0.16, diff.dirtyBoundsRatio)
  }
}
