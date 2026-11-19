package ee.schimke.watchface.packager

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
