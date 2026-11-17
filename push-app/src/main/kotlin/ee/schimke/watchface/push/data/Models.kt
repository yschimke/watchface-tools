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
