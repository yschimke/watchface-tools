package ee.schimke.watchface.uibuilder.renderer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRendererSurfaceV2
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionCollector
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** Environment keys carrying optional editor preview state across the sandboxed runtime seam. */
internal const val WFF_SIMULATED_ENV = "wffSimulated"
internal const val WFF_COMPLICATIONS_ENV = "wffComplications"
internal const val WFF_SCRUB_TIME_ENV = "wffScrubTime"

internal fun UiBuilderDocument.wffSimulatedFromEnvironment(): WffSimulatedState {
  val raw =
    (environment[WFF_SIMULATED_ENV] as? JsonPrimitive)?.contentOrNull ?: return WffSimulatedState()
  val obj =
    runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
      ?: return WffSimulatedState()
  val defaults = WffSimulatedState()
  return WffSimulatedState(
    batteryPercent =
      (obj["batteryPercent"] as? JsonPrimitive)?.intOrNull ?: defaults.batteryPercent,
    batteryCharging =
      (obj["batteryCharging"] as? JsonPrimitive)?.booleanOrNull ?: defaults.batteryCharging,
    stepCount = (obj["stepCount"] as? JsonPrimitive)?.intOrNull ?: defaults.stepCount,
    stepGoal = (obj["stepGoal"] as? JsonPrimitive)?.intOrNull ?: defaults.stepGoal,
    heartRate = (obj["heartRate"] as? JsonPrimitive)?.intOrNull ?: defaults.heartRate,
    is24Hour = (obj["is24Hour"] as? JsonPrimitive)?.booleanOrNull ?: defaults.is24Hour,
    unreadNotifications =
      (obj["unreadNotifications"] as? JsonPrimitive)?.intOrNull ?: defaults.unreadNotifications,
    weatherTemperature =
      (obj["weatherTemperature"] as? JsonPrimitive)?.intOrNull ?: defaults.weatherTemperature,
    weatherCondition =
      (obj["weatherCondition"] as? JsonPrimitive)?.intOrNull ?: defaults.weatherCondition,
    weatherConditionName =
      (obj["weatherConditionName"] as? JsonPrimitive)?.contentOrNull
        ?: defaults.weatherConditionName,
    weatherPrecipitationChance =
      (obj["weatherPrecipitationChance"] as? JsonPrimitive)?.intOrNull
        ?: defaults.weatherPrecipitationChance,
  )
}

internal fun UiBuilderDocument.wffComplicationsFromEnvironment(): Map<String, WffComplicationPick> {
  val raw =
    (environment[WFF_COMPLICATIONS_ENV] as? JsonPrimitive)?.contentOrNull ?: return emptyMap()
  val obj =
    runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return emptyMap()
  return obj.entries
    .mapNotNull { (slotId, entry) ->
      val map = entry as? JsonObject ?: return@mapNotNull null
      slotId to
        WffComplicationPick(
          provider = (map["provider"] as? JsonPrimitive)?.contentOrNull,
          type = (map["type"] as? JsonPrimitive)?.contentOrNull,
          value = (map["value"] as? JsonPrimitive)?.doubleOrNull,
        )
    }
    .toMap()
}

internal fun UiBuilderDocument.wffScrubTimeFromEnvironment(): WffScrubTime? {
  val raw = (environment[WFF_SCRUB_TIME_ENV] as? JsonPrimitive)?.contentOrNull ?: return null
  val parts = raw.split(':')
  if (parts.size != 3) return null
  val hour = parts[0].toIntOrNull() ?: return null
  val minute = parts[1].toIntOrNull() ?: return null
  val seconds = parts[2].toDoubleOrNull() ?: return null
  return WffScrubTime.of(hour, minute, seconds)
}

/**
 * Renders a Watch Face Format [document] inside the catalog-owned Wasm renderer iframe and reports
 * node layout bounds back to the editor over [onInspectionSnapshot].
 */
@Composable
internal fun WffCatalogRendererHost(
  document: UiBuilderDocument,
  surface: UiBuilderRendererSurfaceV2,
  onInspectionSnapshot: (UiBuilderInspectionSnapshot) -> Unit,
) {
  val collector =
    remember(document.id, document.revision) {
      UiBuilderInspectionCollector(document = document, onSnapshot = onInspectionSnapshot)
    }

  val simulated = remember(document.environment) { document.wffSimulatedFromEnvironment() }
  val complications = remember(document.environment) { document.wffComplicationsFromEnvironment() }
  val scrubTime = remember(document.environment) { document.wffScrubTimeFromEnvironment() }
  val clockState = remember(scrubTime) { WffPreviewClockState().apply { scrubbed = scrubTime } }

  val widthDp = surface.widthDp
  val heightDp = surface.heightDp
  val densityScale = surface.density

  CompositionLocalProvider(
    LocalDensity provides Density(density = densityScale, fontScale = 1f),
    LocalWffSimulatedState provides simulated,
    LocalWffComplicationPicks provides complications,
    LocalWffPreviewClock provides clockState,
  ) {
    val rootId = document.roots.firstOrNull()
    Box(Modifier.requiredSize(widthDp.dp, heightDp.dp)) {
      if (rootId != null) {
        RenderWffNode(
          document = document,
          nodeId = rootId,
          modifier = Modifier.fillMaxSize(),
          collector = collector,
        )
      }
    }
  }
}

@Composable
private fun RenderWffNode(
  document: UiBuilderDocument,
  nodeId: String,
  modifier: Modifier,
  collector: UiBuilderInspectionCollector,
) {
  val node = document.nodes[nodeId] ?: return
  val trackedModifier = modifier.onGloballyPositioned { coords ->
    val pos = coords.positionInRoot()
    collector.recordNodeBounds(
      nodeId = node.id,
      left = pos.x,
      top = pos.y,
      right = pos.x + coords.size.width,
      bottom = pos.y + coords.size.height,
    )
  }
  WatchFaceCanvas(
    document = document,
    node = node,
    modifier = trackedModifier,
  ) { childId, childModifier ->
    RenderWffNode(
      document = document,
      nodeId = childId,
      modifier = childModifier,
      collector = collector,
    )
  }
}
