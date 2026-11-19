package ee.schimke.watchface.push

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import ee.schimke.watchface.push.data.FakePushServer
import ee.schimke.watchface.push.data.PreviewArt
import ee.schimke.watchface.push.link.LinkState
import ee.schimke.watchface.push.push.InstallResult
import ee.schimke.watchface.push.push.InstalledFace
import ee.schimke.watchface.push.push.PushState
import ee.schimke.watchface.push.ui.DesignDetailScreen
import ee.schimke.watchface.push.ui.DesignListScreen
import ee.schimke.watchface.push.ui.LinkScreen
import ee.schimke.watchface.push.ui.LocalPreviewPainter
import ee.schimke.watchface.push.ui.ServerScreen
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every screen's first frame on a small (192dp) and a large (227dp) round watch, as the app shows
 * it: lists first lay out while loading, then their content arrives. Always composes each screen;
 * writes `<screenshotDir>/gallery/<size>/<screen>.png` when `-PscreenshotDir` is given (the PR's
 * before/after table).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ScreenGalleryTest(private val size: String) {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    fun sizes() = listOf(arrayOf<Any>("small"), arrayOf<Any>("large"))
  }

  @get:Rule val rule = createComposeRule()

  private val server = FakePushServer(FAKE_HOST, previewPng = PreviewArt::render)
  private val designs = server.designs
  private val singleSlotFull =
    PushState(listOf(InstalledFace("slot1", designs[1].packageName, 1)), remainingSlots = 0)

  private fun shot(
    name: String,
    scroll: Boolean = false,
    loaded: (() -> Unit)? = null,
    content: @Composable () -> Unit,
  ) {
    RuntimeEnvironment.setQualifiers(
      if (size == "small") "w192dp-h192dp-round-xhdpi" else "w227dp-h227dp-round-xhdpi",
    )
    rule.setContent {
      val painters = remember {
        designs.associate {
          val bytes = PreviewArt.render(it.title)
          it.id to
            BitmapPainter(BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap())
        }
      }
      CompositionLocalProvider(LocalPreviewPainter provides { painters.getValue(it.id) }) {
        MaterialTheme { AppScaffold { content() } }
      }
    }
    rule.waitForIdle()
    // The content arrives after the first layout, as it does from the network.
    loaded?.invoke()
    if (scroll) rule.onNode(hasScrollAction()).performTouchInput { swipeUp(durationMillis = 600) }
    repeat(5) {
      rule.waitForIdle()
      Thread.sleep(100)
    }
    val dir = System.getProperty("screenshotDir").orEmpty()
    if (dir.isEmpty()) return
    val out = File(dir, "gallery/$size").apply { mkdirs() }
    File(out, "$name.png").outputStream().use {
      rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
    }
  }

  @Composable
  private fun List(state: MutableState<DesignsState>, linked: Boolean) =
    DesignListScreen(
      state = state.value,
      accountName = if (linked) "demo@example.com" else null,
      server = "https://ui.coo.ee",
      deviceWff = 4,
      pushSupported = true,
      onOpen = {},
      onRefresh = {},
      onUnlink = {},
      linked = linked,
    )

  private fun list(name: String, linked: Boolean, ready: DesignsState, scroll: Boolean = false) {
    val state = mutableStateOf<DesignsState>(DesignsState.Loading)
    shot(name, scroll, loaded = { state.value = ready }) { List(state, linked) }
  }

  @Test
  fun publicList() =
    list("01-public-list", false, DesignsState.Ready(server.publicDesigns, singleSlotFull))

  @Test
  fun publicListScrolled() =
    list(
      "02-public-list-scrolled",
      false,
      DesignsState.Ready(server.publicDesigns, singleSlotFull),
      scroll = true,
    )

  @Test fun publicEmpty() = list("03-public-empty", false, DesignsState.Ready(emptyList(), null))

  @Test fun linkedList() = list("04-linked-list", true, DesignsState.Ready(designs, singleSlotFull))

  @Test
  fun linkedEmpty() = list("05-linked-empty", true, DesignsState.Ready(emptyList(), singleSlotFull))

  @Test
  fun listError() =
    list(
      "06-list-error",
      false,
      DesignsState.Error("This server has no public faces. Sign in for your designs."),
    )

  @Test
  fun listLoading() {
    val state = mutableStateOf<DesignsState>(DesignsState.Loading)
    shot("07-list-loading") { List(state, linked = false) }
  }

  @Test
  fun linkWaiting() {
    val state = mutableStateOf<LinkState>(LinkState.Requesting)
    shot(
      "08-link-code",
      loaded = {
        state.value = LinkState.Waiting("AB7K-92QX", "https://ui.coo.ee/link?code=AB7K-92QX")
      },
    ) {
      LinkScreen(state.value, server = "https://ui.coo.ee", onRetry = {}, onChangeServer = {})
    }
  }

  @Test
  fun linkExpired() {
    val state = mutableStateOf<LinkState>(LinkState.Requesting)
    shot("09-link-expired", loaded = { state.value = LinkState.Expired }) {
      LinkScreen(state.value, server = "https://ui.coo.ee", onRetry = {}, onChangeServer = {})
    }
  }

  @Test
  fun serverPicker() =
    shot("10-server") {
      ServerScreen(
        current = "https://faces.example.org",
        presets = listOf("https://ui.coo.ee", "https://preview.coo.ee"),
        onSelect = { true },
        onDone = {},
      )
    }

  private fun detail(name: String, install: InstallUi, push: PushState = singleSlotFull) =
    shot(name) {
      DesignDetailScreen(
        design = designs[0],
        push = push,
        deviceWff = 4,
        pushSupported = true,
        install = install,
        onInstall = {},
        onRequestActivation = {},
        onDismissResult = {},
      )
    }

  @Test fun detailReplace() = detail("11-detail-replace", InstallUi.Idle)

  @Test
  fun detailNeedsNewerWff() =
    shot("12-detail-needs-wff") {
      DesignDetailScreen(
        design = designs[2],
        push = singleSlotFull,
        deviceWff = 4,
        pushSupported = true,
        install = InstallUi.Idle,
        onInstall = {},
        onRequestActivation = {},
        onDismissResult = {},
      )
    }

  @Test
  fun detailInstalling() =
    detail("13-detail-installing", InstallUi.Working(designs[0].id), PushState(emptyList(), 1))

  @Test
  fun detailFailed() =
    detail(
      "14-detail-failed",
      InstallUi.Finished(designs[0].id, InstallResult.ChecksumMismatch),
      PushState(emptyList(), 1),
    )
}
