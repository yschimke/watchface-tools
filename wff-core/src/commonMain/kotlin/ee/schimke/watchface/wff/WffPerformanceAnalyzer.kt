package ee.schimke.watchface.wff

/** How frequently a watch face or mode requests frame redraws. */
public enum class WffUpdateCadence {
  /** No time- or sensor-driven updates detected. */
  STATIC,
  /** Updates once per minute (standard ambient / battery-friendly interactive). */
  MINUTE,
  /** Updates once per second (e.g. ticking second hand or `[SECOND]` expression). */
  SECOND,
  /**
   * Updates multiple times per second (sweep hand, `[MILLISECOND]`, `<Gyro>`, or animated image).
   */
  CONTINUOUS,
}

public enum class WffPerformanceCode {
  /** Expression references `[MILLISECOND]`, `[SECOND_MILLISECOND]`, or `[UTC_TIMESTAMP]`. */
  HIGH_FREQUENCY_EXPRESSION,
  /** `<Gyro>` causes continuous sensor wakeups and redraws. */
  GYRO_SENSOR_USAGE,
  /** `<PartAnimatedImage>` or `<Sweep>` runs above Wear OS's 15 fps battery guideline. */
  HIGH_FPS_ANIMATION,
  /** `<SecondHand>` uses `<Sweep>` (continuous redraws) rather than `<Tick>`. */
  SWEEP_SECOND_HAND,
  /** A second-level or continuous animation is not hidden in `AMBIENT` mode. */
  AMBIENT_ANIMATION_VISIBLE,
  /** `<Scene>` has a non-black `backgroundColor`, increasing OLED power draw. */
  NON_BLACK_SCENE_BACKGROUND,
}

public data class WffPerformanceFinding(
  val severity: WffSeverity,
  val code: WffPerformanceCode,
  val path: String,
  val message: String,
  val suggestion: String? = null,
)

public data class WffPerformanceReport(
  val interactiveCadence: WffUpdateCadence,
  val ambientCadence: WffUpdateCadence,
  val estimatedMaxFps: Int,
  val gyroCount: Int,
  val animatedImageCount: Int,
  val highFrequencyExpressionCount: Int,
  val ambientAnimatedElementCount: Int,
  val findings: List<WffPerformanceFinding>,
)

/**
 * Static performance and battery-cost analyzer for a [WffDocument].
 *
 * Inspects expressions, `<SecondHand>` sweep/tick modes, `<Gyro>`, `<PartAnimatedImage>`, and
 * `AMBIENT` visibility (`<Variant mode="AMBIENT" target="alpha" value="0"/>`) to estimate redraw
 * cadence and flag Wear OS battery-drain patterns without needing an emulator or Android SDK.
 */
public object WffPerformanceAnalyzer {
  /**
   * Wear OS / Watch Face Studio battery guideline threshold for continuous animation frame rate.
   */
  public const val RECOMMENDED_MAX_FPS: Int = 15

  private val SUB_SECOND_SOURCES: Set<String> =
    setOf(
      "MILLISECOND",
      "SECOND_MILLISECOND",
      "UTC_TIMESTAMP",
      "ACCELEROMETER_IS_SUPPORTED",
      "ACCELEROMETER_X",
      "ACCELEROMETER_Y",
      "ACCELEROMETER_Z",
      "ACCELEROMETER_ANGLE_X",
      "ACCELEROMETER_ANGLE_Y",
      "ACCELEROMETER_ANGLE_Z",
      "ACCELEROMETER_ANGLE_XY",
    )

  private val SECOND_SOURCES: Set<String> =
    setOf(
      "SECOND",
      "SECOND_Z",
      "SECOND_TENS_DIGIT",
      "SECOND_UNITS_DIGIT",
    )

