package ee.schimke.watchface.push.data

import kotlinx.serialization.Serializable

@Serializable
data class DeviceCodeRequest(val deviceName: String? = null, val wearOsApi: Int? = null)

@Serializable
data class DeviceCode(
  /**
   * Short code shown to the user, e.g. `ABCD-2345`. Also the `{code}` path segment when polling.
   */
  val code: String,
  /** Secret known only to this device; sent in the poll body. */
  val pollSecret: String,
  /** Page the user opens, e.g. `https://preview.coo.ee/link?code=ABCD-2345`. Encoded in the QR. */
  val verificationUrl: String,
  val expiresInSeconds: Int,
  val pollIntervalSeconds: Int = 5,
)

@Serializable data class PollRequest(val pollSecret: String)

@Serializable
data class PollResponse(
  /** `pending`, `approved`, `denied` or `expired`. */
  val status: String,
  val deviceToken: String? = null,
  val accountName: String? = null,
)

@Serializable data class DesignList(val designs: List<Design>)

@Serializable
data class Design(
  /**
   * Stable id. Catalog faces use `catalog:<system>/<previewId>`, which contains `/`: never build a
   * URL or a navigation route from it; use [apkUrl] and [previewUrl] exactly as given.
   */
  val id: String,
  val title: String,
  val description: String? = null,
  val previewUrl: String,
  /** Monotonic; equals the APK `versionCode`. */
  val version: Long,
  val versionName: String? = null,
  /** Watch Face Format version the design needs (1..5). */
  val wffVersion: Int,
  /** `<marketplacePackage>.watchfacepush.<faceName>` of the linked APK. */
  val packageName: String,
  val apkUrl: String,
  val sha256: String,
  val sizeBytes: Long? = null,
  val validationToken: String,
  /** `design` (an account's design) or `catalog` (a face from a public catalog); null = design. */
  val source: String? = null,
  /** Which catalog a `catalog` face comes from. */
  val catalog: CatalogInfo? = null,
  /** Where a `catalog` face's source lives and under which licence. */
  val attribution: Attribution? = null,
  /** `public` or `private`; display only. */
  val visibility: String? = null,
) {
  val isCatalog: Boolean
    get() = source == SOURCE_CATALOG

  companion object {
    const val SOURCE_CATALOG = "catalog"
  }
}

/** A public catalog of watch faces, e.g. `wear-os-samples-watchfaces` on preview.coo.ee. */
@Serializable data class CatalogInfo(val system: String, val title: String? = null)

/** Credit for a catalog face. [license] is an SPDX id, e.g. `Apache-2.0`. */
@Serializable
data class Attribution(
  val repoUrl: String? = null,
  val sourceUrl: String? = null,
  val license: String? = null,
)

/**
 * Best-effort install telemetry (`POST /installs`). Everything after [result] is optional; the
 * server must accept events without it.
 */
@Serializable
data class InstallEvent(
  val designId: String,
  val version: Long,
  /**
   * `installed`, `updated`, `replaced` or `failed`; `activation` for the follow-up event sent when
   * the face is activated after the install (once the user grants the permission).
   */
  val result: String,
  val errorKind: String? = null,
  val wearOsApi: Int? = null,
  val packageName: String? = null,
  /** `activated`, `not_requested`, `manual` (the user must pick it) or `failed`. */
  val activation: String? = null,
  /** The [ee.schimke.watchface.push.push.PushFailureKind] when [activation] is manual or failed. */
  val activationError: String? = null,
  val deviceModel: String? = null,
  /** Marketing version (`6`, `5.1`) when the API level is known, else null. */
  val wearOsVersion: String? = null,
  /** Version of the `androidx.wear.watchfacepush` library the app uses. */
  val watchFacePushApiVersion: String? = null,
  /** The push app's versionName. */
  val appVersion: String? = null,
)

/** What every [InstallEvent] says about the watch and the app. */
data class DeviceInfo(
  val wearOsApi: Int,
  val deviceModel: String? = null,
  val watchFacePushApiVersion: String? = null,
  val appVersion: String? = null,
) {
  val wearOsVersion: String?
    get() = WEAR_OS_VERSIONS[wearOsApi]

  fun stamp(event: InstallEvent): InstallEvent =
    event.copy(
      wearOsApi = wearOsApi,
      deviceModel = deviceModel,
      wearOsVersion = wearOsVersion,
      watchFacePushApiVersion = watchFacePushApiVersion,
      appVersion = appVersion,
    )

  private companion object {
    /** Wear OS release per Android API level (Wear OS 6 is Android 16, API 36). */
    val WEAR_OS_VERSIONS = mapOf(30 to "3", 33 to "4", 34 to "5", 35 to "5.1", 36 to "6")
  }
}
