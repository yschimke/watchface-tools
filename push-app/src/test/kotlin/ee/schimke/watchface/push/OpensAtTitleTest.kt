package ee.schimke.watchface.push

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import ee.schimke.watchface.push.data.FakePushServer
import ee.schimke.watchface.push.link.LinkState
import ee.schimke.watchface.push.push.InstalledFace
import ee.schimke.watchface.push.push.PushState
import ee.schimke.watchface.push.ui.DesignDetailScreen
import ee.schimke.watchface.push.ui.DesignListScreen
import ee.schimke.watchface.push.ui.LinkScreen
import ee.schimke.watchface.push.ui.LocalPreviewPainter
import ee.schimke.watchface.push.ui.ServerScreen
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every screen opens with its title fully on the round display: below the time text and inside the
 * disc. The design list is checked across its Loading -> Ready switch, which used to open it
 * scrolled down to the buttons with the header off screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w227dp-h227dp-round-xhdpi")
class OpensAtTitleTest {
  @get:Rule val rule = createComposeRule()

  private val server = FakePushServer("fake.example")
  private val singleSlot =
    PushState(listOf(InstalledFace("slot1", server.designs[1].packageName, 1)), 0)

  private fun setScreen(content: @Composable () -> Unit) = rule.setContent {
    androidx.compose.runtime.CompositionLocalProvider(
      LocalPreviewPainter provides { ColorPainter(androidx.compose.ui.graphics.Color.Gray) },
    ) {
      MaterialTheme { AppScaffold { content() } }
    }
  }

  private fun assertTitleOnScreen(title: String) {
    rule.waitForIdle()
    val screen = rule.onRoot().fetchSemanticsNode().boundsInRoot
    val header = rule.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
    // Below the time text (the top ~10% of the screen), and in the top half: the list opened at
    // its start, not scrolled.
    assertTrue(header.top >= screen.height * 0.08f, "'$title' under the time text: $header")
    assertTrue(header.bottom <= screen.height * 0.5f, "'$title' not at the top: $header")
    assertInsideDisc(title, header, screen)
  }

  private fun assertInsideDisc(title: String, r: Rect, screen: Rect) {
    val cx = screen.width / 2
    val cy = screen.height / 2
    val radius = screen.width / 2
    for ((x, y) in
      listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom)) {
      val dx = x - cx
      val dy = y - cy
      assertTrue(dx * dx + dy * dy <= radius * radius, "'$title' clipped by the round screen: $r")
    }
  }

  @Test
  fun designListAfterLoading() {
    var state by mutableStateOf<DesignsState>(DesignsState.Loading)
    setScreen {
      DesignListScreen(
        state = state,
        accountName = "demo@example.com",
        server = "https://ui.coo.ee",
        deviceWff = 4,
        pushSupported = true,
        onOpen = {},
        onRefresh = {},
        onUnlink = {},
      )
    }
    rule.waitForIdle()
    state = DesignsState.Ready(server.designs, singleSlot)
    assertTitleOnScreen("Your designs")
  }

  @Test
  fun emptyListAfterLoading() {
    var state by mutableStateOf<DesignsState>(DesignsState.Loading)
    setScreen {
      DesignListScreen(
        state = state,
        accountName = "demo@example.com",
        server = "https://ui.coo.ee",
        deviceWff = 4,
        pushSupported = true,
        onOpen = {},
        onRefresh = {},
        onUnlink = {},
      )
    }
    rule.waitForIdle()
    state = DesignsState.Ready(emptyList(), singleSlot)
    assertTitleOnScreen("Your designs")
  }

  @Test
  fun publicListAfterLoading() {
    var state by mutableStateOf<DesignsState>(DesignsState.Loading)
    setScreen {
      DesignListScreen(
        state = state,
        accountName = null,
        server = "https://ui.coo.ee",
        deviceWff = 4,
        pushSupported = true,
        onOpen = {},
        onRefresh = {},
        onUnlink = {},
        linked = false,
      )
    }
    rule.waitForIdle()
    state = DesignsState.Ready(server.publicDesigns, singleSlot)
    assertTitleOnScreen("Public faces")
  }

  @Test
  fun linkScreenAfterRequesting() {
    var state by mutableStateOf<LinkState>(LinkState.Requesting)
    setScreen { LinkScreen(state, server = "https://ui.coo.ee", onRetry = {}, onChangeServer = {}) }
    rule.waitForIdle()
    state = LinkState.Waiting("AB7K-92QX", "https://ui.coo.ee/link?code=AB7K-92QX")
    assertTitleOnScreen("Link your account")
  }

  /**
   * Reopening sign-in (emulator run 44479759876): the screen once composed with the previous,
   * cancelled code, then fetched a new one, and the server issued the same code again. The pin had
   * fired on the stale code, so the new one did not re-pin and the screen opened scrolled.
   */
  @Test
  fun linkScreenWithARepeatedCode() {
    val code = LinkState.Waiting("AB7K-92QX", "https://ui.coo.ee/link?code=AB7K-92QX")
    var state by mutableStateOf<LinkState>(code)
    setScreen { LinkScreen(state, server = "https://ui.coo.ee", onRetry = {}, onChangeServer = {}) }
    rule.waitForIdle()
    state = LinkState.Requesting
    rule.waitForIdle()
    state = code
    assertTitleOnScreen("Link your account")
  }

  @Test
  fun serverScreen() {
    setScreen {
      ServerScreen(
        current = "https://faces.example.org",
        presets = listOf("https://ui.coo.ee", "https://preview.coo.ee"),
        onSelect = { true },
        onDone = {},
      )
    }
    assertTitleOnScreen("Catalog server")
  }

  @Test
  fun detailScreen() {
    setScreen {
      DesignDetailScreen(
        design = server.designs[0],
        push = singleSlot,
        deviceWff = 4,
        pushSupported = true,
        install = InstallUi.Idle,
        onInstall = {},
        onRequestActivation = {},
        onDismissResult = {},
      )
    }
    assertTitleOnScreen(server.designs[0].title)
  }
}
