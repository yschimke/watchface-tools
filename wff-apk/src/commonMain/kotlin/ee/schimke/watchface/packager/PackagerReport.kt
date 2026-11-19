package ee.schimke.watchface.packager

/** Status of one [ReportCheck]. */
public enum class CheckStatus {
  PASSED,
  FAILED,
  /** Not run; the check carries one finding saying why. */
  SKIPPED,
}

public enum class FindingSeverity {
  ERROR,
  WARNING,
}

/** One finding of a check. [line], [column] and [path] are set when the source knows them. */
public data class ReportFinding(
  val severity: FindingSeverity,
  val message: String,
  val line: Int? = null,
  val column: Int? = null,
  /** wff-core node path, such as `/WatchFace/Scene/Group[2]/PartText`. */
  val path: String? = null,
  /** What to change, for an authoring tool: "downscale to the 450x450 it is drawn at". */
  val suggestion: String? = null,
)

/** The memory footprint and the limits it was checked against. */
public data class ReportMemory(
  val totalBytes: Long,
  val maxActiveBytes: Long,
  val maxAmbientBytes: Long,
  val activeLimitBytes: Long,
  val ambientLimitBytes: Long,
)

/** Where a [MemoryContributor] comes from. */
public enum class ContributorKind {
  IMAGE,
  FONT,
  OTHER,
}

/**
 * One resource's share of the memory footprint, measured by [WffMemoryEstimator] (Google's
 * evaluator does not report per resource). Images cost their decoded ARGB size.
 */
public data class MemoryContributor(
  /** `drawable/case_steel`, `font/roboto`. */
  val resource: String,
  val kind: ContributorKind,
  /** `png`, `jpeg`, `webp`, `ttf`, `otf`, `unknown`; null when [missing]. */
  val format: String?,
  val width: Int? = null,
  val height: Int? = null,
  /** Size of the file in the bundle. */
  val fileBytes: Long = 0,
  /** In memory once decoded (`width * height * 4` for an image). */
  val decodedBytes: Long = 0,
  /** Of [decodedBytes], what the active (interactive) mode draws. */
  val activeBytes: Long = 0,
  /** Of [decodedBytes], what ambient (always-on) mode draws. */
  val ambientBytes: Long = 0,
  /** The largest size the face draws it at, when the face gives one. */
  val renderedMaxWidth: Int? = null,
  val renderedMaxHeight: Int? = null,
  /** The face references it but the bundle does not ship it. */
  val missing: Boolean = false,
  val suggestion: String? = null,
) {
  /** What [PackagerReport] sorts contributors by. */
  val cost: Long
    get() = maxOf(activeBytes, ambientBytes)
}

public data class ReportCheck(
  val id: String,
  val title: String,
  val status: CheckStatus,
  val findings: List<ReportFinding> = emptyList(),
  /** Only for [MEMORY_FOOTPRINT], when the evaluator (or the estimate) ran. */
  val memory: ReportMemory? = null,
  /** Only for [MEMORY_FOOTPRINT]: every resource the face uses, most expensive first. */
  val contributors: List<MemoryContributor>? = null,
  /** Only for [PUSH_VALIDATOR], when the validator ran. */
  val passedChecks: List<String>? = null,
  /** Only for [PUSH_VALIDATOR]: whether a validation token was issued. The token is never here. */
  val tokenIssued: Boolean? = null,
) {
  public companion object {
    public const val WFF_SCHEMA: String = "wff-schema"
    public const val WFF_XSD: String = "wff-xsd"
    public const val MEMORY_FOOTPRINT: String = "memory-footprint"
    public const val PUSH_VALIDATOR: String = "push-validator"

    /** Every check, in report order, with its title. */
    public val TITLES: Map<String, String> =
      linkedMapOf(
        WFF_SCHEMA to "WFF schema (wff-core)",
        WFF_XSD to "Google WFF XSD validator",
        MEMORY_FOOTPRINT to "Memory footprint",
        PUSH_VALIDATOR to "Watch Face Push validator",
      )
  }
}

/** How a [PackagerReport] was made. */
public enum class ReportMode {
  /** `wff-packager` with Google's validators (JVM, Android SDK). */
  FULL,
  /** [WffEstimate]: wff-core and the memory estimate only, in pure Kotlin (JVM or the browser). */
  ESTIMATE,
}

/**
 * Machine-readable outcome of every check on a bundle ([toJson] is the `--report-out` file). It
 * never contains the validation token, only [ReportCheck.tokenIssued].
 */
