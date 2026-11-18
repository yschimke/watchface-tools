package ee.schimke.watchface.push

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import ee.schimke.watchface.push.data.PreviewArt
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Where the Play listing graphics go: `-PplayGraphicsDir=<listing>/graphics`. */
object PlayGraphics {
  fun dir(type: String): String =
    System.getProperty("playGraphicsDir").orEmpty().let { if (it.isEmpty()) "" else "$it/$type" }

  fun write(type: String, name: String, bitmap: Bitmap) {
    val dir = dir(type)
    if (dir.isEmpty()) return
    File(dir).mkdirs()
    File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }
}

/**
 * Renders the Play listing's 512×512 icon and 1024×500 feature graphic from the launcher icon.
 * Always checks they render; writes them under `-PplayGraphicsDir=<dir>` when given.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class PlayGraphicsTest {
  @get:Rule val rule = createComposeRule()

  /**
   * The launcher icon at [size]px, full square as Play wants it (Play applies its own mask). The
   * adaptive icon's visible 72dp of 108dp fills the bitmap.
   */
  private fun launcherIcon(size: Int): Bitmap {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val icon = context.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val bleed = size * 18 / 72
    for (layer in listOfNotNull(icon.background, icon.foreground)) {
      layer.setBounds(-bleed, -bleed, size + bleed, size + bleed)
      layer.draw(canvas)
    }
    return bitmap
  }

  @Test
  fun icon() {
    val bitmap = launcherIcon(512)
    check(bitmap.width == 512 && bitmap.height == 512)
    PlayGraphics.write("icon", "icon.png", bitmap)
  }

  @Config(qualifiers = "w1024dp-h500dp-mdpi")
  @Test
  fun featureGraphic() {
    val icon = launcherIcon(220).asImageBitmap()
    val dials =
      listOf("Aurora", "Pulse", "Drift", "Sunset").map {
        val bytes = PreviewArt.render(it)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
      }
    rule.setContent {
      MaterialTheme {
        Row(
          Modifier.fillMaxSize().background(Color(0xFF1B1A22)).padding(horizontal = 48.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Image(icon, null, Modifier.size(120.dp).clip(RoundedCornerShape(28.dp)))
          Spacer(Modifier.width(28.dp))
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Faces", color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Bold)
            Text(
              "Watch faces from the web,\nstraight onto your watch.",
              color = Color(0xFFD7D3E0),
              fontSize = 24.sp,
              lineHeight = 32.sp,
            )
          }
          // Four sample dials in a diamond, with distinct colours and room for their names.
          val spots = listOf(0 to 75, 165 to 0, 165 to 150, 330 to 75)
          Box(Modifier.size(width = 470.dp, height = 290.dp)) {
            dials.zip(spots).forEach { (dial, spot) ->
              Image(
                dial,
                null,
                Modifier.offset(x = spot.first.dp, y = spot.second.dp)
                  .size(140.dp)
                  .clip(CircleShape),
              )
            }
          }
        }
      }
    }
    rule.waitForIdle()
    val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
    check(bitmap.width == 1024 && bitmap.height == 500) { "${bitmap.width}x${bitmap.height}" }
    PlayGraphics.write("feature-graphic", "feature-graphic.png", bitmap)
  }
}
