package ee.schimke.watchface.packager

import ee.schimke.watchface.packager.ReportCheck.Companion.WFF_SCHEMA
import ee.schimke.watchface.wff.WffBundleLayout
import ee.schimke.watchface.wff.WffDocument
import ee.schimke.watchface.wff.WffSeverity
import ee.schimke.watchface.wff.WffValidator
import ee.schimke.watchface.wff.WffVersion
import ee.schimke.watchface.wff.WffXmlParser

/** The bundle's face and declared version, read without the manifest rewrite. */
public class BundleFace(
  public val xmlPresent: Boolean,
  public val document: WffDocument?,
  /** The version the manifest declares, or null when it declares none (or an invalid one). */
  public val declared: WffVersion?,
) {
  private val issues =
    if (document != null && declared != null) WffValidator.check(document, declared)
    else emptyList()

  /** The same diagnostics, in the same words, that `ApkPreflight` reports for the face. */
  public fun wffCoreDiagnostics(): List<PackagingDiagnostic> =
    when {
      !xmlPresent -> emptyList()
      document == null ->
        listOf(
          PackagingDiagnostic(
            DiagnosticSeverity.ERROR,
            "wff",
            "${WffBundleLayout.WATCH_FACE_PATH} is not well-formed XML",
          ),
        )
      else ->
        issues.map {
          PackagingDiagnostic(
            if (it.severity == WffSeverity.ERROR) DiagnosticSeverity.ERROR
            else DiagnosticSeverity.WARNING,
            "wff",
            "${it.path}: ${it.message} (${it.code})",
          )
        }
    }

  /** The `wff-schema` check, with node paths. */
  public fun schemaCheck(): ReportCheck =
    when {
      !xmlPresent ->
        ReportChecks.skip(
          WFF_SCHEMA,
          FindingSeverity.ERROR,
          "Not run: the bundle has no watchface.xml",
        )
      document == null ->
        ReportChecks.check(
          WFF_SCHEMA,
          listOf(
            ReportFinding(
              FindingSeverity.ERROR,
              "${WffBundleLayout.WATCH_FACE_PATH} is not well-formed XML",
            ),
          ),
        )
      declared == null -> ReportChecks.noVersionSkip(WFF_SCHEMA)
      else ->
        ReportChecks.check(
          WFF_SCHEMA,
          issues.map {
            ReportFinding(
              if (it.severity == WffSeverity.ERROR) FindingSeverity.ERROR
              else FindingSeverity.WARNING,
              "${it.message} (${it.code})",
              path = it.path,
            )
          },
        )
    }
}

/** Shared pieces of a [PackagerReport], for the JVM packager and the estimate. */
public object ReportChecks {
  /** The bundle's face and declared version, read without the manifest rewrite. */
  public fun inspect(bundle: Map<String, ByteArray>): BundleFace {
    val xml = bundle[WffBundleLayout.WATCH_FACE_PATH]?.decodeToString()
    val declared =
      bundle[WffBundleLayout.MANIFEST_PATH]
        ?.let { WffXmlParser.parseOrNull(it.decodeToString()) }
        ?.root
        ?.firstChild("application")
        ?.childElements("property")
        ?.firstOrNull { it.attr("android:name") == WffBundleLayout.WFF_VERSION_PROPERTY }
        ?.attr("android:value")
        ?.toIntOrNull()
        ?.let(WffVersion::fromNumber)
    return BundleFace(xml != null, xml?.let(WffXmlParser::parseOrNull), declared)
  }

  private fun title(id: String) = ReportCheck.TITLES.getValue(id)

  /** Passed unless a finding is an error. */
  public fun check(id: String, findings: List<ReportFinding>): ReportCheck =
    ReportCheck(
      id,
      title(id),
      if (findings.any { it.severity == FindingSeverity.ERROR }) CheckStatus.FAILED
      else CheckStatus.PASSED,
      findings,
    )

  /** Skipped, with one finding saying [why]. */
  public fun skip(id: String, severity: FindingSeverity, why: String): ReportCheck =
    ReportCheck(id, title(id), CheckStatus.SKIPPED, listOf(ReportFinding(severity, why)))

  public fun noVersionSkip(id: String): ReportCheck =
    skip(
      id,
      FindingSeverity.ERROR,
      "Not run: the manifest declares no valid Watch Face Format version " +
        "(${WffBundleLayout.WFF_VERSION_PROPERTY})",
    )
}