public data class PackagerReport(
  /** The version the manifest declares, or null when it declares none (or an invalid one). */
  val wffVersion: Int?,
  /** The lowest version supporting everything the watch face uses, or null if it didn't parse. */
  val minimumWffVersion: Int?,
  /** One entry per id in [ReportCheck.TITLES], in that order. */
  val checks: List<ReportCheck>,
  val mode: ReportMode = ReportMode.FULL,
) {
  /**
   * No check failed and no finding is an error (a check skipped because the APK failed to build).
   */
  val passed: Boolean
    get() =
      checks.none { it.status == CheckStatus.FAILED } &&
        checks.none { c -> c.findings.any { it.severity == FindingSeverity.ERROR } }

  public fun check(id: String): ReportCheck? = checks.firstOrNull { it.id == id }

  public fun toJson(): String = buildString {
    append("{\"schemaVersion\":").append(SCHEMA_VERSION)
    append(",\"mode\":").appendJson(mode.name.lowercase())
    append(",\"wffVersion\":").append(wffVersion?.toString() ?: "null")
    append(",\"minimumWffVersion\":").append(minimumWffVersion?.toString() ?: "null")
    append(",\"passed\":").append(passed)
    append(",\"checks\":[")
    checks.forEachIndexed { i, c ->
      if (i > 0) append(',')
      append("{\"id\":").appendJson(c.id)
      append(",\"title\":").appendJson(c.title)
      append(",\"status\":").appendJson(c.status.name.lowercase())
      append(",\"findings\":[")
      c.findings.forEachIndexed { j, f ->
        if (j > 0) append(',')
        append("{\"severity\":").appendJson(f.severity.name.lowercase())
        append(",\"message\":").appendJson(f.message)
        f.line?.let { append(",\"line\":").append(it) }
        f.column?.let { append(",\"column\":").append(it) }
        f.path?.let { append(",\"path\":").appendJson(it) }
        f.suggestion?.let { append(",\"suggestion\":").appendJson(it) }
        append('}')
      }
      append(']')
      c.memory?.let { m ->
        append(",\"memory\":{\"totalBytes\":").append(m.totalBytes)
        append(",\"maxActiveBytes\":").append(m.maxActiveBytes)
        append(",\"maxAmbientBytes\":").append(m.maxAmbientBytes)
        append(",\"activeLimitBytes\":").append(m.activeLimitBytes)
        append(",\"ambientLimitBytes\":").append(m.ambientLimitBytes)
        append('}')
      }
      c.contributors?.let { list ->
        append(",\"contributors\":[")
        list.forEachIndexed { j, m ->
          if (j > 0) append(',')
          append("{\"resource\":").appendJson(m.resource)
          append(",\"kind\":").appendJson(m.kind.name.lowercase())
          m.format?.let { append(",\"format\":").appendJson(it) }
          m.width?.let { append(",\"width\":").append(it) }
          m.height?.let { append(",\"height\":").append(it) }
          append(",\"fileBytes\":").append(m.fileBytes)
          append(",\"decodedBytes\":").append(m.decodedBytes)
          append(",\"activeBytes\":").append(m.activeBytes)
          append(",\"ambientBytes\":").append(m.ambientBytes)
          m.renderedMaxWidth?.let { append(",\"renderedMaxWidth\":").append(it) }
          m.renderedMaxHeight?.let { append(",\"renderedMaxHeight\":").append(it) }
          append(",\"missing\":").append(m.missing)
          m.suggestion?.let { append(",\"suggestion\":").appendJson(it) }
          append('}')
        }
        append(']')
      }
      c.passedChecks?.let { list ->
        append(",\"passedChecks\":[")
        list.forEachIndexed { j, s ->
          if (j > 0) append(',')
          appendJson(s)
        }
        append(']')
      }
      c.tokenIssued?.let { append(",\"tokenIssued\":").append(it) }
      append('}')
    }
    append("]}")
  }

  public companion object {
    public const val SCHEMA_VERSION: Int = 1
  }
}

private const val HEX = "0123456789abcdef"

private fun StringBuilder.appendJson(s: String): StringBuilder {
  append('"')
  for (ch in s) {
    when (ch) {
      '"' -> append("\\\"")
      '\\' -> append("\\\\")
      '\n' -> append("\\n")
      '\r' -> append("\\r")
      '\t' -> append("\\t")
      else ->
        if (ch < ' ') {
          append("\\u00").append(HEX[ch.code shr 4]).append(HEX[ch.code and 0xf])
        } else append(ch)
    }
  }
  return append('"')
}
