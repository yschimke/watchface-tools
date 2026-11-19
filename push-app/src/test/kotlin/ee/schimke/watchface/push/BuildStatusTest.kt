package ee.schimke.watchface.push

import ee.schimke.watchface.push.data.BuildStatus
import ee.schimke.watchface.push.data.DefaultJson
import ee.schimke.watchface.push.data.DesignList
import ee.schimke.watchface.push.data.FakePushServer
import ee.schimke.watchface.push.push.BlockReason
import ee.schimke.watchface.push.push.InstallFlow
import ee.schimke.watchface.push.push.InstallPlan
import ee.schimke.watchface.push.push.InstallPlanner
import ee.schimke.watchface.push.push.InstallResult
import ee.schimke.watchface.push.push.InstalledFace
import ee.schimke.watchface.push.push.PushState
import ee.schimke.watchface.push.ui.Messages
import ee.schimke.watchface.push.ui.statusLabel
import ee.schimke.watchface.push.ui.versionLine
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** The server's background builds: decoding, labels, and never installing what isn't built. */
class BuildStatusTest {
  private val now = 1_800_000_000_000L
  private val server = FakePushServer(FAKE_HOST)
  private val aurora = server.designs.first { it.id == "aurora" }
  private val free = PushState(emptyList(), 1)

  @Test
  fun decodesAnUnbuiltEntryAndTreatsAMissingBuildAsBuilt() {
    val json =
      """{"designs":[
        {"id":"comet","title":"Comet","previewUrl":"https://x/p.png","wffVersion":2,
         "packageName":"ee.schimke.watchface.push.watchfacepush.comet",
         "build":{"state":"pending","scheduledAtEpochMillis":$now,"future":"ignored"}},
        {"id":"old","title":"Old","previewUrl":"https://x/o.png","version":3,"wffVersion":1,
         "packageName":"ee.schimke.watchface.push.watchfacepush.old","apkUrl":"https://x/a.apk",
         "sha256":"ab","validationToken":"t"}]}"""
    val (comet, old) = DefaultJson.decodeFromString<DesignList>(json).designs
    assertFalse(comet.isInstallable)
    assertTrue(comet.isBuilding)
    assertEquals(0, comet.version)
    assertTrue(old.isInstallable)
    assertNull(old.build)
    assertFalse(old.isBuilding, "an older server's entry, with no build, is built")
  }

  @Test
  fun labelsEveryBuildState() {
    fun label(b: BuildStatus) = Messages.build(b, now)
    assertEquals("Builds in 3 min", label(BuildStatus(BuildStatus.PENDING, now + 150_000)))
    assertEquals("Building soon", label(BuildStatus(BuildStatus.PENDING, now - 1)))
    assertEquals("Build pending", label(BuildStatus(BuildStatus.PENDING)))
    assertEquals("Queued (2 ahead)", label(BuildStatus(BuildStatus.QUEUED, queueAhead = 2)))
    assertEquals("Queued", label(BuildStatus(BuildStatus.QUEUED, queueAhead = 0)))
    assertEquals(
      "Building… validating",
      label(BuildStatus(BuildStatus.BUILDING, stage = "validating")),
    )
    assertEquals("Building…", label(BuildStatus(BuildStatus.BUILDING)))
    assertEquals("Building…", label(BuildStatus("linting")), "an unknown state reads as building")
    assertTrue(BuildStatus("linting").inProgress)
    assertEquals(
      "Build failed: no case",
      label(BuildStatus(BuildStatus.FAILED, reason = "no case")),
    )
    assertEquals("Build refused", label(BuildStatus(BuildStatus.REFUSED)))
    assertFalse(BuildStatus(BuildStatus.FAILED).inProgress)
  }

  @Test
  fun anUnbuiltFaceIsShownByItsBuildAndCannotBeInstalled() {
    val comet =
      server.startBuild(
        "comet",
        "Comet",
        BuildStatus(BuildStatus.QUEUED, queueAhead = 1, version = 1),
      )
    assertEquals("Queued (1 ahead)", statusLabel(comet, free, deviceWff = 4, nowMillis = now))
    assertEquals(
      InstallPlan.Blocked(BlockReason.NOT_BUILT),
      InstallPlanner.plan(comet, free, supported = true, wffVersion = 4),
    )
    assertEquals("Version 1 · requires WFF v1", versionLine(comet), "names the version being built")
  }

