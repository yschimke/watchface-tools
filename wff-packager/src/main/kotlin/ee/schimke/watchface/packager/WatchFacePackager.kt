package ee.schimke.watchface.packager

import ee.schimke.watchface.apk.ApkPreflight
import ee.schimke.watchface.apk.ApkPreflightResult
import ee.schimke.watchface.packager.ReportCheck.Companion.MEMORY_FOOTPRINT
import ee.schimke.watchface.packager.ReportCheck.Companion.PUSH_VALIDATOR
import ee.schimke.watchface.packager.ReportCheck.Companion.WFF_SCHEMA
import ee.schimke.watchface.packager.ReportCheck.Companion.WFF_XSD
import ee.schimke.watchface.packager.ReportChecks.check
import ee.schimke.watchface.packager.ReportChecks.inspect
import ee.schimke.watchface.packager.ReportChecks.noVersionSkip
import ee.schimke.watchface.packager.ReportChecks.skip
import ee.schimke.watchface.wff.WffBundleLayout
import ee.schimke.watchface.wff.WffValidator
import ee.schimke.watchface.wff.WffVersion
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

/** What [WatchFacePackager.packWithReport] produced: the APK when every check passed, and why. */
public class PackOutcome(
  /** Null when packaging failed; [diagnostics] then holds at least one error. */
  public val packaged: PackagedWatchFace?,
  public val report: PackagerReport,
  public val diagnostics: List<PackagingDiagnostic>,
)

/**
 * Turns a WFF source bundle (`WffBundleLayout.sourceFiles()`: manifest, `res/...`) into a signed,
 * zipaligned, validated APK.
 *
 * Pipeline: rewrite manifest (package, versionCode) -> rule checks -> `aapt2 compile/link` ->
 * `zipalign` -> apksig v2/v3 -> Google's validators. Errors from any stage throw
 * [PackagingException]; warnings are returned in [PackagedWatchFace.diagnostics].
 */
