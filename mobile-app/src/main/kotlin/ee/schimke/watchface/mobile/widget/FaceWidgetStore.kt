package ee.schimke.watchface.mobile.widget

import android.content.Context
import java.io.File

/**
 * The face a widget shows: which site's, which design, the published [version] it was chosen at,
 * and its title for the app's list. A new version is a new document.
 */
data class WidgetFace(
  val server: String,
  val faceId: String,
  val title: String,
  val version: Long = 0,
)

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
    return WidgetFace(
      server,
      faceId,
      prefs.getString("$widgetId.title", null) ?: faceId,
      prefs.getLong("$widgetId.version", 0),
    )
  }

  fun setFace(widgetId: Int, face: WidgetFace) {
    prefs
      .edit()
      .putString("$widgetId.server", face.server)
      .putString("$widgetId.face", face.faceId)
      .putString("$widgetId.title", face.title)
      .putLong("$widgetId.version", face.version)
      .apply()
    // The face it replaces, or an older version of it, may be no widget's document any more.
    prune()
  }

  /** Whether a render for [widgetId] last failed at least [afterMs] before [nowMillis]. */
  fun retryDue(widgetId: Int, nowMillis: Long, afterMs: Long): Boolean {
    val last = lastAttempt(widgetId)
    return last >= 0 && nowMillis - last >= afterMs
  }

  /** When a render for [widgetId] last failed, or -1 if none has: never tried goes first. */
  fun lastAttempt(widgetId: Int): Long = prefs.getLong("$widgetId.attempt", -1)

  /** Records a render attempt at [atMillis] (null once one succeeded). */
  fun attempted(widgetId: Int, atMillis: Long?) {
    prefs
      .edit()
      .apply {
        if (atMillis == null) remove("$widgetId.attempt")
        else putLong("$widgetId.attempt", atMillis)
      }
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
          remove("$id.attempt")
          remove("$id.version")
        }
      }
      .apply()
    prune()
  }

  /** Deletes every cached document no widget's current face uses. */
  private fun prune() {
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
    (face.server + "|" + face.faceId + "|" + face.version).hashCode().toUInt().toString(16) + "-"
}
