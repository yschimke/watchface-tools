package ee.schimke.watchface.uibuilder.renderer

import ee.schimke.composeai.uibuilder.ProvideUiBuilderFonts
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderSemanticActionController
import ee.schimke.composeai.uibuilder.renderer.sdk.catalogRuntimeFontRegistry
import ee.schimke.composeai.uibuilder.renderer.sdk.startCatalogRenderer

fun main() {
  val actionController = UiBuilderSemanticActionController()
  val fonts = catalogRuntimeFontRegistry()
  startCatalogRenderer(actionController) { document, surface, _, onInspectionSnapshot ->
    ProvideUiBuilderFonts(fonts) {
      WffCatalogRendererHost(
        document = document,
        surface = surface,
        onInspectionSnapshot = onInspectionSnapshot,
      )
    }
  }
}