  private val MINUTE_SOURCES: Set<String> =
    setOf(
      "MINUTE",
      "MINUTE_Z",
      "MINUTE_TENS_DIGIT",
      "MINUTE_UNITS_DIGIT",
      "HOUR_0_11",
      "HOUR_0_11_Z",
      "HOUR_1_12",
      "HOUR_1_12_Z",
      "HOUR_0_23",
      "HOUR_0_23_Z",
      "HOUR_1_24",
      "HOUR_1_24_Z",
    )

  private val SOURCE_BRACKET_REGEX: Regex = Regex("""\[([A-Za-z0-9_.]+)]""")

  public fun analyze(doc: WffDocument): WffPerformanceReport {
    val findings = mutableListOf<WffPerformanceFinding>()
    var interactiveCadence = WffUpdateCadence.STATIC
    var ambientCadence = WffUpdateCadence.STATIC
    var estimatedMaxFps = 0
    var gyroCount = 0
    var animatedImageCount = 0
    var highFrequencyExpressionCount = 0
    var ambientAnimatedElementCount = 0

    fun bumpInteractive(cadence: WffUpdateCadence, fps: Int) {
      if (cadence.ordinal > interactiveCadence.ordinal) interactiveCadence = cadence
      if (fps > estimatedMaxFps) estimatedMaxFps = fps
    }

    fun bumpAmbient(cadence: WffUpdateCadence) {
      if (cadence.ordinal > ambientCadence.ordinal) ambientCadence = cadence
    }

    fun visit(element: WffElement, path: String, hiddenInAmbient: Boolean) {
      val nowHiddenInAmbient = hiddenInAmbient || isHiddenInAmbient(element)

      when (element.name) {
        "Scene" -> {
          val bg = element.attr("backgroundColor")?.trim()
          if (bg != null && !isBlackColor(bg)) {
            findings +=
              WffPerformanceFinding(
                severity = WffSeverity.WARNING,
                code = WffPerformanceCode.NON_BLACK_SCENE_BACKGROUND,
                path = path,
                message =
                  "Scene backgroundColor is '$bg'; non-black backgrounds increase OLED power draw",
                suggestion =
                  "Use '#ff000000' for the Scene background and hide decorative layers in AMBIENT mode",
              )
          }
        }
        "AnalogClock",
        "DigitalClock" -> {
          bumpInteractive(WffUpdateCadence.MINUTE, 0)
          if (!nowHiddenInAmbient) bumpAmbient(WffUpdateCadence.MINUTE)
        }
        "SecondHand" -> {
          val sweep = element.firstChild("Sweep")
          if (sweep != null) {
            val freq = sweep.attr("frequency")?.toIntOrNull() ?: 15
            bumpInteractive(WffUpdateCadence.CONTINUOUS, freq)
            findings +=
              WffPerformanceFinding(
                severity = WffSeverity.WARNING,
                code = WffPerformanceCode.SWEEP_SECOND_HAND,
                path = path,
                message = "SecondHand uses <Sweep frequency=\"$freq\"/> which redraws continuously",
                suggestion =
                  "Prefer <Tick/> for lower battery consumption, or keep frequency <= $RECOMMENDED_MAX_FPS",
              )
            if (freq > RECOMMENDED_MAX_FPS) {
              findings +=
                WffPerformanceFinding(
                  severity = WffSeverity.WARNING,
                  code = WffPerformanceCode.HIGH_FPS_ANIMATION,
                  path = "$path/Sweep",
                  message =
                    "Sweep frequency is $freq fps (exceeds Wear OS guideline of $RECOMMENDED_MAX_FPS fps)",
                  suggestion = "Reduce frequency to <= $RECOMMENDED_MAX_FPS fps",
                )
            }
          } else {
            bumpInteractive(WffUpdateCadence.SECOND, 1)
          }
          if (!nowHiddenInAmbient) {
            ambientAnimatedElementCount++
            bumpAmbient(if (sweep != null) WffUpdateCadence.CONTINUOUS else WffUpdateCadence.SECOND)
            findings +=
              WffPerformanceFinding(
                severity = WffSeverity.WARNING,
                code = WffPerformanceCode.AMBIENT_ANIMATION_VISIBLE,
                path = path,
                message = "SecondHand is not hidden in AMBIENT mode",
                suggestion =
                  "Add <Variant mode=\"AMBIENT\" target=\"alpha\" value=\"0\"/> to hide the second hand in AOD",
              )
          }
        }
        "Gyro" -> {
          gyroCount++
          bumpInteractive(WffUpdateCadence.CONTINUOUS, 30)
          findings +=
            WffPerformanceFinding(
              severity = WffSeverity.WARNING,
              code = WffPerformanceCode.GYRO_SENSOR_USAGE,
              path = path,
              message = "<Gyro> triggers continuous sensor-driven redraws whenever the wrist moves",
              suggestion = "Minimize <Gyro> layers or restrict to small non-overlapping elements",
            )
          if (!nowHiddenInAmbient) {
            ambientAnimatedElementCount++
            bumpAmbient(WffUpdateCadence.CONTINUOUS)
            findings +=
              WffPerformanceFinding(
                severity = WffSeverity.WARNING,
                code = WffPerformanceCode.AMBIENT_ANIMATION_VISIBLE,
                path = path,
                message = "<Gyro> is inside an element still visible in AMBIENT mode",
                suggestion =
                  "Hide the parent element in AMBIENT with <Variant mode=\"AMBIENT\" target=\"alpha\" value=\"0\"/>",
              )
          }
        }
        "PartAnimatedImage" -> {
          animatedImageCount++
          val fps =
            element.attr("frameRate")?.toIntOrNull()
              ?: element.firstChild("Image")?.attr("frameRate")?.toIntOrNull()
              ?: element.firstChild("SequenceImages")?.attr("frameRate")?.toIntOrNull()
              ?: 15
          bumpInteractive(WffUpdateCadence.CONTINUOUS, fps)
          if (fps > RECOMMENDED_MAX_FPS) {
            findings +=
              WffPerformanceFinding(
                severity = WffSeverity.WARNING,
                code = WffPerformanceCode.HIGH_FPS_ANIMATION,
                path = path,
                message =
                  "PartAnimatedImage frameRate is $fps fps (exceeds Wear OS guideline of $RECOMMENDED_MAX_FPS fps)",
                suggestion = "Lower frameRate to <= $RECOMMENDED_MAX_FPS fps",
              )
          }
          if (!nowHiddenInAmbient) {
            ambientAnimatedElementCount++
            bumpAmbient(WffUpdateCadence.CONTINUOUS)
            findings +=
              WffPerformanceFinding(
                severity = WffSeverity.WARNING,
                code = WffPerformanceCode.AMBIENT_ANIMATION_VISIBLE,
                path = path,
                message = "PartAnimatedImage is not hidden in AMBIENT mode",
                suggestion =
                  "Add <Variant mode=\"AMBIENT\" target=\"alpha\" value=\"0\"/> to stop animation in AOD",
              )
          }
        }
      }

      // Inspect attribute values and inline text for data source references.
      val sources = collectSources(element)
      if (sources.isNotEmpty()) {
        val subSecond = sources.filter { it in SUB_SECOND_SOURCES }
        val second = sources.filter { it in SECOND_SOURCES }
        val minute = sources.filter { it in MINUTE_SOURCES }

        if (subSecond.isNotEmpty()) {
          highFrequencyExpressionCount++
          bumpInteractive(WffUpdateCadence.CONTINUOUS, 30)
          findings +=
            WffPerformanceFinding(
              severity = WffSeverity.WARNING,
              code = WffPerformanceCode.HIGH_FREQUENCY_EXPRESSION,
              path = path,
              message =
                "High-frequency data source(s) ${subSecond.joinToString { "[$it]" }} force continuous redraws",
              suggestion =
                "Replace sub-second/sensor expressions with minute/second sources where possible",
            )
          if (!nowHiddenInAmbient) {
            ambientAnimatedElementCount++
            bumpAmbient(WffUpdateCadence.CONTINUOUS)
            findings +=
              WffPerformanceFinding(
                severity = WffSeverity.WARNING,
                code = WffPerformanceCode.AMBIENT_ANIMATION_VISIBLE,
                path = path,
                message =
                  "High-frequency source(s) ${subSecond.joinToString { "[$it]" }} remain visible in AMBIENT mode",
                suggestion =
                  "Hide this element in AMBIENT mode with <Variant mode=\"AMBIENT\" target=\"alpha\" value=\"0\"/>",
              )
          }
        } else if (second.isNotEmpty()) {
          bumpInteractive(WffUpdateCadence.SECOND, 1)
          if (!nowHiddenInAmbient) {
            ambientAnimatedElementCount++
            bumpAmbient(WffUpdateCadence.SECOND)
            findings +=
              WffPerformanceFinding(
                severity = WffSeverity.WARNING,
                code = WffPerformanceCode.AMBIENT_ANIMATION_VISIBLE,
                path = path,
                message =
                  "Second-level source(s) ${second.joinToString { "[$it]" }} remain visible in AMBIENT mode",
                suggestion =
                  "Hide second-updating elements in AMBIENT mode so AOD stays at 1 update/minute",
              )
          }
        } else if (minute.isNotEmpty()) {
          bumpInteractive(WffUpdateCadence.MINUTE, 0)
          if (!nowHiddenInAmbient) bumpAmbient(WffUpdateCadence.MINUTE)
        }
      }

      // Recurse into child elements with 1-based indexed paths matching WffValidator.
      val counts = HashMap<String, Int>()
      for (child in element.childElements) {
        counts[child.name] = (counts[child.name] ?: 0) + 1
      }
      val seen = HashMap<String, Int>()
      for (child in element.childElements) {
        val index = (seen[child.name] ?: 0) + 1
        seen[child.name] = index
        val childPath =
          if ((counts[child.name] ?: 0) > 1) "$path/${child.name}[$index]"
          else "$path/${child.name}"
        visit(child, childPath, nowHiddenInAmbient)
      }
    }

    visit(doc.root, "/${doc.root.name}", hiddenInAmbient = false)

    return WffPerformanceReport(
      interactiveCadence = interactiveCadence,
      ambientCadence = ambientCadence,
      estimatedMaxFps = estimatedMaxFps,
      gyroCount = gyroCount,
      animatedImageCount = animatedImageCount,
      highFrequencyExpressionCount = highFrequencyExpressionCount,
      ambientAnimatedElementCount = ambientAnimatedElementCount,
      findings = findings,
    )
  }

  private fun isHiddenInAmbient(element: WffElement): Boolean =
    element.childElements("Variant").any { v ->
      v.attr("mode") == "AMBIENT" &&
        v.attr("target") == "alpha" &&
        (v.attr("value")?.toDoubleOrNull()?.let { it <= 0.0 } == true)
    }

  private fun isBlackColor(raw: String): Boolean {
    val hex = raw.removePrefix("#").lowercase()
    return hex == "000000" || hex == "ff000000" || hex == "00000000"
  }

  private fun collectSources(element: WffElement): Set<String> {
    val out = LinkedHashSet<String>()
    for (attr in element.attributes) {
      for (match in SOURCE_BRACKET_REGEX.findAll(attr.value)) {
        out += match.groupValues[1]
      }
    }
    for (child in element.children) {
      val text =
        when (child) {
          is WffText -> child.text
          is WffCData -> child.text
          else -> null
        } ?: continue
      for (match in SOURCE_BRACKET_REGEX.findAll(text)) {
        out += match.groupValues[1]
      }
    }
    return out
  }
}
