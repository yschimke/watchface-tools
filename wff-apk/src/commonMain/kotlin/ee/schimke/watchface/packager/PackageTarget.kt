package ee.schimke.watchface.packager

// This package name is kept from when the type lived in :wff-packager, so its callers still
// compile.

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
