package ee.schimke.watchface.push

import ee.schimke.watchface.push.push.Activation
import ee.schimke.watchface.push.push.BlockReason
import ee.schimke.watchface.push.push.InstallFlow
import ee.schimke.watchface.push.push.InstallPlan
import ee.schimke.watchface.push.push.InstallPlanner
import ee.schimke.watchface.push.push.InstallResult
import ee.schimke.watchface.push.push.PushFailure
import ee.schimke.watchface.push.push.PushFailureKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class InstallFlowTest {
  private val cache =
    File.createTempFile("cache", "").let { f ->
      f.delete()
      f.mkdirs()
      f.also { it.deleteOnExit() }
    }
  private val pair = fakeServerApi()
  private val server = pair.first
  private val api = pair.second
  private val aurora = server.designs.first { it.id == "aurora" }
  private val pulse = server.designs.first { it.id == "pulse" }
  private val future = server.designs.first { it.id == "future" }

  private fun flow(pusher: FakePusher) = InstallFlow(api, pusher, cache, 36)

  @Test
  fun installsIntoFreeSlotAndReportsTelemetry() = runTest {
    val pusher = FakePusher(slotLimit = 2)
    val result = flow(pusher).install(server.deviceToken, aurora)

    val success = assertIs<InstallResult.Success>(result)
    assertEquals(InstallPlan.AddToFreeSlot, success.plan)
    assertEquals(aurora.packageName, success.face.packageName)
    assertEquals(1, pusher.pushedBytes.size)
    assertEquals(listOf("installed"), server.installs.map { it.result })
    assertTrue(cache.listFiles().orEmpty().isEmpty(), "temp APK must be deleted")
  }

  @Test
  fun singleSlotNeedsConfirmationThenReplaces() = runTest {
    val pusher = FakePusher(slotLimit = 1)
    flow(pusher).install(server.deviceToken, aurora)

    val unconfirmed = flow(pusher).install(server.deviceToken, pulse)
    assertIs<InstallResult.NeedsReplaceConfirmation>(unconfirmed)
    assertEquals(listOf(aurora.packageName), pusher.slots.map { it.packageName })

    val confirmed = flow(pusher).install(server.deviceToken, pulse, confirmReplace = true)
    val success = assertIs<InstallResult.Success>(confirmed)
    assertIs<InstallPlan.ReplaceOther>(success.plan)
    assertEquals(listOf(pulse.packageName), pusher.slots.map { it.packageName })
    assertEquals(1, pusher.slots.size)
  }

  @Test
  fun reinstallingSamePackageUpdatesWithoutConfirmation() = runTest {
    val pusher = FakePusher(slotLimit = 1)
    flow(pusher).install(server.deviceToken, aurora)
    val result = flow(pusher).install(server.deviceToken, aurora)
    assertIs<InstallPlan.UpdateInstalled>(assertIs<InstallResult.Success>(result).plan)
  }

  @Test
  fun refusesDesignNeedingNewerWff() = runTest {
    val pusher = FakePusher(wffVersion = 4)
    val result = flow(pusher).install(server.deviceToken, future)
    assertEquals(InstallResult.Blocked(BlockReason.WFF_TOO_NEW), result)
    assertTrue(pusher.pushedBytes.isEmpty())
    assertTrue(server.installs.isEmpty())
  }

  @Test
  fun refusesWhenPushIsUnsupported() = runTest {
    val result = flow(FakePusher(isSupported = false)).install(server.deviceToken, aurora)
    assertEquals(InstallResult.Blocked(BlockReason.PUSH_UNSUPPORTED), result)
  }

  @Test
  fun refusesPackageOutsideMarketplacePrefix() = runTest {
    val bad = aurora.copy(packageName = "com.evil.watchfacepush.aurora")
    val result = flow(FakePusher()).install(server.deviceToken, bad)
    assertEquals(InstallResult.Blocked(BlockReason.BAD_PACKAGE_NAME), result)
  }

  @Test
  fun checksumMismatchNeverReachesThePushApi() = runTest {
    server.tamperApk = true
    val pusher = FakePusher()
    val result = flow(pusher).install(server.deviceToken, aurora)
    assertEquals(InstallResult.ChecksumMismatch, result)
    assertTrue(pusher.pushedBytes.isEmpty())
    assertTrue(cache.listFiles().orEmpty().isEmpty())
  }

  @Test
  fun badTokenIsReportedAsUnauthorizedDownload() = runTest {
    val result = flow(FakePusher()).install("wrong-token", aurora)
    assertEquals(InstallResult.DownloadFailed(true, "HTTP 401"), result)
  }

  @Test
  fun publicDesignInstallsWithoutATokenAndReportsNothing() = runTest {
    val pusher = FakePusher()
    val result = flow(pusher).install(null, aurora)
    assertIs<InstallResult.Success>(result)
    assertEquals(listOf(aurora.packageName), pusher.slots.map { it.packageName })
    assertTrue(server.installs.isEmpty(), "anonymous installs send no telemetry")
  }

  @Test
  fun privateDesignNeedsALinkedWatch() = runTest {
    val pusher = FakePusher()
    val result = flow(pusher).install(null, pulse)
    assertEquals(InstallResult.DownloadFailed(true, "HTTP 401", anonymous = true), result)
    assertTrue(pusher.pushedBytes.isEmpty())
  }

  @Test
  fun pushRejectionIsMappedAndReported() = runTest {
    val pusher = FakePusher()
    pusher.nextFailure = PushFailure(PushFailureKind.INVALID_VALIDATION_TOKEN)
    val result = flow(pusher).install(server.deviceToken, aurora)
    assertEquals(
      InstallResult.PushRejected(
        PushFailureKind.INVALID_VALIDATION_TOKEN,
        "INVALID_VALIDATION_TOKEN",
      ),
      result,
    )
    assertEquals(listOf("failed"), server.installs.map { it.result })
  }

  @Test
  fun activatesWhenPermissionGrantedOtherwiseLeavesToUser() = runTest {
    val granted = FakePusher(permissionGranted = true)
    val withPermission = flow(granted).install(server.deviceToken, aurora, activate = true)
    assertEquals(Activation.Active, assertIs<InstallResult.Success>(withPermission).activation)
    assertEquals("slot1", granted.activeSlot)

    val denied = FakePusher(permissionGranted = false)
    val without = flow(denied).install(server.deviceToken, aurora, activate = true)
    assertEquals(Activation.NotRequested, assertIs<InstallResult.Success>(without).activation)
  }

  @Test
  fun activationRefusedFallsBackToManual() = runTest {
    // Install works, but setWatchFaceAsActive is refused (already used once, or another maker's
    // face became active in the meantime).
    val pusher = FakePusher()
    pusher.activationFailure = PushFailure(PushFailureKind.MAX_ACTIVATION_ATTEMPTS)
    val result = flow(pusher).install(server.deviceToken, aurora, activate = true)
    assertEquals(
      Activation.Manual(PushFailureKind.MAX_ACTIVATION_ATTEMPTS),
      assertIs<InstallResult.Success>(result).activation,
    )
  }

  @Test
  fun plannerPrefersFreeSlotsAndUpdates() {
    val installed = ee.schimke.watchface.push.push.InstalledFace("s", aurora.packageName, 1)
    val full = ee.schimke.watchface.push.push.PushState(listOf(installed), 0)
    val roomy = ee.schimke.watchface.push.push.PushState(listOf(installed), 1)
    assertTrue(InstallPlanner.hasUpdate(aurora, full)) // installed v1 < design v3
    assertIs<InstallPlan.UpdateInstalled>(InstallPlanner.plan(aurora, full, true, 4))
    assertIs<InstallPlan.ReplaceOther>(InstallPlanner.plan(pulse, full, true, 4))
    assertEquals(InstallPlan.AddToFreeSlot, InstallPlanner.plan(pulse, roomy, true, 4))
  }
}
