package ee.schimke.watchface.uibuilder.renderer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull

internal val LocalUiBuilderEditingCanvas = compositionLocalOf { false }

internal fun UiBuilderNode.valueScalar(name: String): JsonPrimitive? {
  val raw = properties[name] ?: return null
  return when (raw) {
    is JsonPrimitive -> raw
    is JsonObject -> raw["value"] as? JsonPrimitive
    else -> null
  }
}

internal fun UiBuilderNode.string(name: String): String = valueScalar(name)?.contentOrNull.orEmpty()

internal fun UiBuilderNode.float(name: String, fallback: Float = 0f): Float =
  valueScalar(name)?.floatOrNull ?: fallback

internal fun UiBuilderNode.int(name: String, fallback: Int = 0): Int =
  valueScalar(name)?.intOrNull ?: fallback

internal fun UiBuilderNode.boolean(name: String, fallback: Boolean = false): Boolean =
  valueScalar(name)?.booleanOrNull ?: fallback

internal fun UiBuilderNode.color(name: String, fallback: Color): Color {
  val value = string(name)
  if (value.startsWith("#")) return Color(parseArgb(value))
  return fallback
}

internal fun parseArgb(value: String): Long =
  value.removePrefix("#").toLongOrNull(16)?.let { if (value.length == 7) it or 0xff000000 else it }
    ?: 0xff000000

/**
 * Asset bitmap lookup hook for `PartImage` inside the standalone catalog renderer. Returns null
 * until asset bytes are delivered across the runtime boundary.
 */
@Composable internal fun UiBuilderDocument.rememberAssetBitmap(assetId: String): ImageBitmap? = null
