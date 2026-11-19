package ee.schimke.watchface.push.push

import ee.schimke.watchface.push.PushConstants
import ee.schimke.watchface.push.data.ApiException
import ee.schimke.watchface.push.data.Design
import ee.schimke.watchface.push.data.DeviceInfo
import ee.schimke.watchface.push.data.InstallEvent
import ee.schimke.watchface.push.data.PushApi
import java.io.File

/** What installing a design would do given the slots on the watch. Pure; used by the UI too. */
sealed interface InstallPlan {
  /** Design not on the watch and a slot is free. */
  data object AddToFreeSlot : InstallPlan

  /** Same package already installed: replace it with this version. */
  data class UpdateInstalled(val slot: InstalledFace) : InstallPlan

  /** No free slot (Wear OS 6 gives a marketplace exactly one): the other design is replaced. */
  data class ReplaceOther(val slot: InstalledFace) : InstallPlan

  data class Blocked(val reason: BlockReason) : InstallPlan
}

enum class BlockReason {
  PUSH_UNSUPPORTED,
  WFF_TOO_NEW,
  BAD_PACKAGE_NAME,
  NO_SLOTS,
  /** The server has no built APK for this design yet (see `Design.build`). */
  NOT_BUILT,
}

object InstallPlanner {
  fun plan(design: Design, state: PushState, supported: Boolean, wffVersion: Int): InstallPlan {
    if (!supported) return InstallPlan.Blocked(BlockReason.PUSH_UNSUPPORTED)
    if (!design.isInstallable) return InstallPlan.Blocked(BlockReason.NOT_BUILT)
    if (design.wffVersion > wffVersion) return InstallPlan.Blocked(BlockReason.WFF_TOO_NEW)
    if (!PushConstants.isValidFacePackage(design.packageName))
      return InstallPlan.Blocked(BlockReason.BAD_PACKAGE_NAME)
    state.installed
      .firstOrNull { it.packageName == design.packageName }
      ?.let {
        return InstallPlan.UpdateInstalled(it)
      }
    if (state.remainingSlots > 0) return InstallPlan.AddToFreeSlot
    // Slots are full: reuse the marketplace's (only) slot. Prefer the one holding the oldest
    // version code so multi-slot watches replace the least recently updated design.
    val victim = state.installed.minByOrNull { it.versionCode }
    return if (victim != null) InstallPlan.ReplaceOther(victim)
    else InstallPlan.Blocked(BlockReason.NO_SLOTS)
  }

  fun hasUpdate(design: Design, state: PushState): Boolean =
    state.installed.any { it.packageName == design.packageName && it.versionCode < design.version }

  fun isInstalled(design: Design, state: PushState): Boolean =
    state.installed.any { it.packageName == design.packageName }
}

sealed interface Activation {
  /** Not requested (permission not granted, or the caller did not ask). */
  data object NotRequested : Activation

  data object Active : Activation

  /** Activation was refused (permission denied, used before, another maker's face is active). */
  data class Manual(val kind: PushFailureKind) : Activation
}

/** [InstallEvent.activation] and [InstallEvent.activationError] for an activation outcome. */
internal fun Activation.wire(requested: Boolean): Pair<String, String?> =
  when (this) {
    Activation.Active -> "activated" to null
    // Asked for, but the permission is not held: the user has to pick the face themselves.
    Activation.NotRequested ->
      if (requested) "manual" to PushFailureKind.PERMISSION_MISSING.name
      else "not_requested" to null
    is Activation.Manual ->
      when (kind) {
        PushFailureKind.PERMISSION_MISSING,
        PushFailureKind.MAX_ACTIVATION_ATTEMPTS -> "manual" to kind.name
        else -> "failed" to kind.name
      }
  }

sealed interface InstallResult {
  data class Success(
    val plan: InstallPlan,
    val face: InstalledFace,
    val activation: Activation,
  ) : InstallResult

  data class Blocked(val reason: BlockReason) : InstallResult

  /** The slot would be overwritten but the caller has not confirmed that. */
  data class NeedsReplaceConfirmation(val slot: InstalledFace) : InstallResult

  data object ChecksumMismatch : InstallResult

  /** [anonymous]: no device token was sent, so a 401 means the design is not public. */
  data class DownloadFailed(
    val unauthorized: Boolean,
    val message: String?,
    val anonymous: Boolean = false,
  ) : InstallResult

  data class PushRejected(val kind: PushFailureKind, val message: String?) : InstallResult
}

