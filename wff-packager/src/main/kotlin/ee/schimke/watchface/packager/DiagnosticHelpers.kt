package ee.schimke.watchface.packager

internal fun error(source: String, message: String) =
  PackagingDiagnostic(DiagnosticSeverity.ERROR, source, message)

internal fun warning(source: String, message: String) =
  PackagingDiagnostic(DiagnosticSeverity.WARNING, source, message)

internal fun info(source: String, message: String) =
  PackagingDiagnostic(DiagnosticSeverity.INFO, source, message)

internal fun List<PackagingDiagnostic>.hasErrors(): Boolean = any {
  it.severity == DiagnosticSeverity.ERROR
}
