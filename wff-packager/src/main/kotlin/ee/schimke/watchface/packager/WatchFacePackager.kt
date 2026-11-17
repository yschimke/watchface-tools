package ee.schimke.watchface.packager

import ee.schimke.watchface.wff.WffBundleLayout
import ee.schimke.watchface.wff.WffDocument
import ee.schimke.watchface.wff.WffSeverity
import ee.schimke.watchface.wff.WffValidator
import ee.schimke.watchface.wff.WffVersion
import ee.schimke.watchface.wff.WffXmlParser
import ee.schimke.watchface.wff.toXml
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

    // 1. Options and manifest.
    diagnostics += PushRules.checkTarget(options.target)
    if (options.versionCode < 1) {
      diagnostics += error("manifest", "versionCode must be >= 1 (was ${options.versionCode})")
    }
    options.previousVersionCode?.let {
      if (options.versionCode <= it) {
        diagnostics +=
          error(
            "manifest",
            "versionCode ${options.versionCode} must be greater than the previous $it",
          )
      }
    }
    val manifestBytes =
      bundle[WffBundleLayout.MANIFEST_PATH]
        ?: throw PackagingException("manifest", "Bundle has no ${WffBundleLayout.MANIFEST_PATH}")
    val manifest =
      WffXmlParser.parseOrNull(manifestBytes.decodeToString())
        ?: throw PackagingException("manifest", "AndroidManifest.xml is not well-formed XML")
    if (diagnostics.hasErrors()) fail()

    val rewritten = rewriteManifest(manifest, options)
    val files = LinkedHashMap(bundle)
    files[WffBundleLayout.MANIFEST_PATH] = rewritten.toXml().encodeToByteArray()

    // 2. Rule checks on the final manifest and resources.
    val packageName = rewritten.root.attr("package").orEmpty()
    val wffVersion = readWffVersion(rewritten, diagnostics)
    diagnostics += PushRules.checkBundle(files)
    if (options.target is PackageTarget.WatchFacePush) {
      diagnostics += PushRules.checkPackageName(packageName, options.target.marketplacePackage)
    } else if (!PushRules.isValidPackageName(packageName)) {
      diagnostics += error("manifest", "Package '$packageName' is not a valid package name")
    }
    if (wffVersion != null) diagnostics += checkWatchFace(files, wffVersion)
    if (diagnostics.hasErrors()) fail()
    wffVersion!!

    // 3. Build.
    val tools = sdk ?: AndroidSdk.require()
    val signingKey = loadSigningKey(options.signing)
    if (signingKey.debug) {
      diagnostics +=
        warning(
          "signing",
          "Signed with the generated per-installation debug key; pass a keystore for release",
        )
    }
    val minSdk = rewritten.root.firstChild("uses-sdk")!!.attr("android:minSdkVersion")!!.toInt()
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
      var token: String? = null
      when (val target = options.target) {
        is PackageTarget.WatchFacePush -> {
          val result = validators.push.validate(signed, target.marketplacePackage)
          diagnostics += result.failures
          token = result.validationToken
          if (!result.failures.hasErrors() && token.isNullOrEmpty()) {
            diagnostics += error("push-validator", "Validator passed but returned no token")
          }
        }
        is PackageTarget.Sideload -> {
          val xml = files.getValue(WffBundleLayout.WATCH_FACE_PATH).decodeToString()
          diagnostics +=
            validators.format.validate(xml, wffVersion).map { error("wff-validator", it) }
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
      )
    } finally {
      work.deleteRecursively()
    }
  }

  private fun rewriteManifest(manifest: WffDocument, options: PackagerOptions): WffDocument {
    var root = manifest.root
    options.target.packageName?.let { root = root.withAttribute("package", it) }
    root = root.withAttribute("android:versionCode", options.versionCode.toString())
    options.versionName?.let { root = root.withAttribute("android:versionName", it) }
    return manifest.withRoot(root)
  }

  private fun readWffVersion(
    manifest: WffDocument,
    diagnostics: MutableList<PackagingDiagnostic>,
  ): WffVersion? {
    val value =
      manifest.root
        .firstChild("application")
        ?.childElements("property")
        ?.firstOrNull { it.attr("android:name") == WffBundleLayout.WFF_VERSION_PROPERTY }
        ?.attr("android:value")
    val version = value?.toIntOrNull()?.let(WffVersion::fromNumber)
    if (version == null) {
      diagnostics +=
        error(
          "manifest",
          "Manifest needs <property android:name=\"${WffBundleLayout.WFF_VERSION_PROPERTY}\" " +
            "android:value=\"1..${WffVersion.LATEST.number}\"/> (found ${value ?: "none"})",
        )
    }
    return version
  }

  /** wff-core structural + version check of the watch face against the declared version. */
  private fun checkWatchFace(
    files: Map<String, ByteArray>,
    version: WffVersion,
  ): List<PackagingDiagnostic> {
    val xml = files[WffBundleLayout.WATCH_FACE_PATH] ?: return emptyList()
    val doc =
      WffXmlParser.parseOrNull(xml.decodeToString())
        ?: return listOf(error("wff", "${WffBundleLayout.WATCH_FACE_PATH} is not well-formed XML"))
    return WffValidator.check(doc, version).map {
      PackagingDiagnostic(
        if (it.severity == WffSeverity.ERROR) DiagnosticSeverity.ERROR
        else DiagnosticSeverity.WARNING,
        "wff",
        "${it.path}: ${it.message} (${it.code})",
      )
    }
  }
}
