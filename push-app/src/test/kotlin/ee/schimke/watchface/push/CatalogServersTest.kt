package ee.schimke.watchface.push

import ee.schimke.watchface.push.data.CatalogServers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CatalogServersTest {
  @Test
  fun normalizesToHttpsWithoutTrailingSlash() {
    assertEquals("https://ui.coo.ee", CatalogServers.normalize("ui.coo.ee"))
    assertEquals("https://ui.coo.ee", CatalogServers.normalize("  https://UI.coo.ee/ "))
    assertEquals(
      "https://faces.example.org:8443/site",
      CatalogServers.normalize("faces.example.org:8443/site/"),
    )
    assertEquals("https://ui.coo.ee", CatalogServers.normalize("https://ui.coo.ee:443"))
  }

  @Test
  fun refusesAddressesThatWouldLeakOrMislead() {
    assertNull(CatalogServers.normalize("http://ui.coo.ee"), "plain http sends the token in clear")
    assertNull(CatalogServers.normalize("https://user:pw@ui.coo.ee"))
    assertNull(CatalogServers.normalize("https://ui.coo.ee/?x=1"))
    assertNull(CatalogServers.normalize("https://ui.coo.ee/#a"))
    assertNull(CatalogServers.normalize("ftp://ui.coo.ee"))
    assertNull(CatalogServers.normalize("not a url"))
    assertNull(CatalogServers.normalize("intranet"), "a bare word is a typo, not a server")
    assertNull(CatalogServers.normalize(""))
  }

  @Test
  fun debugBuildsMayUsePlainHttpForALocalServer() {
    assertEquals("http://10.0.2.2:8080", CatalogServers.normalize("http://10.0.2.2:8080", true))
    assertEquals("http://localhost:8080", CatalogServers.normalize("http://localhost:8080", true))
  }

  @Test
  fun labelsAndPresets() {
    assertEquals("ui.coo.ee", CatalogServers.label("https://ui.coo.ee"))
    assertEquals("x.org:8443/a", CatalogServers.label("https://x.org:8443/a"))
    assertEquals(
      listOf("https://ui.coo.ee", "https://preview.coo.ee"),
      CatalogServers.presets(
        "https://ui.coo.ee",
        listOf("https://ui.coo.ee/", "preview.coo.ee", "http://insecure.example"),
      ),
    )
  }
}
