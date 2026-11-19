package ee.schimke.watchface.push.ui

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.ui.tooling.preview.WearPreviewLargeRound
import androidx.wear.compose.ui.tooling.preview.WearPreviewSmallRound
import ee.schimke.composeai.preview.ScrollMode
import ee.schimke.composeai.preview.ScrollingPreview
import ee.schimke.watchface.push.DesignsState
import ee.schimke.watchface.push.InstallUi
import ee.schimke.watchface.push.data.BuildStatus
import ee.schimke.watchface.push.data.Design
import ee.schimke.watchface.push.data.FakePushServer
import ee.schimke.watchface.push.data.PreviewArt
import ee.schimke.watchface.push.link.LinkState
import ee.schimke.watchface.push.push.Activation
import ee.schimke.watchface.push.push.InstallPlan
import ee.schimke.watchface.push.push.InstallResult
import ee.schimke.watchface.push.push.InstalledFace
import ee.schimke.watchface.push.push.PushFailureKind
import ee.schimke.watchface.push.push.PushState

/*
 * Every push-app screen and its important states, on small and large round watches, rendered by
 * compose-preview on each PR (.github/workflows/compose-preview.yml) and diffed against main.
 * Scrolling screens also have a `…Long` preview: one tall capture of the whole list. The plain
 * previews are the first frame, so a screen that opens without its title shows here.
 *
 * Data comes from the debug build's FakePushServer and PreviewArt: no network.
 */

private object Fixtures {
  val server = FakePushServer("faces.example", previewPng = PreviewArt::render)
  val designs: List<Design> = server.designs
  val aurora: Design = designs.first { it.id == "aurora" }
  val newerWff: Design = designs.first { it.wffVersion > DEVICE_WFF }
  val catalogFace: Design = designs.first { it.isCatalog }
  val publicDesigns: List<Design> = server.publicDesigns
  val oneSlotFull = PushState(listOf(InstalledFace("slot1", designs[1].packageName, 1)), 0)
  val freeSlot = PushState(emptyList(), remainingSlots = 1)
  const val DEVICE_WFF = 4
  const val SERVER = "https://ui.coo.ee"

  /** The previews' clock: "Builds in N min" must render the same on every run. */
  const val NOW = 1_800_000_000_000L

  /** A design the server lists before any version is built: no APK, token or checksum. */
  private fun unbuilt(id: String, title: String, build: BuildStatus) =
    aurora.copy(
      id = id,
      title = title,
      description = "Published a moment ago; the server is building it.",
      version = 0,
      versionName = null,
      packageName = "${aurora.packageName}_$id",
      apkUrl = null,
      sha256 = null,
      validationToken = null,
      build = build,
    )

  val pending = unbuilt("comet", "Comet", BuildStatus(BuildStatus.PENDING, NOW + 3 * 60_000))
  val queued = unbuilt("drift", "Drift", BuildStatus(BuildStatus.QUEUED, queueAhead = 2))
  val building =
    unbuilt("ember", "Ember", BuildStatus(BuildStatus.BUILDING, stage = "validating", version = 1))
  val failed =
    unbuilt(
      "flare",
      "Flare",
      BuildStatus(BuildStatus.FAILED, message = "Asset case_steel is missing"),
    )

  /** Installed at v3 while the server builds v4: still installable, the build is shown too. */
  val auroraBuildingNext =
    aurora.copy(build = BuildStatus(BuildStatus.BUILDING, version = aurora.version + 1))
  val auroraInstalled = PushState(listOf(InstalledFace("slot1", aurora.packageName, 3)), 0)
  val auroraOutdated = PushState(listOf(InstalledFace("slot1", aurora.packageName, 2)), 0)

  val buildStates: List<Design> = listOf(pending, queued, building, failed, auroraBuildingNext)
  val previewed: List<Design> = designs + buildStates
}

