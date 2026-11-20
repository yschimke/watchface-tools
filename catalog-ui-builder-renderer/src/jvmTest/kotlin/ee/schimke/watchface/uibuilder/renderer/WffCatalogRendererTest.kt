package ee.schimke.watchface.uibuilder.renderer

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class WffCatalogRendererTest {

  private val json = Json { ignoreUnknownKeys = true }

  private fun loadTemplate(name: String): UiBuilderDocument {
    val file = java.io.File("../ui-builder-catalog/ui-builder/designs/$name")
    assertTrue(file.isFile, "Missing catalog template: ${file.absolutePath}")
    return json.decodeFromString(UiBuilderDocument.serializer(), file.readText())
  }

  @Test
  fun `catalog templates load as watch faces and decode environment preview params`() {
    val analog = loadTemplate("analog-watch-face.json")
    val digital = loadTemplate("digital-watch-face.json")
    assertTrue(analog.isWatchFace())
    assertTrue(digital.isWatchFace())

    val withParams =
      analog.copy(
        environment =
          JsonObject(
            analog.environment +
              mapOf(
                WFF_SIMULATED_ENV to
                  JsonPrimitive(
                    buildJsonObject {
                      put("batteryPercent", 35)
                      put("stepCount", 4200)
                      put("heartRate", 88)
                    }
                      .toString(),
                  ),
                WFF_SCRUB_TIME_ENV to JsonPrimitive("14:30:15.5"),
              ),
          ),
      )

    val simulated = withParams.wffSimulatedFromEnvironment()
    assertEquals(35, simulated.batteryPercent)
    assertEquals(4200, simulated.stepCount)
    assertEquals(88, simulated.heartRate)

    val scrub = withParams.wffScrubTimeFromEnvironment()
    assertNotNull(scrub)
    assertEquals(14, scrub.hour)
    assertEquals(30, scrub.minute)
    assertEquals(15.5, scrub.seconds)

    val context = WffPreviewContext().at(scrub).withSimulated(simulated)
    assertEquals(WffValue.Num(35.0), evaluateWffExpression("[BATTERY_PERCENT]", context))
    assertEquals(WffValue.Num(4200.0), evaluateWffExpression("[STEP_COUNT]", context))
    assertEquals(WffValue.Num(88.0), evaluateWffExpression("[HEART_RATE]", context))
  }
}
