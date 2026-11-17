package ee.schimke.watchface.packager

public enum class DiagnosticSeverity {
  ERROR,
  WARNING,
  INFO,
}

/**
 * One finding from packaging. [source] says which stage produced it (`bundle`, `manifest`, `wff`,
 * `aapt2`, `apk`, `signing`, `push-validator`, `memory-footprint`, ...).
 */
public data class PackagingDiagnostic(
  val severity: DiagnosticSeverity,
  val source: String,
  val message: String,
) {
  override fun toString(): String = "${severity.name.lowercase()}[$source]: $message"
}

/** Packaging failed; [diagnostics] holds every finding collected so far (at least one error). */
public class PackagingException(public val diagnostics: List<PackagingDiagnostic>) :
  Exception(
    diagnostics
      .filter { it.severity == DiagnosticSeverity.ERROR }
      .joinToString(separator = "\n") { it.toString() }
      .ifEmpty { "packaging failed" },
  ) {
  public constructor(
    source: String,
    message: String,
  ) : this(listOf(PackagingDiagnostic(DiagnosticSeverity.ERROR, source, message)))
}

internal fun error(source: String, message: String) =
  PackagingDiagnostic(DiagnosticSeverity.ERROR, source, message)

internal fun warning(source: String, message: String) =
  PackagingDiagnostic(DiagnosticSeverity.WARNING, source, message)

internal fun info(source: String, message: String) =
  PackagingDiagnostic(DiagnosticSeverity.INFO, source, message)

internal fun List<PackagingDiagnostic>.hasErrors(): Boolean = any {
  it.severity == DiagnosticSeverity.ERROR
}
