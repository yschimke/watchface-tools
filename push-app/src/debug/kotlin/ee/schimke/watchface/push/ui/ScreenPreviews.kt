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
}

/** The app theme, with previews drawn locally instead of loaded by Coil. */
@Composable
private fun Watch(content: @Composable () -> Unit) {
  val painters = remember {
    Fixtures.designs.associate {
      val bytes = PreviewArt.render(it.title)
      it.id to BitmapPainter(BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap())
    }
  }
  CompositionLocalProvider(LocalPreviewPainter provides { painters.getValue(it.id) }) {
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
