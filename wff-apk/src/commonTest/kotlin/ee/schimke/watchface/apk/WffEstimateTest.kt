package ee.schimke.watchface.apk

import ee.schimke.watchface.packager.CheckStatus
import ee.schimke.watchface.packager.ContributorKind
import ee.schimke.watchface.packager.FindingSeverity
import ee.schimke.watchface.packager.ImageHeader
import ee.schimke.watchface.packager.ReportCheck.Companion.MEMORY_FOOTPRINT
import ee.schimke.watchface.packager.ReportCheck.Companion.PUSH_VALIDATOR
import ee.schimke.watchface.packager.ReportCheck.Companion.WFF_SCHEMA
import ee.schimke.watchface.packager.ReportCheck.Companion.WFF_XSD
import ee.schimke.watchface.packager.ReportMode
import ee.schimke.watchface.packager.WffEstimate
import ee.schimke.watchface.packager.WffMemoryEstimator
import ee.schimke.watchface.wff.WffBundleLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The cheap report: runs on the JVM and under Kotlin/Wasm (`wasmJsBrowserTest`). */
class WffEstimateTest {
  /** A PNG with only the signature and IHDR, which is all the estimate reads. */
  private fun png(w: Int, h: Int, colourType: Int = 6): ByteArray {
    fun be32(v: Int) = listOf(v ushr 24, v ushr 16, v ushr 8, v).map { (it and 0xff).toByte() }
    val bytes =
      listOf(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13).map { it.toByte() } +
        "IHDR".encodeToByteArray().toList() +
        be32(w) +
        be32(h) +
        listOf(8, colourType, 0, 0, 0, 0, 0, 0, 0).map { it.toByte() }
    return bytes.toByteArray()
  }

  private fun jpeg(w: Int, h: Int, components: Int): ByteArray =
    listOf(
        0xff,
        0xd8, // SOI
        0xff,
        0xe0,
        0x00,
        0x04,
        0x00,
        0x00, // APP0, empty
        0xff,
        0xc0,
        0x00,
        0x11,
        0x08,
        h shr 8,
        h and 0xff,
        w shr 8,
        w and 0xff,
        components,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
      )
      .map { it.toByte() }
      .toByteArray()

  private fun face(scene: String) =
    """<WatchFace width="450" height="450"><Scene>$scene</Scene></WatchFace>"""

  private fun bundle(scene: String, vararg drawables: Pair<String, ByteArray>) =
    TestBundles.bundle().toMutableMap().also { b ->
      b[WffBundleLayout.WATCH_FACE_PATH] = face(scene).encodeToByteArray()
      for ((name, bytes) in drawables) b["res/drawable/$name"] = bytes
    }

  private val caseScene =
    """<PartImage x="0" y="0" width="450" height="450"><Image resource="case_steel"/></PartImage>"""

  @Test
  fun readsImageHeaders() {
    assertEquals(ImageHeader("png", 300, 200, true), WffMemoryEstimator.imageHeader(png(300, 200)))
    assertEquals(
      ImageHeader("png", 3, 4, false),
      WffMemoryEstimator.imageHeader(png(3, 4, colourType = 0)),
    )
    assertEquals(
      ImageHeader("jpeg", 640, 480, true),
      WffMemoryEstimator.imageHeader(jpeg(640, 480, 3)),
    )
    assertEquals(ImageHeader("jpeg", 8, 9, false), WffMemoryEstimator.imageHeader(jpeg(8, 9, 1)))
    assertEquals(ImageHeader("png", 4, 4, true), WffMemoryEstimator.imageHeader(TestBundles.png))
    assertNull(WffMemoryEstimator.imageHeader(byteArrayOf(1, 2, 3)))
    assertNull(WffMemoryEstimator.imageHeader(png(3, 3).copyOf(20)))
  }

  @Test
  fun aMissingAssetIsAnErrorWithTheElementPathAndTheClosestName() {
    // The real case_steel failure: the face names a drawable the bundle does not ship.
    val report = WffEstimate.report(bundle(caseScene, "case_steel_1.png" to png(450, 450)))
    assertEquals(ReportMode.ESTIMATE, report.mode)
    assertFalse(report.passed)
    val memory = report.check(MEMORY_FOOTPRINT)!!
    assertEquals(CheckStatus.FAILED, memory.status)
    val error = memory.findings.first { it.severity == FindingSeverity.ERROR }
    assertTrue("case_steel" in error.message, error.message)
    assertEquals("/WatchFace/Scene/PartImage/Image", error.path)
    assertTrue("case_steel_1" in error.suggestion!!, error.suggestion)
    val missing = memory.contributors!!.first { it.resource == "drawable/case_steel" }
    assertTrue(missing.missing)
    assertEquals(450, missing.renderedMaxWidth)
  }

