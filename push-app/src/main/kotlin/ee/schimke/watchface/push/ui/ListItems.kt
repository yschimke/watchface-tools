package ee.schimke.watchface.push.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState

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
