package ee.schimke.watchface.packager

import ee.schimke.watchface.apk.ApkPreflight
import ee.schimke.watchface.wff.WffBundleLayout
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

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
  public fun pack(bundle: Map<String, ByteArray>, options: PackagerOptions): PackagedWatchFace {
    val diagnostics = mutableListOf<PackagingDiagnostic>()
    fun fail(): Nothing = throw PackagingException(diagnostics.toList())

    // 1-2. Options, manifest rewrite and the rule checks, shared with the pure-Kotlin APK builder.
    val preflight =
      ApkPreflight.check(
        bundle,
        options.target,
        options.versionCode,
        options.versionName,
        options.previousVersionCode,
      )
    diagnostics += preflight.diagnostics
    val files = preflight.files
    val packageName = preflight.packageName
    val wffVersion = preflight.wffVersion

    // 3. Build.
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
    val minSdk = preflight.minSdk
    val work = Files.createTempDirectory("wff-packager").toFile()
    try {
      val src = File(work, "bundle").also { it.mkdirs() }
      writeBundle(files, src)
      val linked = File(work, "linked.apk")
      linkResourceApk(tools, src, linked)
      val aligned = File(work, "aligned.apk")
      zipalign(tools, linked, aligned)
      val signed = File(work, "signed.apk")
      signApk(aligned, signed, signingKey, minSdk)

      val entries = ZipFile(signed).use { z -> z.entries().toList().map { it.name } }
      diagnostics += PushRules.checkApkEntries(entries)
      if (diagnostics.hasErrors()) fail()

      // 4. Official validators.
      val memory = validators.memory.evaluate(signed, wffVersion)
      memory.limitFailure?.let { diagnostics += error("memory-footprint", it) }
      // Google's XSD validator runs for both targets: it is the only source of the actual cvc
      // message (the push validator only says the face "does not match the specification").
      val xml = files.getValue(WffBundleLayout.WATCH_FACE_PATH).decodeToString()
      diagnostics += validators.format.validate(xml, wffVersion).map { error("wff-validator", it) }
      var token: String? = null
      val target = options.target
      if (target is PackageTarget.WatchFacePush) {
        val result = validators.push.validate(signed, target.marketplacePackage)
        diagnostics += result.failures
        token = result.validationToken
        if (!result.failures.hasErrors() && token.isNullOrEmpty()) {
          diagnostics += error("push-validator", "Validator passed but returned no token")
        }
      }
      if (diagnostics.hasErrors()) fail()

      return PackagedWatchFace(
        apk = signed.readBytes(),
        packageName = packageName,
        versionCode = options.versionCode,
        wffVersion = wffVersion,
        memoryFootprint = memory.footprint,
        validationToken = token?.takeIf(String::isNotEmpty),
        diagnostics = diagnostics.toList(),
        signerCertificateSha256 = certificateSha256(signingKey.chain.first()),
      )
    } finally {
      work.deleteRecursively()
    }
  }
}
