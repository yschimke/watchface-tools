package ee.schimke.watchface.mobile

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import ee.schimke.watchface.mobile.data.Face
import ee.schimke.watchface.mobile.data.FaceCatalog
import ee.schimke.watchface.mobile.widget.FaceWidgetProvider
import ee.schimke.watchface.mobile.widget.WidgetFace
import okhttp3.OkHttpClient

/**
 * Faces on the phone: the site's public watch faces, each one tap from the home screen. Opened from
 * the launcher it pins a new widget; opened as [Configure] (adding or reconfiguring a widget) it
 * sets that widget's face.
 */
open class MainActivity : ComponentActivity() {
  /** The launcher's configuration step for a new or existing face widget. */
  class Configure : MainActivity()

  private val widgetId: Int
    get() =
      intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val configuring = widgetId != AppWidgetManager.INVALID_APPWIDGET_ID
    // Backing out of a new widget's configuration cancels it.
    if (configuring) {
      setResult(
        Activity.RESULT_CANCELED,
        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
      )
    }
    val catalog = FaceCatalog(BuildConfig.FACES_SERVER, OkHttpClient())
    setContent {
      MaterialTheme(if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        FacesScreen(catalog, if (configuring) "Choose a face" else "Faces") { face -> choose(face) }
      }
    }
  }

  private fun choose(face: Face) {
    val chosen = WidgetFace(BuildConfig.FACES_SERVER, face.id, face.title, face.version)
    val id = widgetId
    if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
      FaceWidgetProvider.show(applicationContext, id, chosen)
      setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
      finish()
    } else if (!resources.getBoolean(R.bool.supports_face_widgets)) {
      Toast.makeText(this, "Watch face widgets need Android 17", Toast.LENGTH_LONG).show()
    } else if (!FaceWidgetProvider.pin(applicationContext, chosen)) {
      Toast.makeText(this, "Add the Faces widget from your home screen", Toast.LENGTH_LONG).show()
    }
  }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun FacesScreen(catalog: FaceCatalog, title: String, onChoose: (Face) -> Unit) {
  var faces by remember { mutableStateOf<List<Face>?>(null) }
  var error by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(catalog) {
    runCatching { catalog.faces() }
      .onSuccess { faces = it }
      .onFailure { error = it.message ?: "Couldn't load faces" }
  }
  Scaffold(topBar = { TopAppBar(title = { Text(title) }) }) { padding ->
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
      val list = faces
      when {
        error != null -> Text(error!!, Modifier.padding(24.dp))
        list == null -> CircularProgressIndicator()
        list.isEmpty() -> Text("Nothing published yet.", Modifier.padding(24.dp))
        else ->
          LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            items(list, key = { it.id }) { face -> FaceRow(face) { onChoose(face) } }
          }
      }
    }
  }
}

@Composable
private fun FaceRow(face: Face, onClick: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().clickable(onClick = onClick).padding(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    AsyncImage(
      model = face.previewUrl,
      contentDescription = null,
      modifier = Modifier.size(72.dp).clip(CircleShape),
    )
    Column(Modifier.padding(start = 16.dp)) {
      Text(face.title, style = MaterialTheme.typography.titleMedium)
      face.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
  }
}
