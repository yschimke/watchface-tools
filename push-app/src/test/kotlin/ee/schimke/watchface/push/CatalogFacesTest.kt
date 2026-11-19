package ee.schimke.watchface.push

import ee.schimke.watchface.push.data.Attribution
import ee.schimke.watchface.push.data.CatalogInfo
import ee.schimke.watchface.push.data.DefaultJson
import ee.schimke.watchface.push.data.Design
import ee.schimke.watchface.push.data.DesignList
import ee.schimke.watchface.push.data.FakePushServer
import ee.schimke.watchface.push.push.InstallFlow
import ee.schimke.watchface.push.push.InstallResult
import ee.schimke.watchface.push.ui.attributionLines
import ee.schimke.watchface.push.ui.designSections
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Faces from public catalogs (preview.coo.ee), listed after the designs with attribution. */
class CatalogFacesTest {
  private val pair = fakeServerApi()
  private val server = pair.first
  private val api = pair.second
  private val catalogFace = server.designs.first { it.id == FakePushServer.CATALOG_FACE_ID }

  @Test
  fun parsesTheServersCatalogFields() {
    val json =
      """{"designs":[{"id":"catalog:wear-os-samples-watchfaces/SimpleDigital","title":"Simple Digital",
        "previewUrl":"https://preview.coo.ee/api/watchface-push/v1/catalog-faces/wear-os-samples-watchfaces/SimpleDigital/preview?v=3",
        "version":3,"wffVersion":1,
        "packageName":"ee.schimke.watchface.push.watchfacepush.c_wear_os_samples_watchfaces_simpledigital_0123456789",
        "apkUrl":"https://preview.coo.ee/api/watchface-push/v1/catalog-faces/wear-os-samples-watchfaces/SimpleDigital/apk?v=3",
        "sha256":"00","validationToken":"t","source":"catalog","visibility":"public",
        "catalog":{"system":"wear-os-samples-watchfaces","title":"Wear OS samples"},
        "attribution":{"repoUrl":"https://github.com/android/wear-os-samples","sourceUrl":"https://github.com/android/wear-os-samples/tree/main/WatchFaceFormat/SimpleDigital","license":"Apache-2.0"}}]}"""
    val face = DefaultJson.decodeFromString(DesignList.serializer(), json).designs.single()
    assertTrue(face.isCatalog)
    assertEquals(CatalogInfo("wear-os-samples-watchfaces", "Wear OS samples"), face.catalog)
    assertEquals("Apache-2.0", face.attribution?.license)
    assertTrue(PushConstants.isValidFacePackage(face.packageName))
  }

  @Test
  fun aDesignWithoutTheNewFieldsIsADesign() {
    assertTrue(server.designs.filter { it.id != catalogFace.id }.none { it.isCatalog })
  }

  @Test
  fun anonymousListingCarriesCatalogFacesAfterTheDesigns() = runTest {
    val listed = api.listDesigns(null)
    assertEquals(catalogFace, listed.last())
    assertTrue(listed.dropLast(1).none { it.isCatalog })
  }

  @Test
  fun aCatalogFaceDownloadsAndInstallsAnonymouslyFromItsOwnUrl() = runTest {
    val cache = createTempDir()
    val pusher = FakePusher()
    val result =
      InstallFlow(api, pusher, cache, ee.schimke.watchface.push.data.DeviceInfo(36))
        .install(null, catalogFace)
    assertIs<InstallResult.Success>(result)
    assertEquals(listOf(catalogFace.packageName), pusher.slots.map { it.packageName })
    assertTrue(server.installs.isEmpty())
    cache.deleteRecursively()
  }

  @Test
  fun sectionsPutDesignsFirstThenOnePerCatalog() {
    fun face(id: String, system: String, title: String?) =
      catalogFace.copy(id = "catalog:$system/$id", catalog = CatalogInfo(system, title))
    val aurora = server.designs.first { it.id == "aurora" }
    val list =
      listOf(
        aurora,
        face("a", "bonstio-casino-gshark", "Casino G-Shark"),
        face("b", "wear-os-samples-watchfaces", null),
        face("c", "bonstio-casino-gshark", "Casino G-Shark"),
      )
    val sections = designSections(list)
    assertEquals(
      listOf(null, "Casino G-Shark", "wear-os-samples-watchfaces"),
      sections.map { it.title },
    )
    assertEquals(listOf(aurora), sections[0].designs)
    assertEquals(
      listOf("catalog:bonstio-casino-gshark/a", "catalog:bonstio-casino-gshark/c"),
      sections[1].designs.map(Design::id),
    )
  }

  @Test
  fun onlyCatalogFacesNeedCredit() {
    assertEquals(
      listOf(
        "From Wear OS samples",
        "License: Apache-2.0",
        "Source: github.com/android/wear-os-samples/tree/main/WatchFaceFormat",
      ),
      attributionLines(catalogFace),
    )
    assertEquals(emptyList(), attributionLines(server.designs.first { it.id == "aurora" }))
    val repoOnly = catalogFace.copy(attribution = Attribution(repoUrl = "https://example.com/r"))
    assertEquals(
      listOf("From Wear OS samples", "Source: example.com/r"),
      attributionLines(repoOnly),
    )
  }

  private fun createTempDir(): File =
    File.createTempFile("catalog", "").also {
      it.delete()
      it.mkdirs()
    }
}
