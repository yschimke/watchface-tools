package ee.schimke.watchface.packager

import ee.schimke.watchface.packager.ReportCheck.Companion.MEMORY_FOOTPRINT
import ee.schimke.watchface.packager.ReportCheck.Companion.PUSH_VALIDATOR
import ee.schimke.watchface.packager.ReportCheck.Companion.WFF_XSD
import ee.schimke.watchface.wff.WffValidator

/**
 * The cheap report: the `wff-core` schema check and [WffMemoryEstimator], in pure Kotlin, with no
 * aapt2, SDK or Google validator, so an editor can run it on every change (in the browser, under
 * Kotlin/Wasm). Same JSON as `wff-packager validate` with `mode` `estimate`; Google's XSD and push
 * validators are `skipped`. `wff-packager validate` stays the authority before publishing.
 */
public object WffEstimate {
  public fun report(bundle: Map<String, ByteArray>): PackagerReport {
    val face = ReportChecks.inspect(bundle)
    val estimate = WffMemoryEstimator.estimate(bundle)
    val memory =
      if (!face.xmlPresent) {
        ReportChecks.skip(
          MEMORY_FOOTPRINT,
          FindingSeverity.ERROR,
          "Not run: the bundle has no watchface.xml",
        )
      } else {
        ReportChecks.check(MEMORY_FOOTPRINT, estimate.findings)
          .copy(
            title = "${ReportCheck.TITLES.getValue(MEMORY_FOOTPRINT)} (estimate)",
            memory = estimate.memory,
            contributors = estimate.contributors,
          )
      }
    val notRun = "Not run: estimate only; `wff-packager validate` runs Google's validators"
    return PackagerReport(
      face.declared?.number,
      face.document?.let { WffValidator.minimumVersion(it).number },
      listOf(
        face.schemaCheck(),
        ReportChecks.skip(WFF_XSD, FindingSeverity.WARNING, notRun),
        memory,
        ReportChecks.skip(PUSH_VALIDATOR, FindingSeverity.WARNING, notRun),
      ),
      ReportMode.ESTIMATE,
    )
  }
}
