package ee.schimke.watchface.push.ui

import ee.schimke.watchface.push.data.BuildStatus
import ee.schimke.watchface.push.push.Activation
import ee.schimke.watchface.push.push.BlockReason
import ee.schimke.watchface.push.push.InstallPlan
import ee.schimke.watchface.push.push.InstallResult
import ee.schimke.watchface.push.push.PushFailureKind

/** User-facing copy for flow outcomes, kept out of composables so it is unit-testable. */
object Messages {
  fun badServer(input: String): String =
    "\"${input.trim().take(40)}\" is not a server address. Use one like ui.coo.ee (https only)."

  fun blocked(reason: BlockReason, designWff: Int, deviceWff: Int): String =
    when (reason) {
      BlockReason.PUSH_UNSUPPORTED -> "This watch needs Wear OS 6 to install faces from here."
      BlockReason.WFF_TOO_NEW ->
        "This face needs Watch Face Format v$designWff. Your watch supports up to v$deviceWff."
      BlockReason.BAD_PACKAGE_NAME -> "This design has an invalid package name. Republish it."
      BlockReason.NO_SLOTS -> "No watch face slot is available."
      BlockReason.NOT_BUILT -> "This face is still being built. It can be installed once it is."
    }

  /**
   * Short text for a [BuildStatus] (the list subtitle and the detail status line), with "Builds in
   * N min" counted from [nowMillis].
   */
  fun build(build: BuildStatus, nowMillis: Long): String =
    when (build.state) {
      BuildStatus.PENDING -> {
        val at = build.scheduledAtEpochMillis
        val minutes = at?.let { ((it - nowMillis) + 59_999) / 60_000 }
        when {
          minutes == null -> "Build pending"
          minutes <= 0 -> "Building soon"
          else -> "Builds in $minutes min"
        }
      }
      BuildStatus.QUEUED ->
        build.queueAhead?.takeIf { it > 0 }?.let { "Queued ($it ahead)" } ?: "Queued"
      BuildStatus.REFUSED -> build.reason?.let { "Build refused: $it" } ?: "Build refused"
      BuildStatus.FAILED -> build.reason?.let { "Build failed: $it" } ?: "Build failed"
      BuildStatus.BUILT -> "Built"
      // `building` and any state this app does not know yet.
      else -> build.stage?.let { "Building… $it" } ?: "Building…"
    }

  fun failure(kind: PushFailureKind): String =
    when (kind) {
      PushFailureKind.SLOT_LIMIT_REACHED -> "No free slot. Try again to replace the current face."
      PushFailureKind.INVALID_VALIDATION_TOKEN ->
        "The watch rejected this design's validation token. Republish the design on the site."
      PushFailureKind.INVALID_PACKAGE_NAME ->
        "The watch rejected the package name. It must be ${ee.schimke.watchface.push.PushConstants.PACKAGE_PREFIX}<name>."
      PushFailureKind.MALFORMED_APK -> "The watch could not read this face package."
      PushFailureKind.UNEXPECTED_CONTENT ->
        "The package contains files a pushed watch face may not have."
      PushFailureKind.INVALID_SLOT -> "The slot changed on the watch. Try again."
      PushFailureKind.PERMISSION_MISSING -> "Permission to set the active face is missing."
      PushFailureKind.MAX_ACTIVATION_ATTEMPTS ->
        "Press and hold your current face, then choose this one."
      PushFailureKind.UNKNOWN -> "Something went wrong installing the face."
    }

  fun result(result: InstallResult, designWff: Int, deviceWff: Int): String =
    when (result) {
      is InstallResult.Success ->
        when (result.plan) {
          is InstallPlan.UpdateInstalled -> "Updated."
          is InstallPlan.ReplaceOther -> "Installed, replacing the previous face."
          else -> "Installed."
        } + activation(result.activation)
      is InstallResult.Blocked -> blocked(result.reason, designWff, deviceWff)
      is InstallResult.NeedsReplaceConfirmation -> "Replace the current face?"
      InstallResult.ChecksumMismatch -> "The download was corrupted (checksum mismatch). Try again."
      is InstallResult.DownloadFailed ->
        if (result.unauthorized && result.anonymous) "This face is private. Link your account."
        else if (result.unauthorized) "Your account link expired. Link again."
        else "Download failed. Check the connection."
      is InstallResult.PushRejected -> failure(result.kind)
    }

  private fun activation(activation: Activation): String =
    when (activation) {
      Activation.Active -> " It is now your watch face."
      is Activation.Manual -> " " + failure((activation as Activation.Manual).kind)
      Activation.NotRequested -> ""
    }
}