/** The app theme, with previews drawn locally instead of loaded by Coil. */
@Composable
private fun Watch(content: @Composable () -> Unit) {
  val painters = remember {
    Fixtures.previewed.associate {
      val bytes = PreviewArt.render(it.title)
      it.id to BitmapPainter(BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap())
    }
  }
  CompositionLocalProvider(
    LocalPreviewPainter provides { painters.getValue(it.id) },
    LocalNow provides { Fixtures.NOW },
  ) {
    MaterialTheme { AppScaffold { content() } }
  }
}

// Design list

@Composable
private fun DesignList(state: DesignsState, linked: Boolean) = Watch {
  DesignListScreen(
    state = state,
    accountName = if (linked) "demo@example.com" else null,
    server = Fixtures.SERVER,
    deviceWff = Fixtures.DEVICE_WFF,
    pushSupported = true,
    onOpen = {},
    onRefresh = {},
    onUnlink = {},
    linked = linked,
  )
}

@WearPreviewSmallRound
@WearPreviewLargeRound
@Composable
fun PublicListPreview() =
  DesignList(DesignsState.Ready(Fixtures.publicDesigns, Fixtures.oneSlotFull), linked = false)

@WearPreviewLargeRound
@ScrollingPreview(modes = [ScrollMode.LONG])
@Composable
fun PublicListLongPreview() =
  DesignList(DesignsState.Ready(Fixtures.publicDesigns, Fixtures.oneSlotFull), linked = false)

@WearPreviewSmallRound
@WearPreviewLargeRound
@Composable
fun LinkedListPreview() =
  DesignList(DesignsState.Ready(Fixtures.designs, Fixtures.oneSlotFull), linked = true)

@WearPreviewLargeRound
@ScrollingPreview(modes = [ScrollMode.LONG])
@Composable
fun LinkedListLongPreview() =
  DesignList(DesignsState.Ready(Fixtures.designs, Fixtures.oneSlotFull), linked = true)

@WearPreviewSmallRound
@WearPreviewLargeRound
@Composable
fun LinkedListEmptyPreview() =
  DesignList(DesignsState.Ready(emptyList(), Fixtures.oneSlotFull), linked = true)

@WearPreviewSmallRound
@WearPreviewLargeRound
@Composable
fun ListLoadingPreview() = DesignList(DesignsState.Loading, linked = false)

@WearPreviewSmallRound
@WearPreviewLargeRound
@Composable
fun ListErrorPreview() =
  DesignList(
    DesignsState.Error("This server has no public faces. Sign in for your designs."),
    linked = false,
  )

/**
 * Every build state in one list: pending, queued, building, failed, and an installed face whose
 * next version is building.
 */
@WearPreviewLargeRound
@ScrollingPreview(modes = [ScrollMode.LONG])
@Composable
fun BuildStatesListLongPreview() =
  DesignList(DesignsState.Ready(Fixtures.buildStates, Fixtures.auroraInstalled), linked = true)

@WearPreviewSmallRound
@WearPreviewLargeRound
@Composable
fun BuildStatesListPreview() =
  DesignList(DesignsState.Ready(Fixtures.buildStates, Fixtures.auroraInstalled), linked = true)

// Sign in

@Composable
private fun Link(state: LinkState) = Watch {
  LinkScreen(state, server = Fixtures.SERVER, onRetry = {}, onChangeServer = {})
}

private val waiting = LinkState.Waiting("AB7K-92QX", "https://ui.coo.ee/link?code=AB7K-92QX")

@WearPreviewSmallRound @WearPreviewLargeRound @Composable fun LinkCodePreview() = Link(waiting)

@WearPreviewLargeRound
@ScrollingPreview(modes = [ScrollMode.LONG])
@Composable
fun LinkCodeLongPreview() = Link(waiting)

@WearPreviewLargeRound @Composable fun LinkExpiredPreview() = Link(LinkState.Expired)

// Server

@Composable
private fun Server() = Watch {
  ServerScreen(
    current = "https://faces.example.org",
    presets = listOf(Fixtures.SERVER, "https://preview.coo.ee"),
    onSelect = { true },
    onDone = {},
  )
}

