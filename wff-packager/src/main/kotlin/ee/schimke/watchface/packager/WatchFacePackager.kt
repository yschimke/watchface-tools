package ee.schimke.watchface.packager

import ee.schimke.watchface.apk.ApkPreflight
import ee.schimke.watchface.apk.ApkPreflightResult
import ee.schimke.watchface.packager.ReportCheck.Companion.MEMORY_FOOTPRINT
import ee.schimke.watchface.packager.ReportCheck.Companion.PUSH_VALIDATOR
import ee.schimke.watchface.packager.ReportCheck.Companion.WFF_SCHEMA
import ee.schimke.watchface.packager.ReportCheck.Companion.WFF_XSD
import ee.schimke.watchface.wff.WffBundleLayout
import ee.schimke.watchface.wff.WffDocument
import ee.schimke.watchface.wff.WffSeverity
import ee.schimke.watchface.wff.WffValidator
import ee.schimke.watchface.wff.WffVersion
import ee.schimke.watchface.wff.WffXmlParser
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
          face.declared?.number,
          face.document?.let { WffValidator.minimumVersion(it).number },
          ReportCheck.TITLES.keys.map { checks[it] ?: skipped(it) },
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
      if (signed != null && face.declared != null) {
        val memory = runMemory(signed, face.declared)
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
      if (xml != null && face.declared != null && (signed != null || keepGoing)) {
        val found = validators.format.validate(xml, face.declared)
        diagnostics += found.map { error("wff-validator", it) }
        checks[WFF_XSD] = check(WFF_XSD, found.map(::xsdFinding))
      } else if (xml == null) {
        checks[WFF_XSD] =
          skip(WFF_XSD, FindingSeverity.ERROR, "Not run: the bundle has no watchface.xml")
      } else if (face.declared == null) {
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

/** The bundle's face and declared version, read without the manifest rewrite. */
private class FaceInfo(
  val xmlPresent: Boolean,
  val document: WffDocument?,
  val declared: WffVersion?,
) {
  private val issues =
    if (document != null && declared != null) WffValidator.check(document, declared)
    else emptyList()

  /** The same diagnostics, in the same words, that [ApkPreflight] reports for the face. */
  fun wffCoreDiagnostics(): List<PackagingDiagnostic> =
    when {
      !xmlPresent -> emptyList()
      document == null ->
        listOf(error("wff", "${WffBundleLayout.WATCH_FACE_PATH} is not well-formed XML"))
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

  fun schemaCheck(): ReportCheck =
    when {
      !xmlPresent ->
        skip(WFF_SCHEMA, FindingSeverity.ERROR, "Not run: the bundle has no watchface.xml")
      document == null ->
        check(
          WFF_SCHEMA,
          listOf(
            ReportFinding(
              FindingSeverity.ERROR,
              "${WffBundleLayout.WATCH_FACE_PATH} is not well-formed XML",
            ),
          ),
        )
      declared == null -> noVersionSkip(WFF_SCHEMA)
      else ->
        check(
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

private fun inspect(bundle: Map<String, ByteArray>): FaceInfo {
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
  return FaceInfo(xml != null, xml?.let(WffXmlParser::parseOrNull), declared)
}

private fun title(id: String) = ReportCheck.TITLES.getValue(id)

/** Passed unless a finding is an error. */
private fun check(id: String, findings: List<ReportFinding>) =
  ReportCheck(
    id,
    title(id),
    if (findings.any { it.severity == FindingSeverity.ERROR }) CheckStatus.FAILED
    else CheckStatus.PASSED,
    findings,
  )

private fun skip(id: String, severity: FindingSeverity, why: String) =
  ReportCheck(id, title(id), CheckStatus.SKIPPED, listOf(ReportFinding(severity, why)))

private fun noVersionSkip(id: String) =
  skip(
    id,
    FindingSeverity.ERROR,
    "Not run: the manifest declares no valid Watch Face Format version " +
      "(${WffBundleLayout.WFF_VERSION_PROPERTY})",
  )
