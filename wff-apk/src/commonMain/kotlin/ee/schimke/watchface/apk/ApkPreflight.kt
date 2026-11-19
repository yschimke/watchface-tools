package ee.schimke.watchface.apk

import ee.schimke.watchface.packager.DiagnosticSeverity
import ee.schimke.watchface.packager.PackageTarget
import ee.schimke.watchface.packager.PackagingDiagnostic
import ee.schimke.watchface.packager.PackagingException
import ee.schimke.watchface.packager.PushRules
import ee.schimke.watchface.wff.WffBundleLayout
import ee.schimke.watchface.wff.WffDocument
import ee.schimke.watchface.wff.WffSeverity
import ee.schimke.watchface.wff.WffValidator
import ee.schimke.watchface.wff.WffVersion
import ee.schimke.watchface.wff.WffXmlParser
import ee.schimke.watchface.wff.toXml

/** What [ApkPreflight.check] settled on: the manifest as it will be packaged, and what it said. */
public class ApkPreflightResult(
  /** The bundle with `AndroidManifest.xml` rewritten for the target, version code and name. */
  public val files: Map<String, ByteArray>,
  public val manifest: WffDocument,
  public val packageName: String,
  public val wffVersion: WffVersion,
  public val minSdk: Int,
  /** Warnings only: an error throws. */
  public val diagnostics: List<PackagingDiagnostic>,
)

/**
 * Everything checked about a bundle before any byte of an APK is written, shared by the JVM
 * `wff-packager` (which then runs aapt2) and the pure-Kotlin [WffApkBuilder] (which does not): the
 * option rules, the manifest rewrite, the Watch Face Push structural rules ([PushRules]) and the
 * `wff-core` check of the face against its declared Watch Face Format version.
 */
public object ApkPreflight {
  /** Throws [PackagingException] with every finding when anything is an error. */
  public fun check(
    bundle: Map<String, ByteArray>,
    target: PackageTarget,
    versionCode: Int,
    versionName: String? = null,
    previousVersionCode: Int? = null,
  ): ApkPreflightResult =
    check(bundle, target, versionCode, versionName, previousVersionCode, checkWatchFace = true)

  /**
   * [check], optionally without the `wff-core` check of `res/raw/watchface.xml` ([checkWatchFace]
   * false), for a caller that runs and reports that check itself and still wants an APK built from
   * a face that fails it (the packager's `validate`, which runs every check).
   */
  public fun check(
    bundle: Map<String, ByteArray>,
    target: PackageTarget,
    versionCode: Int,
    versionName: String?,
    previousVersionCode: Int?,
    checkWatchFace: Boolean,
  ): ApkPreflightResult {
    val diagnostics = mutableListOf<PackagingDiagnostic>()

    fun fail(): Nothing = throw PackagingException(diagnostics.toList())

    fun hasErrors() = diagnostics.any { it.severity == DiagnosticSeverity.ERROR }

    fun error(source: String, message: String) =
      PackagingDiagnostic(DiagnosticSeverity.ERROR, source, message)

    // 1. Options and manifest.
    diagnostics += PushRules.checkTarget(target)
    if (versionCode < 1) {
      diagnostics += error("manifest", "versionCode must be >= 1 (was $versionCode)")
    }
    previousVersionCode?.let {
      if (versionCode <= it) {
        diagnostics +=
          error("manifest", "versionCode $versionCode must be greater than the previous $it")
      }
    }
    val manifestBytes =
      bundle[WffBundleLayout.MANIFEST_PATH]
        ?: throw PackagingException("manifest", "Bundle has no ${WffBundleLayout.MANIFEST_PATH}")
    val manifest =
      WffXmlParser.parseOrNull(manifestBytes.decodeToString())
        ?: throw PackagingException("manifest", "AndroidManifest.xml is not well-formed XML")
    if (hasErrors()) fail()

    val rewritten = rewriteManifest(manifest, target, versionCode, versionName)
    val files = LinkedHashMap(bundle)
    files[WffBundleLayout.MANIFEST_PATH] = rewritten.toXml().encodeToByteArray()

    // 2. Rule checks on the final manifest and resources.
    val packageName = rewritten.root.attr("package").orEmpty()
    val wffVersion = readWffVersion(rewritten, diagnostics)
    diagnostics += PushRules.checkBundle(files)
    if (target is PackageTarget.WatchFacePush) {
      diagnostics += PushRules.checkPackageName(packageName, target.marketplacePackage)
    } else if (!PushRules.isValidPackageName(packageName)) {
      diagnostics += error("manifest", "Package '$packageName' is not a valid package name")
    }
    if (wffVersion != null && checkWatchFace) diagnostics += checkWatchFace(files, wffVersion)
    if (hasErrors()) fail()

    val minSdk =
      rewritten.root.firstChild("uses-sdk")?.attr("android:minSdkVersion")?.toIntOrNull()
        ?: throw PackagingException("manifest", "<uses-sdk android:minSdkVersion> is missing")
    return ApkPreflightResult(
      files,
      rewritten,
      packageName,
      wffVersion!!,
      minSdk,
      diagnostics.toList(),
    )
  }

  private fun rewriteManifest(
    manifest: WffDocument,
    target: PackageTarget,
    versionCode: Int,
    versionName: String?,
  ): WffDocument {
    var root = manifest.root
    target.packageName?.let { root = root.withAttribute("package", it) }
    root = root.withAttribute("android:versionCode", versionCode.toString())
    versionName?.let { root = root.withAttribute("android:versionName", it) }
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
        PackagingDiagnostic(
          DiagnosticSeverity.ERROR,
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
        ?: return listOf(
          PackagingDiagnostic(
            DiagnosticSeverity.ERROR,
            "wff",
            "${WffBundleLayout.WATCH_FACE_PATH} is not well-formed XML",
          ),
        )
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