  @Test
  fun anOversizedImageIsSuggestedToBeDownscaledToItsDrawnSize() {
    val report = WffEstimate.report(bundle(caseScene, "case_steel.png" to png(1024, 1024)))
    assertTrue(report.passed, report.toJson())
    val memory = report.check(MEMORY_FOOTPRINT)!!
    val c = memory.contributors!!.first()
    assertEquals("drawable/case_steel", c.resource)
    assertEquals(ContributorKind.IMAGE, c.kind)
    assertEquals(1024L * 1024 * 4, c.decodedBytes)
    assertEquals(c.decodedBytes, c.activeBytes)
    assertEquals(c.decodedBytes, c.ambientBytes)
    assertTrue("to the 450x450 it is drawn at" in c.suggestion!!, c.suggestion)
    assertTrue("full-colour" in c.suggestion!!, c.suggestion)
    assertEquals(c.decodedBytes, memory.memory!!.maxActiveBytes)
    assertTrue(
      memory.findings.any { it.severity == FindingSeverity.WARNING && it.suggestion != null },
    )
  }

  @Test
  fun anImageHiddenInAmbientCostsNothingThereAndContributorsAreSortedByCost() {
    val scene =
      """<PartImage x="0" y="0" width="450" height="450"><Variant mode="AMBIENT" target="alpha" value="0"/><Image resource="big"/></PartImage>""" +
        """<PartImage x="0" y="0" width="10" height="10"><Image resource="small"/></PartImage>"""
    val report =
      WffEstimate.report(bundle(scene, "big.png" to png(450, 450), "small.png" to png(10, 10, 0)))
    val contributors = report.check(MEMORY_FOOTPRINT)!!.contributors!!
    assertEquals(
      listOf("drawable/big", "drawable/small"),
      contributors.filter { it.activeBytes > 0 }.map { it.resource },
    )
    val big = contributors.first()
    assertEquals(0, big.ambientBytes)
    assertNull(big.suggestion, "drawn at its size and hidden in ambient: nothing to suggest")
    assertEquals(400L, contributors.first { it.resource == "drawable/small" }.ambientBytes)
  }

  @Test
  fun anUnreferencedDrawableIsReportedButCostsNoMemory() {
    val report = WffEstimate.report(bundle("", "leftover.png" to png(100, 100)))
    val c = report.check(MEMORY_FOOTPRINT)!!.contributors!!.single()
    assertEquals("drawable/leftover", c.resource)
    assertEquals(0, c.cost)
    assertTrue("not referenced" in c.suggestion!!)
  }

  @Test
  fun overTheAmbientLimitIsAnErrorNamingTheLargest() {
    val report = WffEstimate.report(bundle(caseScene, "case_steel.png" to png(2000, 2000)))
    val memory = report.check(MEMORY_FOOTPRINT)!!
    val error = memory.findings.single { it.severity == FindingSeverity.ERROR }
    assertTrue(
      error.message.startsWith("Ambient footprint 15.3 MB exceeds the 10.0 MB"),
      error.message,
    )
    assertTrue("drawable/case_steel" in error.suggestion!!)
    assertFalse(report.passed)
  }

  @Test
  fun theEstimateRunsWffCoreAndSkipsGooglesValidators() {
    val report = WffEstimate.report(TestBundles.bundle())
    assertEquals(CheckStatus.PASSED, report.check(WFF_SCHEMA)!!.status)
    for (id in listOf(WFF_XSD, PUSH_VALIDATOR)) {
      val c = report.check(id)!!
      assertEquals(CheckStatus.SKIPPED, c.status)
      assertEquals(FindingSeverity.WARNING, c.findings.single().severity)
    }
    assertTrue(report.passed, report.toJson())
    val json = report.toJson()
    assertTrue(json.startsWith("{\"schemaVersion\":1,\"mode\":\"estimate\","), json)
    assertTrue("\"contributors\":[" in json, json)
    assertNotNull(report.minimumWffVersion)
  }

  @Test
  fun contributorsSerializeEveryField() {
    val json = WffEstimate.report(bundle(caseScene, "case_steel.png" to png(1024, 1024))).toJson()
    assertTrue(
      "{\"resource\":\"drawable/case_steel\",\"kind\":\"image\",\"format\":\"png\",\"width\":1024," +
        "\"height\":1024,\"fileBytes\":33,\"decodedBytes\":4194304,\"activeBytes\":4194304," +
        "\"ambientBytes\":4194304,\"renderedMaxWidth\":450,\"renderedMaxHeight\":450," +
        "\"missing\":false,\"suggestion\":\"downscale" in json,
      json,
    )
  }