public class WatchFacePackager(
  private val sdk: AndroidSdk? = null,
  private val validators: Validators = Validators.google(),
) {
  /** Packages [bundle], stopping at the first failing stage. */
  public fun pack(bundle: Map<String, ByteArray>, options: PackagerOptions): PackagedWatchFace {
    val outcome = run(bundle, options, keepGoing = false)
    return outcome.packaged ?: throw PackagingException(outcome.diagnostics)
  }

  /** [pack], returning the [PackagerReport] as well, and never throwing [PackagingException]. */
  public fun packWithReport(bundle: Map<String, ByteArray>, options: PackagerOptions): PackOutcome =
    run(bundle, options, keepGoing = false)

  /**
   * Runs every check without stopping at the first failure: the APK is still built and validated
   * when the face fails the `wff-core` check, and the push validator still runs when the memory
   * evaluator fails. Use a debug key ([SigningConfig.GeneratedDebugKey], the default): the APK is
   * thrown away.
   */
  public fun validate(bundle: Map<String, ByteArray>, options: PackagerOptions): PackagerReport =
    run(bundle, options, keepGoing = true).report

  private fun run(
    bundle: Map<String, ByteArray>,
    options: PackagerOptions,
    keepGoing: Boolean,
  ): PackOutcome {
    val diagnostics = mutableListOf<PackagingDiagnostic>()
    val checks = mutableMapOf<String, ReportCheck>()
    /** Errors that stopped the APK from being built; null while nothing has. */
    var buildFailure: List<PackagingDiagnostic>? = null
    val face = inspect(bundle)
    val declared = face.declared
    val estimate = if (face.document != null) WffMemoryEstimator.estimate(bundle) else null

    fun skipped(id: String): ReportCheck =
      when {
        buildFailure != null ->
          skip(
            id,
            FindingSeverity.ERROR,
            "Not run: the APK could not be built: " + buildFailure!!.joinToString("; "),
          )
        else ->
          skip(id, FindingSeverity.WARNING, "Not run: packaging stopped at an earlier failure")
      }

    fun outcome(packaged: PackagedWatchFace?): PackOutcome {
      val report =
        PackagerReport(
          declared?.number,
          face.document?.let { WffValidator.minimumVersion(it).number },
          ReportCheck.TITLES.keys
            .map { checks[it] ?: skipped(it) }
            .map { withContributors(it, estimate) },
        )
      return PackOutcome(packaged, report, diagnostics.toList())
    }

    // 1. The face against its declared version (wff-core), reported with node paths.
    val wffDiagnostics = face.wffCoreDiagnostics()
    checks[WFF_SCHEMA] = face.schemaCheck()

    // 2. Options, manifest rewrite and the rule checks, shared with the pure-Kotlin APK builder.
    val preflight: ApkPreflightResult? =
      try {
        ApkPreflight.check(
            bundle,
            options.target,
            options.versionCode,
            options.versionName,
            options.previousVersionCode,
            checkWatchFace = false,
          )
          .also { diagnostics += it.diagnostics }
      } catch (e: PackagingException) {
        diagnostics += e.diagnostics
        buildFailure = e.diagnostics.filter { it.severity == DiagnosticSeverity.ERROR }
        null
      }
    diagnostics += wffDiagnostics
    if (!keepGoing && diagnostics.hasErrors()) {
      return outcome(null)
    }

    // 3. Build.
    val work = Files.createTempDirectory("wff-packager").toFile()
    try {
      var signed: File? = null
      var certificate: String? = null
      if (preflight != null) {
        val start = diagnostics.size
        try {
          val tools = sdk ?: AndroidSdk.require()
          val signingKey = loadSigningKey(options.signing)
          checkPinnedCertificate(signingKey, options.expectedCertificateSha256)
          if (signingKey.debug) {
            diagnostics +=
              warning(
                "signing",
                "Signed with the generated per-installation debug key; pass a keystore for release",
              )
          }
          val src = File(work, "bundle").also { it.mkdirs() }
          writeBundle(preflight.files, src)
          val linked = File(work, "linked.apk")
          linkResourceApk(tools, src, linked)
          val aligned = File(work, "aligned.apk")
          zipalign(tools, linked, aligned)
          val apk = File(work, "signed.apk")
          signApk(aligned, apk, signingKey, preflight.minSdk)
          val entries = ZipFile(apk).use { z -> z.entries().toList().map { it.name } }
          diagnostics += PushRules.checkApkEntries(entries)
          certificate = certificateSha256(signingKey.chain.first())
          signed = apk
        } catch (e: PackagingException) {
          diagnostics += e.diagnostics
        }
        val errors =
          diagnostics.subList(start, diagnostics.size).filter {
            it.severity == DiagnosticSeverity.ERROR
          }
        if (errors.isNotEmpty()) {
          buildFailure = errors
          signed = null
        }
      }
      if (!keepGoing && diagnostics.hasErrors()) {
        return outcome(null)
      }

      // 4. Official validators.
      var footprint: MemoryFootprintReport? = null
      if (signed != null && declared != null) {
        val memory = runMemory(signed, declared)
        checks[MEMORY_FOOTPRINT] = memory.check
        footprint = memory.footprint
        diagnostics += memory.diagnostics
        // The evaluator throwing (a missing asset) stops packaging; a limit failure does not.
        if (!keepGoing && memory.threw) {
          return outcome(null)
        }
      }
      // Google's XSD validator runs for both targets: it is the only source of the actual cvc
      // message (the push validator only says the face "does not match the specification").
      // It reads the source XML, so `validate` runs it even when the APK did not build.
      val xml = bundle[WffBundleLayout.WATCH_FACE_PATH]?.decodeToString()
      if (xml != null && declared != null && (signed != null || keepGoing)) {
        val found = validators.format.validate(xml, declared)
        diagnostics += found.map { error("wff-validator", it) }
        checks[WFF_XSD] = check(WFF_XSD, found.map(::xsdFinding))
      } else if (xml == null) {
        checks[WFF_XSD] =
          skip(WFF_XSD, FindingSeverity.ERROR, "Not run: the bundle has no watchface.xml")
      } else if (declared == null) {
        checks[WFF_XSD] = noVersionSkip(WFF_XSD)
      }
      var token: String? = null
      val target = options.target
      if (target !is PackageTarget.WatchFacePush) {
        checks[PUSH_VALIDATOR] =
          skip(
            PUSH_VALIDATOR,
            FindingSeverity.WARNING,
            "Not run: a sideload build; the Watch Face Push validator only runs for a push build " +
              "(--push-marketplace)",
          )
      } else if (signed != null) {
        val result =
          try {
            validators.push.validate(signed, target.marketplacePackage)
          } catch (e: Exception) {
            PushValidation(null, listOf(error("push-validator", "Validator crashed: $e")))
          }
        val failures = result.failures.toMutableList()
        token = result.validationToken
        if (!failures.hasErrors() && token.isNullOrEmpty()) {
          failures += error("push-validator", "Validator passed but returned no token")
        }
        diagnostics += failures
        val issued = !failures.hasErrors() && !token.isNullOrEmpty()
        checks[PUSH_VALIDATOR] =
          check(PUSH_VALIDATOR, failures.map { it.toFinding() })
            .copy(passedChecks = result.passedChecks, tokenIssued = issued)
      }
      if (diagnostics.hasErrors() || signed == null || preflight == null) {
        return outcome(null)
      }

      return outcome(
        PackagedWatchFace(
          apk = signed.readBytes(),
          packageName = preflight.packageName,
          versionCode = options.versionCode,
          wffVersion = preflight.wffVersion,
          memoryFootprint = footprint,
          validationToken = token?.takeIf(String::isNotEmpty),
          diagnostics = diagnostics.toList(),
          signerCertificateSha256 = certificate!!,
        ),
      )
    } finally {
      work.deleteRecursively()
    }
  }

  /**
   * Google's evaluator gives totals only, so the per-resource breakdown comes from
   * [WffMemoryEstimator], and its suggestion goes on a finding that names a resource it measured.
   */
  private fun withContributors(c: ReportCheck, estimate: MemoryEstimate?): ReportCheck {
    if (c.id != MEMORY_FOOTPRINT || estimate == null) return c
    val byName = estimate.contributors.associateBy { it.resource.substringAfter('/') }
    val findings =
      c.findings.map { f ->
        val name = MISSING_ASSET.find(f.message)?.groupValues?.get(1)
        val hint = name?.let { byName[it]?.suggestion }
        if (hint != null && f.suggestion == null) f.copy(suggestion = hint) else f
      }
    return c.copy(findings = findings, contributors = estimate.contributors)
  }

  private class MemoryRun(
    val check: ReportCheck,
    val footprint: MemoryFootprintReport?,
    val diagnostics: List<PackagingDiagnostic>,
    val threw: Boolean,
  )

  private fun runMemory(apk: File, version: WffVersion): MemoryRun {
    val result =
      try {
        validators.memory.evaluate(apk, version)
      } catch (e: PackagingException) {
        return MemoryRun(
          check(MEMORY_FOOTPRINT, e.diagnostics.map { it.toFinding() }),
          null,
          e.diagnostics,
          threw = true,
        )
      } catch (e: Exception) {
        val d = error("memory-footprint", "Memory evaluator crashed: $e")
        return MemoryRun(check(MEMORY_FOOTPRINT, listOf(d.toFinding())), null, listOf(d), true)
      }
    val failure = result.limitFailure?.let { error("memory-footprint", it) }
    val f = result.footprint
    return MemoryRun(
      check(MEMORY_FOOTPRINT, listOfNotNull(failure?.toFinding()))
        .copy(
          memory =
            ReportMemory(
              f.totalBytes,
              f.maxActiveBytes,
              f.maxAmbientBytes,
              result.activeLimitBytes,
              result.ambientLimitBytes,
            ),
        ),
      f,
      listOfNotNull(failure),
      threw = false,
    )
  }
}

private val MISSING_ASSET = Regex("""Asset (\S+) was not found""")
