package ee.schimke.watchface.packager

import ee.schimke.watchface.wff.WffVersion
import java.io.File

/** What the APK is for. It decides the package name. */
public sealed interface PackageTarget {
  /** The package name written to the manifest, or null to keep the bundle manifest's. */
  public val packageName: String?

  /** Sideload / Play-style APK with a plain package name. */
  public data class Sideload(override val packageName: String? = null) : PackageTarget

  /**
   * Watch Face Push: the package becomes `<marketplacePackage>.watchfacepush.<faceName>` and the
   * official push validator is run to obtain the validation token.
   */
  public data class WatchFacePush(val marketplacePackage: String, val faceName: String) :
    PackageTarget {
    override val packageName: String
      get() = PushRules.packageName(marketplacePackage, faceName)
  }
}

/** Where the signing key comes from. */
public sealed interface SigningConfig {
  /** A caller-supplied keystore (PKCS12 or JKS). */
  public class Keystore(
    public val file: File,
    public val storePassword: String,
    public val keyAlias: String? = null,
    public val keyPassword: String = storePassword,
  ) : SigningConfig

  /**
   * A debug key generated on first use under [directory] (default `$WFF_PACKAGER_HOME` or
   * `~/.config/wff-packager`). It is per installation, never part of the repository, and APKs
   * signed with it can't be updated by APKs signed with another key.
   */
  public class GeneratedDebugKey(public val directory: File = defaultDebugKeyDirectory()) :
    SigningConfig

  public companion object {
    public fun defaultDebugKeyDirectory(): File =
      System.getenv("WFF_PACKAGER_HOME")?.takeIf(String::isNotBlank)?.let(::File)
        ?: File(System.getProperty("user.home"), ".config/wff-packager")
  }
}

public class PackagerOptions(
  public val target: PackageTarget,
  /** Caller-managed, must increase with every published version of the same face. */
  public val versionCode: Int,
  public val signing: SigningConfig = SigningConfig.GeneratedDebugKey(),
  /** If set, [versionCode] must be strictly greater than this (the last published code). */
  public val previousVersionCode: Int? = null,
  /** Override the `versionName`; null keeps the bundle manifest's. */
  public val versionName: String? = null,
)

public class MemoryFootprintReport(
  public val totalBytes: Long,
  public val maxActiveBytes: Long,
  public val maxAmbientBytes: Long,
) {
  override fun toString(): String =
    "MemoryFootprint(total=$totalBytes, active=$maxActiveBytes, ambient=$maxAmbientBytes)"
}

public class PackagedWatchFace(
  public val apk: ByteArray,
  public val packageName: String,
  public val versionCode: Int,
  public val wffVersion: WffVersion,
  public val memoryFootprint: MemoryFootprintReport?,
  /** Only ever set from Google's official Watch Face Push validator. */
  public val validationToken: String?,
  public val diagnostics: List<PackagingDiagnostic>,
)
