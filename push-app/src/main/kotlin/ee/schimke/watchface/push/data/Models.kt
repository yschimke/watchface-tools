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

@Serializable
data class InstallEvent(
  val designId: String,
  val version: Long,
  /** `installed`, `updated`, `replaced` or `failed`. */
  val result: String,
  val errorKind: String? = null,
  val wearOsApi: Int? = null,
)
