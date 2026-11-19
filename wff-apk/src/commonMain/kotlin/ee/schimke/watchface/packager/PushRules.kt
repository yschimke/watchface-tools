package ee.schimke.watchface.packager

import ee.schimke.watchface.wff.WffBundleLayout
import ee.schimke.watchface.wff.WffDocument
import ee.schimke.watchface.wff.WffElement
import ee.schimke.watchface.wff.WffVersion
import ee.schimke.watchface.wff.WffXmlParser

/**
 * The Watch Face Push APK rules from developer.android.com/training/wearables/watch-face-push,
 * checked early so failures read clearly. Google's validator stays the authority (it issues the
 * token); these exist for fast, specific diagnostics.
 */
public object PushRules {
  public const val MIN_SDK: Int = 33
  private const val SOURCE = "push-rules"

  public val ALLOWED_MANIFEST_TAGS: Set<String> =
    setOf("manifest", "uses-feature", "uses-sdk", "application", "property", "meta-data")

  private val segment = Regex("[A-Za-z][A-Za-z0-9_]*")

  public fun isValidPackageName(name: String): Boolean =
    name.split('.').let { it.size >= 2 && it.all(segment::matches) }

  public fun isValidFaceName(name: String): Boolean = segment.matches(name)

  /** `<marketplacePackage>.watchfacepush.<faceName>` */
  public fun packageName(marketplacePackage: String, faceName: String): String =
    "$marketplacePackage.watchfacepush.$faceName"

  /** [packageName] must be exactly `<marketplace>.watchfacepush.<face>` for a valid face name. */
  public fun checkPackageName(
    packageName: String,
    marketplacePackage: String,
  ): List<PackagingDiagnostic> {
    val out = mutableListOf<PackagingDiagnostic>()
    if (!isValidPackageName(marketplacePackage)) {
      out += error(SOURCE, "Marketplace package '$marketplacePackage' is not a valid package name")
    }
    val prefix = "$marketplacePackage.watchfacepush."
    if (!packageName.startsWith(prefix)) {
      out +=
        error(
          SOURCE,
          "Package '$packageName' must be '$prefix<faceName>' for Watch Face Push",
        )
    } else if (!isValidFaceName(packageName.removePrefix(prefix))) {
      out +=
        error(
          SOURCE,
          "Face name '${packageName.removePrefix(prefix)}' in '$packageName' must be a single " +
            "package segment (letters, digits, underscore; starting with a letter)",
        )
    }
    return out
  }

  public fun checkTarget(target: PackageTarget): List<PackagingDiagnostic> =
    when (target) {
      is PackageTarget.Sideload ->
        target.packageName
          ?.takeIf { !isValidPackageName(it) }
          ?.let { listOf(error(SOURCE, "Package '$it' is not a valid package name")) }
          .orEmpty()
      is PackageTarget.WatchFacePush ->
        buildList {
          if (!isValidPackageName(target.marketplacePackage)) {
            add(
              error(
                SOURCE,
                "Marketplace package '${target.marketplacePackage}' is not a valid package name",
              ),
            )
          }
          if (!isValidFaceName(target.faceName)) {
            add(
              error(
                SOURCE,
                "Face name '${target.faceName}' must be a single package segment (letters, " +
                  "digits, underscore; starting with a letter)",
              ),
            )
          }
        }
    }

  /** Checks the (already rewritten) manifest and the resource files of a source bundle. */
  public fun checkBundle(files: Map<String, ByteArray>): List<PackagingDiagnostic> {
    val out = mutableListOf<PackagingDiagnostic>()
    val manifestBytes = files[WffBundleLayout.MANIFEST_PATH]
    if (manifestBytes == null) {
      return listOf(error("manifest", "Bundle has no ${WffBundleLayout.MANIFEST_PATH}"))
    }
    val manifest =
      WffXmlParser.parseOrNull(manifestBytes.decodeToString())
        ?: return listOf(error("manifest", "AndroidManifest.xml is not well-formed XML"))
    out += checkManifest(manifest)
    out += checkSingleWatchFace(files)
    for (path in files.keys) {
      if (path != WffBundleLayout.MANIFEST_PATH && !path.startsWith("res/")) {
        out +=
          error(
            "bundle",
            "Unexpected bundle file '$path': only AndroidManifest.xml and res/... may be packaged",
          )
      }
    }
    return out
  }