  @Test
  fun installingAnUnbuiltFaceNeverDownloads() = runTest {
    val (fake, api) = fakeServerApi(server)
    val comet = fake.startBuild("comet", "Comet")
    val cache = kotlin.io.path.createTempDirectory("cache").toFile()
    val pusher = FakePusher()
    val result =
      InstallFlow(api, pusher, cache, ee.schimke.watchface.push.data.DeviceInfo(36))
        .install(fake.deviceToken, comet)
    assertEquals(InstallResult.Blocked(BlockReason.NOT_BUILT), result)
    assertTrue(pusher.pushedBytes.isEmpty())
    assertTrue(cache.listFiles().orEmpty().isEmpty())
  }

  @Test
  fun aFinishedBuildInstalls() = runTest {
    val (fake, api) = fakeServerApi(server)
    fake.startBuild("comet", "Comet")
    val built = fake.finishBuild("comet")
    assertTrue(built.isInstallable)
    assertFalse(built.isBuilding)
    val pusher = FakePusher()
    val result =
      InstallFlow(
          api,
          pusher,
          File(System.getProperty("java.io.tmpdir")),
          ee.schimke.watchface.push.data.DeviceInfo(36),
        )
        .install(null, built)
    assertIs<InstallResult.Success>(result, "listed to everyone, so anonymous installs work")
  }

  @Test
  fun anInstalledFaceShowsUpdatesOnlyForBuiltVersions() {
    val installedV3 = PushState(listOf(InstalledFace("s", aurora.packageName, 3)), 0)
    val installedV2 = PushState(listOf(InstalledFace("s", aurora.packageName, 2)), 0)
    // v3 built and installed, v4 building: nothing to update to yet, but the build shows.
    val next = aurora.copy(build = BuildStatus(BuildStatus.BUILDING, version = 4))
    assertEquals("Installed · Building…", statusLabel(next, installedV3, 4, now))
    assertFalse(InstallPlanner.hasUpdate(next, installedV3))
    // A newer version is built: that is an update, whatever is building after it.
    assertEquals("Update available", statusLabel(next, installedV2, 4, now))
    assertEquals("Update available", statusLabel(aurora, installedV2, 4, now))
    // v4's build failed: v3 stays installed and installable, and the failure shows.
    val failed =
      aurora.copy(build = BuildStatus(BuildStatus.FAILED, version = 4, reason = "no case"))
    assertEquals("Installed · Build failed: no case", statusLabel(failed, installedV3, 4, now))
    assertTrue(failed.isInstallable)
  }

  @Test
  fun theListingAsksForPendingFacesAndAnOlderRequestDoesNotSeeThem() = runTest {
    val (fake, api) = fakeServerApi(server)
    fake.startBuild("comet", "Comet")
    assertTrue(api.listDesigns(null).any { it.id == "comet" }, "the app sends includePending=1")

    // A request without it (an older app) never sees an unbuilt entry it cannot parse.
    val raw =
      okhttp3.OkHttpClient.Builder()
        .addInterceptor(fake)
        .build()
        .newCall(
          okhttp3.Request.Builder().url("https://$FAKE_HOST/api/watchface-push/v1/designs").build(),
        )
        .execute()
        .use { it.body.string() }
    assertFalse("comet" in raw, raw)
  }

  @Test
  fun anUnbuiltEntryWithoutAPreviewDecodes() {
    val json =
      """{"designs":[{"id":"x","title":"X","wffVersion":1,"packageName":"p.watchfacepush.x",
        "visibility":"private","source":"design",
        "build":{"state":"failed","reason":"the packager refused it","automatic":true,
          "diagnostics":[{"code":"wff"}]}}]}"""
    val x = DefaultJson.decodeFromString<DesignList>(json).designs.single()
    assertNull(x.previewUrl)
    assertEquals("Build failed: the packager refused it", Messages.build(x.build!!, now))
  }
}
