package ee.schimke.watchface.apk

import ee.schimke.watchface.packager.DiagnosticSeverity
import ee.schimke.watchface.packager.PackageTarget
import ee.schimke.watchface.packager.PackagingDiagnostic
import ee.schimke.watchface.packager.PackagingException
import ee.schimke.watchface.packager.PushRules
import ee.schimke.watchface.wff.WffBundleLayout
import ee.schimke.watchface.wff.WffVersion
import ee.schimke.watchface.wff.WffXmlParser

public class WffApkOptions(
  public val target: PackageTarget,
  /** Caller-managed, must increase with every published version of the same face. */
  public val versionCode: Int,
  /** If set, [versionCode] must be strictly greater than this (the last published code). */
  public val previousVersionCode: Int? = null,
  /** Override the `versionName`; null keeps the bundle manifest's. */
  public val versionName: String? = null,
  /**
   * SHA-256 of the certificate the APK must be signed with (hex, colons and case ignored). Building
   * fails before signing if the signer's certificate differs, so a configuration slip cannot ship
   * an APK that will not update the installed one. Null skips the check.
   */
  public val expectedCertificateSha256: String? = null,
  /** Also sign with APK Signature Scheme v3 (v2 is always written). */
  public val signV3: Boolean = true,
)

public class WffApk(
  public val apk: ByteArray,
  public val packageName: String,
  public val versionCode: Int,
  public val wffVersion: WffVersion,
  /** Warnings; an error throws [PackagingException]. */
  public val diagnostics: List<PackagingDiagnostic>,
  /** Lower-case hex SHA-256 of the signing certificate. */
  public val signerCertificateSha256: String,
)

/**
 * Packages a Watch Face Format source bundle ([WffBundleLayout.sourceFiles]) into a signed,
 * installable APK, entirely in Kotlin, so it runs in the browser (Kotlin/Wasm) as well as on the
 * JVM. It does what `wff-packager` does with aapt2, zipalign and apksig:
 *
 * 1. [ApkPreflight]: the same option, manifest and Watch Face Push rule checks `wff-packager` runs.
 * 2. `AndroidManifest.xml` and the XML under `res/xml` to binary XML, with the framework attribute
 *    ids.
 * 3. A `resources.arsc` declaring `res/` (files by path, `<string>` values) under aapt2's ids.
 * 4. A zip with `resources.arsc` stored, every other stored entry 4-byte aligned.
 * 5. APK Signature Scheme v2 and v3 with the caller's [ApkSigner].
 *
 * What it does not do is run Google's Watch Face Push validator, which is what issues the
 * validation token and is JVM-only; hand the APK to `wff-packager`'s `Validators` for that.
 * Resource support is the subset a face uses: `<string>` values in `values` directories, file
 * resources under the standard type directories, `res/xml` documents, and the `android:` attributes
 * in [AndroidAttributes]. Anything else fails with a diagnostic instead of producing a table the
 * platform would read differently.
 */
public object WffApkBuilder {
  /**
   * Entries stored rather than deflated: already-compressed media, and the table the platform maps.
   */
  private val STORED_EXTENSIONS =
    setOf("png", "jpg", "jpeg", "gif", "webp", "ogg", "mp3", "mp4", "m4a", "wav", "zip", "arsc")

  public suspend fun build(
    bundle: Map<String, ByteArray>,
    options: WffApkOptions,
    signer: ApkSigner,
  ): WffApk {
    val preflight =
      ApkPreflight.check(
        bundle,
        options.target,
        options.versionCode,
        options.versionName,
        options.previousVersionCode,
      )
    val diagnostics = preflight.diagnostics.toMutableList()

    val signerSha = signer.certificateSha256
    options.expectedCertificateSha256?.let { expected ->
      val pin =
        normalizeSha256(expected)
          ?: throw PackagingException(
            "signing",
            "Expected certificate SHA-256 must be 64 hex digits (colons allowed), got ${expected.length} characters",
          )
      if (signerSha != pin) {
        throw PackagingException(
          "signing",
          "Signing certificate SHA-256 $signerSha does not match the pinned $pin; refusing to sign. " +
            "APKs signed with a different key cannot update installed watch faces.",
        )
      }
    }

    val files = preflight.files
    val table = ResourceTable.fromBundle(preflight.packageName, files)
    val items = ArrayList<ZipItem>()
    items +=
      ZipItem(
        WffBundleLayout.MANIFEST_PATH,
        BinaryXml.compile(
          preflight.manifest,
          WffBundleLayout.MANIFEST_PATH,
          utf8 = false,
          table::idOf,
        ),
        compress = true,
      )
    for (path in table.filePaths()) {
      val data = files.getValue(path)
      val type = path.removePrefix("res/").substringBefore('/').substringBefore('-')
      val isXml = path.endsWith(".xml")
      val bytes =
        when {
          type == "raw" -> data
          isXml && type == "xml" -> {
            val doc =
              WffXmlParser.parseOrNull(data.decodeToString())
                ?: throw PackagingException("resources", "$path is not well-formed XML")
            BinaryXml.compile(doc, path, utf8 = true, table::idOf)
          }
          isXml ->
            throw PackagingException(
              "resources",
              "$path: only res/xml and res/raw XML files can be packaged (other XML resources need " +
                "attribute definitions this packager does not carry)",
            )
          else -> data
        }
      items +=
        ZipItem(path, bytes, compress = path.substringAfterLast('.', "") !in STORED_EXTENSIONS)
    }
    items += ZipItem("resources.arsc", table.toArsc(), compress = false)

    val unsigned = Zip.build(items)
    diagnostics += PushRules.checkApkEntries(items.map { it.name })
    if (diagnostics.any { it.severity == DiagnosticSeverity.ERROR }) {
      throw PackagingException(diagnostics)
    }
    val signed = ApkSigningBlock.sign(unsigned, signer, preflight.minSdk, options.signV3)
    return WffApk(
      apk = signed,
      packageName = preflight.packageName,
      versionCode = options.versionCode,
      wffVersion = preflight.wffVersion,
      diagnostics = diagnostics.toList(),
      signerCertificateSha256 = signerSha,
    )
  }

  /** Accepts `AB:CD:…`, `abcd…` or with spaces; returns lower-case hex, or null if not SHA-256. */
  internal fun normalizeSha256(value: String): String? =
    value
      .filterNot { it == ':' || it.isWhitespace() }
      .lowercase()
      .takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
}