  public fun checkManifest(manifest: WffDocument): List<PackagingDiagnostic> {
    val out = mutableListOf<PackagingDiagnostic>()
    val root = manifest.root
    fun walk(e: WffElement) {
      if (e.name !in ALLOWED_MANIFEST_TAGS) {
        out +=
          error(
            "manifest",
            "<${e.name}> is not allowed; Watch Face Push manifests may only contain " +
              ALLOWED_MANIFEST_TAGS.joinToString { "<$it>" },
          )
      }
      e.childElements.forEach(::walk)
    }
    walk(root)
    val minSdk = root.firstChild("uses-sdk")?.attr("android:minSdkVersion")
    // Each WFF version needs the Wear OS release that introduced it (v2 needs API 34, not 33), so
    // the floor comes from the declared format version; MIN_SDK is the floor for Push regardless.
    val versionProperty =
      root.firstChild("application")?.childElements("property")?.firstOrNull {
        it.attr("android:name") == WffBundleLayout.WFF_VERSION_PROPERTY
      }
    val rawVersionValue = versionProperty?.attr("android:value")
    val rawVersionResource = versionProperty?.attr("android:resource")
    val declaredVersion =
      when {
        versionProperty == null -> null
        rawVersionValue == null && rawVersionResource != null -> {
          out +=
            error(
              "manifest",
              "${WffBundleLayout.WFF_VERSION_PROPERTY} must use a literal integer android:value, not android:resource='$rawVersionResource' (Watch Face Push validator rejects resource references)",
            )
          null
        }
        rawVersionValue == null -> {
          out +=
            error(
              "manifest",
              "${WffBundleLayout.WFF_VERSION_PROPERTY} is missing a literal integer android:value",
            )
          null
        }
        rawVersionValue.toIntOrNull() == null -> {
          out +=
            error(
              "manifest",
              "${WffBundleLayout.WFF_VERSION_PROPERTY} android:value='$rawVersionValue' must be a literal integer (resource references like @integer/... are rejected by Watch Face Push)",
            )
          null
        }
        else -> {
          val parsed = WffVersion.fromNumber(rawVersionValue.toInt())
          if (parsed == null) {
            out +=
              error(
                "manifest",
                "${WffBundleLayout.WFF_VERSION_PROPERTY} android:value='$rawVersionValue' is not a supported Watch Face Format version",
              )
          }
          parsed
        }
      }
    val required = maxOf(MIN_SDK, declaredVersion?.let(WffBundleLayout::minSdkFor) ?: MIN_SDK)
    val why =
      if (declaredVersion != null && required > MIN_SDK)
        " for Watch Face Format version ${declaredVersion.number}"
      else ""
    when {
      minSdk == null ->
        out +=
          error("manifest", "<uses-sdk android:minSdkVersion> is missing; need >= $required$why")
      minSdk.toIntOrNull() == null ->
        out += error("manifest", "minSdkVersion '$minSdk' is not a number (codenames not allowed)")
      minSdk.toInt() < required ->
        out += error("manifest", "minSdkVersion is $minSdk but must be >= $required$why")
    }
    val app = root.firstChild("application")
    if (app == null) {
      out += error("manifest", "<application> is missing")
    } else if (app.attr("android:hasCode") != "false") {
      out +=
        error(
          "manifest",
          "<application> must declare android:hasCode=\"false\" (found '${app.attr("android:hasCode")}')",
        )
    }
    return out
  }

  /** Exactly one WFF watch face: `res/raw/watchface.xml`, and no other `<WatchFace>` document. */
  public fun checkSingleWatchFace(files: Map<String, ByteArray>): List<PackagingDiagnostic> {
    val faces =
      files.entries
        .filter { (path, _) -> path.startsWith("res/raw") && path.endsWith(".xml") }
        .filter { (_, bytes) ->
          WffXmlParser.parseOrNull(bytes.decodeToString())?.root?.name == "WatchFace"
        }
        .map { it.key }
    return when {
      WffBundleLayout.WATCH_FACE_PATH !in files ->
        listOf(error("bundle", "Missing ${WffBundleLayout.WATCH_FACE_PATH}"))
      faces.size != 1 ->
        listOf(
          error(
            "bundle",
            "Exactly one watch face per APK is allowed; found ${faces.size}: ${faces.sorted()}",
          ),
        )
      faces.single() != WffBundleLayout.WATCH_FACE_PATH ->
        listOf(error("bundle", "The watch face must be ${WffBundleLayout.WATCH_FACE_PATH}"))
      else -> emptyList()
    }
  }

  /**
   * Only `AndroidManifest.xml`, `resources.arsc`, `res/...` and `META-INF/...` may be in the APK.
   */
  public fun checkApkEntries(entryNames: Collection<String>): List<PackagingDiagnostic> {
    val out = mutableListOf<PackagingDiagnostic>()
    for (name in entryNames) {
      val ok =
        name == "AndroidManifest.xml" ||
          name == "resources.arsc" ||
          name.startsWith("res/") ||
          name.startsWith("META-INF/")
      if (!ok) out += error("apk", "APK entry '$name' is not allowed in a Watch Face Push APK")
    }
    for (required in listOf("AndroidManifest.xml", "resources.arsc")) {
      if (required !in entryNames) out += error("apk", "APK is missing '$required'")
    }
    return out
  }
}
