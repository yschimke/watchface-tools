package ee.schimke.watchface.push.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream

object PreviewArt {

  /** Placeholder preview for the fake server: a dial with the design title. */
  fun render(title: String): ByteArray {
    val size = 240
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val hue = (title.hashCode() and 0x7fffffff) % 360
    canvas.drawColor(Color.BLACK)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.6f, 0.9f))
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 6, paint)
    paint.color = Color.BLACK
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 20, paint)
    paint.color = Color.WHITE
    paint.textSize = 30f
    paint.textAlign = Paint.Align.CENTER
    canvas.drawText(title, size / 2f, size / 2f + 10, paint)
    return ByteArrayOutputStream()
      .also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
      .toByteArray()
  }
}