class InstallFlow(
  private val api: PushApi,
  private val pusher: WatchFacePusher,
  private val cacheDir: File,
  /** Stamped on every [InstallEvent]. */
  private val device: DeviceInfo,
  /** One line per step (download, verify, push); the emulator test asserts on them in logcat. */
  private val log: (String) -> Unit = {},
) {
  /**
   * Installs or updates [design]: plan against the watch's slots, download, verify SHA-256, then
   * hand the file to the push API. [confirmReplace] must be true to overwrite a different design's
   * slot. With [activate] the new face is made active afterwards when the permission is held.
   * Without a [deviceToken] (browsing public designs) nothing is reported to the server.
   */
  suspend fun install(
    deviceToken: String?,
    design: Design,
    confirmReplace: Boolean = false,
    activate: Boolean = false,
  ): InstallResult {
    // Slot ids can change under us, so always look them up right before the operation.
    val state =
      try {
        pusher.state()
      } catch (e: PushFailure) {
        return InstallResult.PushRejected(e.kind, e.message)
      }
    val plan = InstallPlanner.plan(design, state, pusher.isSupported, pusher.wffVersion)
    log(
      "plan design=${design.id} plan=${plan.javaClass.simpleName} anonymous=${deviceToken == null}",
    )
    if (plan is InstallPlan.Blocked) return InstallResult.Blocked(plan.reason)
    if (plan is InstallPlan.ReplaceOther && !confirmReplace)
      return InstallResult.NeedsReplaceConfirmation(plan.slot)

    val file = File.createTempFile("face-", ".apk", cacheDir)
    try {
      val actual =
        try {
          api.downloadApk(deviceToken, design, file)
        } catch (e: ApiException) {
          log("download design=${design.id} failed status=${e.status}")
          return InstallResult.DownloadFailed(e.isUnauthorized, e.message, deviceToken == null)
        }
      log("download design=${design.id} bytes=${file.length()} sha256=$actual")
      val verified = design.sha256 != null && actual.equals(design.sha256, ignoreCase = true)
      log("verify design=${design.id} sha256 ${if (verified) "ok" else "mismatch"}")
      if (!verified) return InstallResult.ChecksumMismatch

      // The planner blocks a design without one, so this is always set here.
      val validationToken = checkNotNull(design.validationToken)
      val face =
        try {
          when (plan) {
            is InstallPlan.AddToFreeSlot -> pusher.add(file, validationToken)
            is InstallPlan.UpdateInstalled -> pusher.update(plan.slot.slotId, file, validationToken)
            is InstallPlan.ReplaceOther -> pusher.update(plan.slot.slotId, file, validationToken)
            is InstallPlan.Blocked -> error("unreachable")
          }
        } catch (e: PushFailure) {
          log("push design=${design.id} rejected kind=${e.kind} message=${e.message}")
          report(
            deviceToken,
            InstallEvent(
              design.id,
              design.version,
              "failed",
              e.kind.name,
              packageName = design.packageName,
            ),
          )
          return InstallResult.PushRejected(e.kind, e.message)
        }

      log("push design=${design.id} installed slot=${face.slotId} package=${face.packageName}")
      val activation =
        if (activate && pusher.canSetActive()) {
          try {
            pusher.setActive(face.slotId)
            Activation.Active
          } catch (e: PushFailure) {
            Activation.Manual(e.kind)
          }
        } else Activation.NotRequested
      log("activate design=${design.id} activation=$activation")
      // Sent once activation has finished, so the one event carries its outcome.
      val (outcome, activationError) = activation.wire(requested = activate)
      report(
        deviceToken,
        InstallEvent(
          design.id,
          design.version,
          when (plan) {
            is InstallPlan.AddToFreeSlot -> "installed"
            is InstallPlan.UpdateInstalled -> "updated"
            else -> "replaced"
          },
          packageName = face.packageName,
          activation = outcome,
          activationError = activationError,
        ),
      )
      return InstallResult.Success(plan, face, activation)
    } finally {
      file.delete()
    }
  }

  /**
   * Makes an installed [face] of design [designId] active (after the user granted the permission)
   * and reports the outcome as a second, `activation` event. Never throws for a refusal.
   */
  suspend fun activate(deviceToken: String?, designId: String, face: InstalledFace): Activation {
    val activation =
      try {
        pusher.setActive(face.slotId)
        Activation.Active
      } catch (e: PushFailure) {
        Activation.Manual(e.kind)
      }
    log("activate design=$designId activation=$activation")
    val (outcome, activationError) = activation.wire(requested = true)
    report(
      deviceToken,
      InstallEvent(
        designId,
        face.versionCode,
        "activation",
        packageName = face.packageName,
        activation = outcome,
        activationError = activationError,
      ),
    )
    return activation
  }

  /** Best effort, like [PushApi.reportInstall]: telemetry never fails an install. */
  private suspend fun report(deviceToken: String?, event: InstallEvent) {
    try {
      api.reportInstall(deviceToken, device.stamp(event))
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (e: Exception) {
      log("report design=${event.designId} failed: $e")
    }
  }
}
