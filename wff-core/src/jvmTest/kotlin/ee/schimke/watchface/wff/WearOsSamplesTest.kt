package ee.schimke.watchface.wff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round-trips and validates the Apache-2.0 wear-os-samples watch faces (see the NOTICE next to
 * them).
 */
class WearOsSamplesTest {
  // Minimum WFF version per sample according to WffSchema. verify against each sample's manifest.
  private val expectedMinimum =
    mapOf(
      "SimpleAnalog" to WffVersion.V1,
      "SimpleDigital" to WffVersion.V1,
      "Complications" to WffVersion.V2,
      "Flavors" to WffVersion.V2,
      "Weather" to WffVersion.V2,
      "PhotosMulti" to WffVersion.V2,
      "PhotosMask" to WffVersion.V2,
    )

  private fun read(path: String): String =
    checkNotNull(javaClass.getResource("/wear-os-samples/$path")) { "missing $path" }
      .readText(Charsets.UTF_8)

  @Test
  fun watchFacesRoundTrip() {
    for (name in expectedMinimum.keys) {
      val src = read("$name/raw/watchface.xml")
      val doc = WffXmlParser.parse(src)
      val once = WffXmlWriter.write(doc)
      val reparsed = WffXmlParser.parse(once)
      assertEquals(doc, reparsed, "$name: parse(write(x)) != x")
      assertEquals(once, WffXmlWriter.write(reparsed), "$name: writer is not idempotent")
    }
  }

  @Test
  fun attributeOrderAndTextSurviveRoundTrip() {
    for (name in expectedMinimum.keys) {
      val src = WffXmlParser.parse(read("$name/raw/watchface.xml"))
      val out = WffXmlParser.parse(WffXmlWriter.write(src))
      fun flatten(e: WffElement): List<String> =
        listOf("${e.name}${e.attributes.map { it.name to it.value }}${e.textContent()}") +
          e.childElements.flatMap(::flatten)
      assertEquals(flatten(src.root), flatten(out.root), name)
      assertEquals(src.prolog, out.prolog, "$name licence header")
    }
  }

  @Test
  fun watchFaceInfoRoundTrips() {
    for (name in expectedMinimum.keys) {
      val src = read("$name/xml/watch_face_info.xml")
      val doc = WffXmlParser.parse(src)
      val once = doc.toXml()
      assertEquals(once, WffXmlParser.parse(once).toXml(), name)
      val info = WffWatchFaceInfo.fromDocument(doc)
      assertTrue(info.preview.startsWith("@drawable/"), name)
      assertEquals(emptyList(), info.extra, "$name has unmodelled WatchFaceInfo elements")
    }
  }

  @Test
  fun samplesValidateAtTheirMinimumVersion() {
    for ((name, min) in expectedMinimum) {
      val doc = WffXmlParser.parse(read("$name/raw/watchface.xml"))
      assertEquals(min, WffValidator.minimumVersion(doc), "$name minimum version")
      val diagnostics = WffValidator.check(doc, min)
      assertEquals(emptyList(), diagnostics.filter { it.severity == WffSeverity.ERROR }, name)
      assertEquals(emptyList(), diagnostics, "$name should have no warnings either")
      assertEquals(emptyList(), WffValidator.errors(doc, WffVersion.V4), name)
    }
  }

  @Test
  fun v2SamplesFailAtV1() {
    for ((name, min) in expectedMinimum.filterValues { it > WffVersion.V1 }) {
      val doc = WffXmlParser.parse(read("$name/raw/watchface.xml"))
      assertTrue(WffValidator.errors(doc, WffVersion.V1).isNotEmpty(), "$name should need > v1")
    }
  }
}
