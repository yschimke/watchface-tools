package ee.schimke.watchface.push.ui

import android.app.RemoteInput
import android.content.Intent
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.input.wearableExtender
import ee.schimke.watchface.push.data.CatalogServers

private const val SERVER_INPUT_KEY = "server"

/**
 * Picks the catalog server: the known ones, the current one if it was typed in, and "Other server"
 * for any other address, entered with the watch keyboard or voice. [onSelect] returns false for an
 * address that is not usable, and the screen says so.
 */
@Composable
fun ServerScreen(
  current: String?,
  presets: List<String>,
  onSelect: (String) -> Boolean,
  onDone: () -> Unit,
) {
  val list = rememberTransformingLazyColumnState()
  var error by remember { mutableStateOf<String?>(null) }
  val input =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      val text =
        result.data
          ?.let(RemoteInput::getResultsFromIntent)
          ?.getCharSequence(SERVER_INPUT_KEY)
          ?.toString()
      if (text != null) {
        if (onSelect(text)) onDone() else error = Messages.badServer(text)
      }
    }
  val options = (presets + listOfNotNull(current)).distinct()
  ScreenScaffold(list) { padding ->
    TransformingLazyColumn(state = list, contentPadding = padding) {
      item { ListHeader { Text("Catalog server") } }
      item {
        Text(
          "Where Faces finds your designs. Each server has its own link.",
          style = MaterialTheme.typography.bodySmall,
          textAlign = TextAlign.Center,
        )
      }
      items(options.size) { index ->
        val server = options[index]
        RadioButton(
          selected = server == current,
          onSelect = { if (onSelect(server)) onDone() },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(CatalogServers.label(server)) },
          secondaryLabel =
            if (server == presets.firstOrNull()) ({ Text("Default") })
            else if (server !in presets) ({ Text("Entered") }) else null,
        )
      }
      item {
        Button(
          onClick = {
            error = null
            val remote =
              RemoteInput.Builder(SERVER_INPUT_KEY)
                .setLabel("Server address")
                .wearableExtender {
                  setEmojisAllowed(false)
                  setInputActionType(EditorInfo.IME_ACTION_DONE)
                }
                .build()
            val intent: Intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
            RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(remote))
            input.launch(intent)
          },
          colors = ButtonDefaults.filledTonalButtonColors(),
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Other server") },
          secondaryLabel = { Text("Type its address") },
        )
      }
      error?.let {
        item {
          Text(
            it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
          )
        }
      }
    }
  }
}