  @Test
  fun anAnimationThumbnailIsAReferenceNotALeftover() {
    val scene =
      """<PartAnimatedImage x="0" y="0" width="100" height="100"><AnimatedImage resource="anim" format="WEBP" thumbnail="thumb"/></PartAnimatedImage>"""
    val report =
      WffEstimate.report(bundle(scene, "anim.png" to png(100, 100), "thumb.png" to png(100, 100)))
    val thumb =
      report.check(MEMORY_FOOTPRINT)!!.contributors!!.single { it.resource == "drawable/thumb" }
    assertTrue(thumb.activeBytes > 0, "$thumb")
    assertFalse("not referenced" in thumb.suggestion.orEmpty(), "${thumb.suggestion}")

    val missing = WffEstimate.report(bundle(scene, "anim.png" to png(100, 100)))
    assertTrue(
      missing.check(MEMORY_FOOTPRINT)!!.findings.any {
        it.severity == FindingSeverity.ERROR && "thumb" in it.message
      },
    )
  }

  @Test
  fun bitmapGlyphsCostOnlyWhereTextUsesTheirFont() {
    val fonts =
      """<BitmapFonts><BitmapFont name="digits"><Character name="0" resource="glyph" width="2000" height="2000"/></BitmapFont></BitmapFonts>"""
    fun face(scene: String) =
      """<WatchFace width="450" height="450">$fonts<Scene>$scene</Scene></WatchFace>"""
    fun estimate(scene: String) =
      TestBundles.bundle().toMutableMap().let { b ->
        b[WffBundleLayout.WATCH_FACE_PATH] = face(scene).encodeToByteArray()
        b["res/drawable/glyph.png"] = png(2000, 2000)
        WffEstimate.report(b)
      }

    val unused = estimate("")
    val glyph = unused.check(MEMORY_FOOTPRINT)!!.contributors!!.single()
    assertEquals(0, glyph.cost, "an unused bitmap font costs nothing")
    assertTrue(unused.passed, unused.toJson())

    val used =
      estimate(
        """<PartText x="0" y="0" width="450" height="450"><Text><BitmapFont family="digits" size="40"><Template>%s<Parameter expression="[HOUR_0_23]"/></Template></BitmapFont></Text></PartText>""",
      )
    val drawn = used.check(MEMORY_FOOTPRINT)!!.contributors!!.single()
    assertEquals(2000L * 2000 * 4, drawn.activeBytes)
    assertEquals(drawn.activeBytes, drawn.ambientBytes)
  }

  @Test
  fun aTintOnTheContainerTintsTheImage() {
    val scene =
      """<PartImage x="0" y="0" width="450" height="450" tintColor="#ffffff"><Image resource="hand"/></PartImage>"""
    val report = WffEstimate.report(bundle(scene, "hand.png" to png(450, 450)))
    val hand = report.check(MEMORY_FOOTPRINT)!!.contributors!!.single()
    assertTrue(hand.ambientBytes > 0)
    assertNull(hand.suggestion, "a tinted image is not full-colour in ambient")
  }

  @Test
  fun aDrawableTheManifestUsesIsNotALeftover() {
    val b = bundle("", "icon.png" to png(48, 48)).toMutableMap()
    b[WffBundleLayout.MANIFEST_PATH] =
      b.getValue(WffBundleLayout.MANIFEST_PATH)
        .decodeToString()
        .replaceFirst("<application", "<application android:icon=\"@drawable/icon\"")
        .encodeToByteArray()
    val contributors = WffEstimate.report(b).check(MEMORY_FOOTPRINT)!!.contributors!!
    assertTrue(contributors.none { it.resource == "drawable/icon" }, "$contributors")
  }

  @Test
  fun aScaledContainerDrawsTheImageLarger() {
    val scaled =
      """<PartImage x="0" y="0" width="100" height="100" scaleX="4" scaleY="4"><Image resource="art"/></PartImage>"""
    val c =
      WffEstimate.report(bundle(scaled, "art.png" to png(400, 400)))
        .check(MEMORY_FOOTPRINT)!!
        .contributors!!
        .single()
    assertEquals(400, c.renderedMaxWidth)
    assertFalse("downscale" in c.suggestion.orEmpty(), "${c.suggestion}")

    val animated =
      """<PartImage x="0" y="0" width="100" height="100"><Transform target="scaleX" value="2"/><Image resource="art"/></PartImage>"""
    val a =
      WffEstimate.report(bundle(animated, "art.png" to png(400, 400)))
        .check(MEMORY_FOOTPRINT)!!
        .contributors!!
        .single()
    assertNull(a.renderedMaxWidth, "an animated scale leaves the drawn size unknown")
    assertFalse("downscale" in a.suggestion.orEmpty(), "${a.suggestion}")
  }
}
