package ee.schimke.watchface.push

/**
 * The marketplace package is the Wear OS app's applicationId. It is defined once, as
 * `watchface.marketplacePackage` in the root `gradle.properties`, and surfaced here through
 * BuildConfig. The packager must name every pushed APK `<marketplacePackage>.watchfacepush.<face>`.
 */
object PushConstants {
  const val MARKETPLACE_PACKAGE: String = BuildConfig.MARKETPLACE_PACKAGE
  const val WATCH_FACE_INFIX: String = ".watchfacepush."
  const val API_PREFIX: String = "/api/watchface-push/v1"

  /** Package name prefix every design APK must carry. */
  const val PACKAGE_PREFIX: String = MARKETPLACE_PACKAGE + WATCH_FACE_INFIX

  /** True when [packageName] is a valid pushed watch face package for this marketplace. */
  fun isValidFacePackage(packageName: String): Boolean {
    if (!packageName.startsWith(PACKAGE_PREFIX)) return false
    val face = packageName.removePrefix(PACKAGE_PREFIX)
    return face.isNotEmpty() && face.all { it.isLetterOrDigit() || it == '_' }
  }
}
