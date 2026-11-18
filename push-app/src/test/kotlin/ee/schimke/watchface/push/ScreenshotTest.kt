package ee.schimke.watchface.push

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import ee.schimke.watchface.push.data.FakePushServer
import ee.schimke.watchface.push.data.PreviewArt
import ee.schimke.watchface.push.link.LinkState
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the Wear screens on a round 227dp display. Always asserts the screens compose; writes
 * PNGs to `-PscreenshotDir=<dir>` when given (used for the PR screenshots), and the square Play
 * Store shots to `-PplayScreenshotDir=<dir>`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w227dp-h227dp-round-xhdpi")
class ScreenshotTest {
  @get:Rule val rule = createComposeRule()

  companion object {
    // Tall viewport so the whole scrolling column is visible in one image.
    const val TALL = "w227dp-h560dp-round-xhdpi"
  }

  private val server = FakePushServer(FAKE_HOST, previewPng = PreviewArt::render)
  private val designs = server.designs
  private val aurora = designs[0]

  private fun shot(
    name: String,
    dirProperty: String = "screenshotDir",
    content: @Composable () -> Unit,
  ) {
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
    repeat(5) {
      rule.waitForIdle()
      Thread.sleep(100)
    }
    val dir = System.getProperty(dirProperty).orEmpty()
    if (dir.isNotEmpty()) {
      File(dir).mkdirs()
      File(dir, "$name.png").outputStream().use {
        rule
          .onRoot()
          .captureToImage()
          .asAndroidBitmap()
          .compress(
            android.graphics.Bitmap.CompressFormat.PNG,
            100,
            it,
          )
      }
    }
  }

  private val singleSlotFull =
    PushState(listOf(InstalledFace("slot1", designs[1].packageName, 1)), remainingSlots = 0)

  @Config(qualifiers = "w227dp-h340dp-round-xhdpi")
  @Test
  fun link() =
    shot("link") {
      LinkScreen(
        LinkState.Waiting("AB7K-92QX", "https://ui.coo.ee/link?code=AB7K-92QX"),
        server = "https://ui.coo.ee",
        onRetry = {},
        onChangeServer = {},
      )
    }

  @Config(qualifiers = "w227dp-h400dp-round-xhdpi")
  @Test
  fun server() =
    shot("server") {
      ServerScreen(
        current = "https://faces.example.org",
        presets = listOf("https://ui.coo.ee", "https://preview.coo.ee"),
        onSelect = { true },
        onDone = {},
      )
    }

  @Config(qualifiers = TALL)
  @Test
  fun list() =
    shot("list") {
      DesignListScreen(
        state = ee.schimke.watchface.push.DesignsState.Ready(designs, singleSlotFull),
        accountName = "demo@example.com",
        server = "https://ui.coo.ee",
        deviceWff = 4,
        pushSupported = true,
        onOpen = {},
        onRefresh = {},
        onUnlink = {},
      )
    }

  @Config(qualifiers = TALL)
  @Test
  fun detailReplacesSingleSlot() =
    shot("detail-replace") {
      DesignDetailScreen(
        design = aurora,
        push = singleSlotFull,
        deviceWff = 4,
        pushSupported = true,
        install = InstallUi.Idle,
        onInstall = {},
        onRequestActivation = {},
        onDismissResult = {},
      )
    }

  @Config(qualifiers = TALL)
  @Test
  fun detailNeedsNewerWff() =
    shot("detail-wff") {
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

  // Play Store Wear screenshots must be square (1:1, at least 384px). These use the class's
  // 227dp round display (454px at xhdpi), so each shows the screen's first viewport. Play orders
  // the listing's screenshots by file name.
  private fun playShot(name: String, content: @Composable () -> Unit) =
    shot(name, dirProperty = "playScreenshotDir", content = content)

  @Test
  fun playList() =
    playShot("1-list") {
      DesignListScreen(
        state = ee.schimke.watchface.push.DesignsState.Ready(designs, singleSlotFull),
        accountName = "demo@example.com",
        server = "https://ui.coo.ee",
        deviceWff = 4,
        pushSupported = true,
        onOpen = {},
        onRefresh = {},
        onUnlink = {},
      )
    }

  @Test
  fun playDetail() =
    playShot("2-detail") {
      DesignDetailScreen(
        design = aurora,
        push = PushState(emptyList(), remainingSlots = 1),
        deviceWff = 4,
        pushSupported = true,
        install = InstallUi.Idle,
        onInstall = {},
        onRequestActivation = {},
        onDismissResult = {},
      )
    }

  @Test
  fun playLink() =
    playShot("3-link") {
      LinkScreen(
        LinkState.Waiting("AB7K-92QX", "https://ui.coo.ee/link?code=AB7K-92QX"),
        server = "https://ui.coo.ee",
        onRetry = {},
        onChangeServer = {},
      )
    }
}
