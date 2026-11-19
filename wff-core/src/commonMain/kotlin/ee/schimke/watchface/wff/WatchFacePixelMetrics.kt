package ee.schimke.watchface.wff

/**
 * Pixel-level summary of a single ambient (Always-On Display) watch-face frame.
 *
 * Wear OS app quality guideline `WO-P7` requires ambient mode to illuminate no more than 15% of
 * screen pixels ([MAX_WO_P7_ON_PIXEL_RATIO]).
 */
public data class AmbientPixelReport(
  /** Number of pixels inside the watch mask with non-zero RGB and non-zero alpha. */
  val litPixels: Int,
  /** Total number of pixels inside the watch mask (circular or rectangular). */
  val totalMaskPixels: Int,
  /** Fraction of mask pixels illuminated (`litPixels / totalMaskPixels`, in `[0.0, 1.0]`). */
  val onPixelRatio: Double,
  /**
   * Mean alpha-premultiplied RGB luminance across the watch mask (`[0.0, 1.0]`), serving as a
   * hardware-independent OLED display power proxy.
   */
  val averageLuminance: Double,
) {
  /** True when [onPixelRatio] exceeds Google Play's `WO-P7` 15% ambient limit. */
  val exceedsWoP7Limit: Boolean
    get() = onPixelRatio > MAX_WO_P7_ON_PIXEL_RATIO

  public companion object {
    public const val MAX_WO_P7_ON_PIXEL_RATIO: Double = 0.15
  }
}

/**
 * Multi-timestamp ambient burn-in summary across a sequence of ambient frames (for example, sampled
 * across a 24-hour clock sweep).
 */
public data class BurnInReport(
  val frameCount: Int,
  val totalMaskPixels: Int,
  /** Pixels illuminated in every sampled ambient frame. */
  val alwaysLitPixels: Int,
  /** Fraction of mask pixels illuminated in every sampled ambient frame (`[0.0, 1.0]`). */
  val alwaysLitRatio: Double,
  /** Peak [AmbientPixelReport.onPixelRatio] observed across the sampled frames. */
  val maxOnPixelRatio: Double,
  /** Mean [AmbientPixelReport.onPixelRatio] across the sampled frames. */
  val averageOnPixelRatio: Double,
) {
  val exceedsWoP7Limit: Boolean
    get() = maxOnPixelRatio > AmbientPixelReport.MAX_WO_P7_ON_PIXEL_RATIO
}

/**
 * Pixel-diff summary between two consecutive rendered watch-face frames, used to quantify animation
 * churn and dirty bounding-box area.
 */
public data class FrameDiffReport(
  val dirtyPixels: Int,
  val totalMaskPixels: Int,
  /** Fraction of mask pixels whose ARGB value changed (`[0.0, 1.0]`). */
  val dirtyRatio: Double,
  /** Tight bounding box of changed pixels (`-1` when [dirtyPixels] == 0). */
  val dirtyLeft: Int,
  val dirtyTop: Int,
  val dirtyRight: Int,
  val dirtyBottom: Int,
  /** Area of the dirty bounding box divided by [totalMaskPixels] (`[0.0, 1.0]`). */
  val dirtyBoundsRatio: Double,
)

/**
 * Pure-Kotlin ARGB pixel metrics for watch faces (ambient OPR / `WO-P7`, OLED luminance proxy,
 * multi-frame burn-in, and frame-to-frame dirty-pixel churn).
 *
 * Operates on packed `0xAARRGGBB` [IntArray] buffers with zero platform dependencies so the same
 * math runs identically in the browser (Kotlin/Wasm), JVM servers, and Robolectric tests.
 */
public object WatchFacePixelMetrics {
  /**
   * Measures ambient On-Pixel Ratio (OPR) and OLED luminance on a single `width * height` ARGB
   * buffer. When [circular] is true, only pixels inside the inscribed watch circle are counted.
   */
  public fun measureAmbient(
    pixels: IntArray,
    width: Int,
    height: Int,
    circular: Boolean = true,
  ): AmbientPixelReport {
    requireDimensions(pixels, width, height)
    var lit = 0
    var maskTotal = 0
    var luminanceSum = 0.0
    val cx = width / 2.0
    val cy = height / 2.0
    val radius = minOf(width, height) / 2.0
    val radiusSq = radius * radius

    for (y in 0 until height) {
      val dy = y + 0.5 - cy
      val dySq = dy * dy
      val rowOffset = y * width
      for (x in 0 until width) {
        if (circular) {
          val dx = x + 0.5 - cx
          if (dx * dx + dySq > radiusSq) continue
        }
        maskTotal++
        val argb = pixels[rowOffset + x]
        val a = (argb ushr 24) and 0xFF
        if (a == 0) continue
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        if (r != 0 || g != 0 || b != 0) {
          lit++
          val alphaFactor = a / 255.0
          luminanceSum += ((r + g + b) / (3.0 * 255.0)) * alphaFactor
        }
      }
    }

    val opr = if (maskTotal == 0) 0.0 else lit.toDouble() / maskTotal.toDouble()
    val avgLum = if (maskTotal == 0) 0.0 else luminanceSum / maskTotal.toDouble()
    return AmbientPixelReport(
      litPixels = lit,
      totalMaskPixels = maskTotal,
      onPixelRatio = opr,
      averageLuminance = avgLum,
    )
  }