@WearPreviewSmallRound @WearPreviewLargeRound @Composable fun ServerPreview() = Server()

@WearPreviewLargeRound
@ScrollingPreview(modes = [ScrollMode.LONG])
@Composable
fun ServerLongPreview() = Server()

// Design detail

@Composable
private fun Detail(
  design: Design,
  install: InstallUi = InstallUi.Idle,
  push: PushState = Fixtures.freeSlot,
) = Watch {
  DesignDetailScreen(
    design = design,
    push = push,
    deviceWff = Fixtures.DEVICE_WFF,
    pushSupported = true,
    install = install,
    onInstall = {},
    onRequestActivation = {},
    onDismissResult = {},
  )
}

@WearPreviewSmallRound
@WearPreviewLargeRound
@Composable
fun DetailDesignPreview() = Detail(Fixtures.aurora, push = Fixtures.oneSlotFull)

@WearPreviewLargeRound
@ScrollingPreview(modes = [ScrollMode.LONG])
@Composable
fun DetailDesignLongPreview() = Detail(Fixtures.aurora, push = Fixtures.oneSlotFull)

@WearPreviewSmallRound
@WearPreviewLargeRound
@Composable
fun DetailCatalogFacePreview() = Detail(Fixtures.catalogFace)

@WearPreviewLargeRound
@ScrollingPreview(modes = [ScrollMode.LONG])
@Composable
fun DetailCatalogFaceLongPreview() = Detail(Fixtures.catalogFace)

@WearPreviewLargeRound
@ScrollingPreview(modes = [ScrollMode.LONG])
@Composable
fun InstallBlockedPreview() = Detail(Fixtures.newerWff)

@WearPreviewLargeRound
@ScrollingPreview(modes = [ScrollMode.LONG])
@Composable
fun InstallProgressPreview() = Detail(Fixtures.aurora, InstallUi.Working(Fixtures.aurora.id))

@WearPreviewLargeRound
@ScrollingPreview(modes = [ScrollMode.LONG])
@Composable
fun InstallSuccessPreview() =
  Detail(
    Fixtures.aurora,
    InstallUi.Finished(
      Fixtures.aurora.id,
      InstallResult.Success(
        InstallPlan.AddToFreeSlot,
        InstalledFace("slot1", Fixtures.aurora.packageName, Fixtures.aurora.version),
        Activation.NotRequested,
      ),
    ),
  )

@WearPreviewLargeRound
@ScrollingPreview(modes = [ScrollMode.LONG])
@Composable
fun InstallRejectedPreview() =
  Detail(
    Fixtures.aurora,
    InstallUi.Finished(
      Fixtures.aurora.id,
      InstallResult.PushRejected(PushFailureKind.MALFORMED_APK, "The APK is malformed."),
    ),
  )

// Builds

@WearPreviewLargeRound
@ScrollingPreview(modes = [ScrollMode.LONG])
@Composable
fun DetailBuildPendingPreview() = Detail(Fixtures.pending)

@WearPreviewLargeRound
@ScrollingPreview(modes = [ScrollMode.LONG])
@Composable
fun DetailBuildQueuedPreview() = Detail(Fixtures.queued)

@WearPreviewSmallRound
@WearPreviewLargeRound
@Composable
fun DetailBuildingPreview() = Detail(Fixtures.building)

@WearPreviewLargeRound
@ScrollingPreview(modes = [ScrollMode.LONG])
@Composable
fun DetailBuildFailedPreview() = Detail(Fixtures.failed)

@WearPreviewLargeRound
@ScrollingPreview(modes = [ScrollMode.LONG])
@Composable
fun DetailInstalledNextBuildingPreview() =
  Detail(Fixtures.auroraBuildingNext, push = Fixtures.auroraInstalled)

@WearPreviewSmallRound
@WearPreviewLargeRound
@Composable
fun DetailUpdateAvailablePreview() = Detail(Fixtures.aurora, push = Fixtures.auroraOutdated)
