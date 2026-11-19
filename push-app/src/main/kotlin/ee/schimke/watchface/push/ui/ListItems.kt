package ee.schimke.watchface.push.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListHeaderDefaults
import androidx.wear.compose.material3.LocalTextStyle
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextDefaults
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight

/*
 * Wear Material 3 list items for a ScreenScaffold + TransformingLazyColumn: surfaces (buttons,
 * cards, headers) take SurfaceTransformation, everything else takes [transformedItem], so content
 * scrolling under the time text, or towards the bottom edge, shrinks and fades instead of being
 * drawn over the time or clipped by the round screen.
 */

/**
 * List state for a screen that must open at its title. The first layout pins the first item (the
 * header) to the top: the default `initialAnchorItemIndex` of -1; a non-negative index would
 * *centre* that item instead.
 *
 * After that the column keeps the item nearest the centre in place across content changes. A list
 * that first lays out without its content (a spinner, then the action buttons) would keep a button
 * centred when the content arrives, opening scrolled down past the header. So each time
 * [contentKey] changes to a new non-null value (the content first loaded, a new link code) the
 * header is pinned to the top again, in the same frame; while it stays the same (a refresh) the
 * position is kept. The last key survives navigation, so coming back from a detail screen keeps the
 * scroll.
 */
@Composable
fun rememberTopAnchoredListState(
  contentKey: String? = "",
  /**
   * True: a null [contentKey] (content gone, e.g. a new link code being fetched) forgets the last
   * key, so the next content re-pins even if its key repeats.
   */
  repinAfterReload: Boolean = false,
): TransformingLazyColumnState {
  val state = rememberTransformingLazyColumnState()
  var pinnedKey by rememberSaveable { mutableStateOf<String?>(null) }
  if (contentKey == null && repinAfterReload && pinnedKey != null) {
    SideEffect { pinnedKey = null }
  }
  if (contentKey != null && contentKey != pinnedKey) {
    SideEffect {
      // Centring item 0 overscrolls the start, which the column corrects by pinning it to the top.
      state.requestScrollToItem(0)
      pinnedKey = contentKey
    }
  }
  return state
}

/** Scales and fades a non-surface item (text, image, progress) at the screen's top and bottom. */
fun Modifier.transformedItem(
  scope: TransformingLazyColumnItemScope,
  spec: TransformationSpec,
): Modifier =
  with(scope) {
    this@transformedItem.transformedHeight(scope, spec).graphicsLayer {
      with(spec) { applyContainerTransformation(scrollProgress) }
    }
  }

/** A screen title or section header. As the first item it asks for the M3 top padding. */
@Composable
fun TransformingLazyColumnItemScope.Header(text: String, spec: TransformationSpec) {
  ListHeader(
    modifier =
      Modifier.fillMaxWidth()
        .transformedHeight(this, spec)
        .minimumVerticalContentPadding(
          top = ListHeaderDefaults.minimumTopListContentPadding,
          bottom = ListHeaderDefaults.minimumBottomListContentPadding,
        ),
    transformation = SurfaceTransformation(spec),
  ) {
    Text(text, textAlign = TextAlign.Center)
  }
}

/**
 * Centred body text. Inset a little more than the scaffold's content padding: a full-width line
 * near the top or bottom of a round screen otherwise loses its first and last letters to the curve.
 */
@Composable
fun TransformingLazyColumnItemScope.BodyText(
  text: String,
  spec: TransformationSpec,
  style: TextStyle = LocalTextStyle.current,
  color: Color = Color.Unspecified,
) {
  Text(
    text,
    modifier =
      Modifier.fillMaxWidth()
        .transformedItem(this, spec)
        .minimumVerticalContentPadding(
          top = TextDefaults.minimumTopListContentPadding,
          bottom = TextDefaults.minimumBottomListContentPadding,
        )
        .padding(horizontal = BodyTextInset),
    style = style,
    color = color,
    textAlign = TextAlign.Center,
  )
}

private val BodyTextInset = 8.dp
