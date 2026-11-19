package ee.schimke.watchface.mobile.widget

import android.content.Context
import java.io.File

/** The face a widget shows: which site's, which design, and its title for the app's list. */
data class WidgetFace(val server: String, val faceId: String, val title: String)

/**
 * What each widget shows, and the documents it was last drawn with. A widget's document is kept on
 * disk by size so the minute tick re-pushes it without the network, and a resize to a size already
 * rendered costs nothing.
 */
class FaceWidgetStore(context: Context) {
  private val prefs = context.getSharedPreferences("face_widgets", Context.MODE_PRIVATE)
  private val dir = File(context.filesDir, "faces").apply { mkdirs() }

  fun face(widgetId: Int): WidgetFace? {
    val server = prefs.getString("$widgetId.server", null) ?: return null
    val faceId = prefs.getString("$widgetId.face", null) ?: return null
    return WidgetFace(server, faceId, prefs.getString("$widgetId.title", null) ?: faceId)
  }

  fun setFace(widgetId: Int, face: WidgetFace) {
    prefs
      .edit()
      .putString("$widgetId.server", face.server)
      .putString("$widgetId.face", face.faceId)
      .putString("$widgetId.title", face.title)
      .apply()
  }

  /** The cached document for [face] at [sizePx], or null. */
  fun document(face: WidgetFace, sizePx: Int): ByteArray? =
    file(face, sizePx).takeIf { it.isFile }?.readBytes()

  fun putDocument(face: WidgetFace, sizePx: Int, bytes: ByteArray) {
    val target = file(face, sizePx)
    val temp = File(dir, target.name + ".tmp")
    temp.writeBytes(bytes)
    temp.renameTo(target)
  }

  /** Forgets [widgetIds], and every cached document no remaining widget shows. */
  fun remove(widgetIds: IntArray) {
    prefs
      .edit()
      .apply {
        for (id in widgetIds) {
          remove("$id.server")
          remove("$id.face")
          remove("$id.title")
        }
      }
      .apply()
    val kept =
      prefs.all.keys
        .filter { it.endsWith(".face") }
        .mapNotNull { key -> face(key.substringBefore('.').toInt())?.let(::prefix) }
    dir
      .listFiles()
      ?.filter { file -> kept.none { file.name.startsWith(it) } }
      ?.forEach { it.delete() }
  }

  private fun file(face: WidgetFace, sizePx: Int) = File(dir, "${prefix(face)}$sizePx.rc")

  /** A file-name-safe key for a face: ids may hold `/` and `:` (catalog faces). */
  private fun prefix(face: WidgetFace) =
    (face.server + "|" + face.faceId).hashCode().toUInt().toString(16) + "-"
}