  /**
   * Measures burn-in risk across multiple ambient frames by counting pixels illuminated in every
   * frame, alongside peak and average OPR.
   */
  public fun measureBurnIn(
    frames: List<IntArray>,
    width: Int,
    height: Int,
    circular: Boolean = true,
  ): BurnInReport {
    require(frames.isNotEmpty()) { "frames must not be empty" }
    for (frame in frames) requireDimensions(frame, width, height)

    val pixelCount = width * height
    val alwaysLit = BooleanArray(pixelCount) { true }
    var maxOpr = 0.0
    var sumOpr = 0.0
    var maskTotal = 0

    val cx = width / 2.0
    val cy = height / 2.0
    val radius = minOf(width, height) / 2.0
    val radiusSq = radius * radius

    for (frame in frames) {
      var lit = 0
      var frameMaskTotal = 0
      for (y in 0 until height) {
        val dy = y + 0.5 - cy
        val dySq = dy * dy
        val rowOffset = y * width
        for (x in 0 until width) {
          val idx = rowOffset + x
          if (circular) {
            val dx = x + 0.5 - cx
            if (dx * dx + dySq > radiusSq) {
              alwaysLit[idx] = false
              continue
            }
          }
          frameMaskTotal++
          val argb = frame[idx]
          val a = (argb ushr 24) and 0xFF
          val isLit = a != 0 && (argb and 0x00FFFFFF) != 0
          if (isLit) {
            lit++
          } else {
            alwaysLit[idx] = false
          }
        }
      }
      maskTotal = frameMaskTotal
      val opr = if (frameMaskTotal == 0) 0.0 else lit.toDouble() / frameMaskTotal.toDouble()
      if (opr > maxOpr) maxOpr = opr
      sumOpr += opr
    }

    var alwaysLitCount = 0
    for (flag in alwaysLit) {
      if (flag) alwaysLitCount++
    }
    val alwaysLitRatio =
      if (maskTotal == 0) 0.0 else alwaysLitCount.toDouble() / maskTotal.toDouble()
    return BurnInReport(
      frameCount = frames.size,
      totalMaskPixels = maskTotal,
      alwaysLitPixels = alwaysLitCount,
      alwaysLitRatio = alwaysLitRatio,
      maxOnPixelRatio = maxOpr,
      averageOnPixelRatio = sumOpr / frames.size.toDouble(),
    )
  }

  /**
   * Measures pixel churn and dirty bounding-box area between two consecutive `width * height` ARGB
   * frames.
   */
  public fun measureFrameDiff(
    previous: IntArray,
    current: IntArray,
    width: Int,
    height: Int,
    circular: Boolean = true,
  ): FrameDiffReport {
    requireDimensions(previous, width, height)
    requireDimensions(current, width, height)

    var dirty = 0
    var maskTotal = 0
    var minX = Int.MAX_VALUE
    var minY = Int.MAX_VALUE
    var maxX = -1
    var maxY = -1

    val cx = width / 2.0
    val cy = height / 2.0
    val radius = minOf(width, height) / 2.0
    val radiusSq = radius * radius

    for (y in 0 until height) {
      val dy = y + 0.5 - cy
      val dySq = dy * dy
      val rowOffset = y * width
      for (x in 0 until width) {
        if (circular) {
          val dx = x + 0.5 - cx
          if (dx * dx + dySq > radiusSq) continue
        }
        maskTotal++
        val idx = rowOffset + x
        if (previous[idx] != current[idx]) {
          dirty++
          if (x < minX) minX = x
          if (y < minY) minY = y
          if (x > maxX) maxX = x
          if (y > maxY) maxY = y
        }
      }
    }

    if (dirty == 0) {
      return FrameDiffReport(
        dirtyPixels = 0,
        totalMaskPixels = maskTotal,
        dirtyRatio = 0.0,
        dirtyLeft = -1,
        dirtyTop = -1,
        dirtyRight = -1,
        dirtyBottom = -1,
        dirtyBoundsRatio = 0.0,
      )
    }

    val dirtyRatio = if (maskTotal == 0) 0.0 else dirty.toDouble() / maskTotal.toDouble()
    val boundsArea = (maxX - minX + 1).toDouble() * (maxY - minY + 1).toDouble()
    val boundsRatio = if (maskTotal == 0) 0.0 else minOf(1.0, boundsArea / maskTotal.toDouble())
    return FrameDiffReport(
      dirtyPixels = dirty,
      totalMaskPixels = maskTotal,
      dirtyRatio = dirtyRatio,
      dirtyLeft = minX,
      dirtyTop = minY,
      dirtyRight = maxX,
      dirtyBottom = maxY,
      dirtyBoundsRatio = boundsRatio,
    )
  }

  private fun requireDimensions(pixels: IntArray, width: Int, height: Int) {
    require(width > 0 && height > 0) { "width and height must be positive (got ${width}x$height)" }
    require(pixels.size == width * height) {
      "pixels.size (${pixels.size}) must equal width * height (${width * height})"
    }
  }
}
