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
)

/** The memory footprint and the limits it was checked against. */
public data class ReportMemory(
  val totalBytes: Long,
  val maxActiveBytes: Long,
  val maxAmbientBytes: Long,
  val activeLimitBytes: Long,
  val ambientLimitBytes: Long,
)

public data class ReportCheck(
  val id: String,
  val title: String,
  val status: CheckStatus,
  val findings: List<ReportFinding> = emptyList(),
  /** Only for [MEMORY_FOOTPRINT], when the evaluator ran. */
  val memory: ReportMemory? = null,
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

/**
 * Machine-readable outcome of every check `wff-packager` runs on a bundle ([toJson] is the
 * `--report-out` file). It never contains the validation token, only [ReportCheck.tokenIssued].
 */
public data class PackagerReport(
  /** The version the manifest declares, or null when it declares none (or an invalid one). */
  val wffVersion: Int?,
  /** The lowest version supporting everything the watch face uses, or null if it didn't parse. */
  val minimumWffVersion: Int?,
  /** One entry per id in [ReportCheck.TITLES], in that order. */
  val checks: List<ReportCheck>,
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

private fun StringBuilder.appendJson(s: String): StringBuilder {
  append('"')
  for (ch in s) {
    when (ch) {
      '"' -> append("\\\"")
      '\\' -> append("\\\\")
      '\n' -> append("\\n")
      '\r' -> append("\\r")
      '\t' -> append("\\t")
      else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
    }
  }
  return append('"')
}

private val LINE_COLUMN = Regex("""^\[Line (\d+):Column (\d+)]:\s*""")

/** Google's XSD validator reports `[Line n:Column m]: cvc-...`; split that into fields. */
internal fun xsdFinding(message: String): ReportFinding {
  val m = LINE_COLUMN.find(message) ?: return ReportFinding(FindingSeverity.ERROR, message)
  return ReportFinding(
    FindingSeverity.ERROR,
    message.substring(m.range.last + 1),
    line = m.groupValues[1].toInt(),
    column = m.groupValues[2].toInt(),
  )
}

internal fun PackagingDiagnostic.toFinding(): ReportFinding =
  ReportFinding(
    if (severity == DiagnosticSeverity.ERROR) FindingSeverity.ERROR else FindingSeverity.WARNING,
    message,
  )
