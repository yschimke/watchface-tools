package ee.schimke.watchface.wff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WffBundleLayoutTest {
  @Test
  fun producesExpectedPaths() {
    val face = WffXmlParser.parse("""<WatchFace width="450" height="450"><Scene/></WatchFace>""")
    val bundle =
      WffBundle(
        manifest = WffManifest("ee.example.face", "Example \"Face\"", WffVersion.V2),
        watchFace = face,
        watchFaceInfo = WffWatchFaceInfo(editable = true),
        assets =
          WffAssets(
            files =
              listOf(
                WffResourceFile("drawable", "preview.png", byteArrayOf(1, 2)),
                WffResourceFile("font", "f.ttf", byteArrayOf(3)),
                WffResourceFile("drawable-fr", "preview.png", byteArrayOf(4)),
              ),
            strings = mapOf("" to mapOf("b" to "B", "a" to "A & co"), "fr" to mapOf("a" to "Un")),
          ),
      )
    val files = WffBundleLayout.sourceFiles(bundle)
    assertEquals(
      listOf(
        "AndroidManifest.xml",
        "res/raw/watchface.xml",
        "res/xml/watch_face_info.xml",
        "res/values/strings.xml",
        "res/values-fr/strings.xml",
        "res/drawable-fr/preview.png",
        "res/drawable/preview.png",
        "res/font/f.ttf",
      ),
      files.keys.toList(),
    )
    val manifest = files.getValue("AndroidManifest.xml").decodeToString()
    assertTrue(manifest.contains("package=\"ee.example.face\""))
    assertTrue(manifest.contains("android:minSdkVersion=\"34\""))
    assertTrue(manifest.contains("android:name=\"com.google.wear.watchface.format.version\""))
    assertTrue(manifest.contains("android:value=\"2\""))
    assertTrue(manifest.contains("android:label=\"Example &quot;Face&quot;\""))
    assertEquals(face.toXml(), files.getValue("res/raw/watchface.xml").decodeToString())
    val info = files.getValue("res/xml/watch_face_info.xml").decodeToString()
    assertTrue(info.contains("<Preview value=\"@drawable/preview\"/>"))
    assertTrue(info.contains("<Editable value=\"true\"/>"))
    val strings = files.getValue("res/values/strings.xml").decodeToString()
    assertTrue(strings.indexOf("name=\"a\"") < strings.indexOf("name=\"b\""))
    assertTrue(strings.contains(">A &amp; co</string>"))
  }

  @Test
  fun watchFaceInfoRoundTripsThroughModel() {
    val xml =
      """<WatchFaceInfo><Preview value="@drawable/p"/><Editable value="false"/><Other value="x"/></WatchFaceInfo>"""
    val info = WffWatchFaceInfo.fromDocument(WffXmlParser.parse(xml))
    assertEquals("@drawable/p", info.preview)
    assertEquals(false, info.editable)
    assertEquals(1, info.extra.size)
    assertEquals(info, WffWatchFaceInfo.fromDocument(info.toDocument()))
  }

  @Test
  fun androidStringsAreEscaped() {
    assertEquals("It\\'s \\\"on\\\"", WffBundleLayout.androidStringEscape("It's \"on\""))
    assertEquals("\\@home", WffBundleLayout.androidStringEscape("@home"))
    assertEquals("a@b\\nc", WffBundleLayout.androidStringEscape("a@b\nc"))
    val xml = WffBundleLayout.stringsDocument(mapOf("label" to "It's")).toXml()
    assertTrue(xml.contains("<string name=\"label\">It\\'s</string>"), xml)
  }

  /**
   * developer.android.com/training/wearables/wff, "Versioning in WFF": WFF 1 = Wear OS 4 = API 33,
   * 2 = Wear OS 5 = API 34, 3 = Wear OS 5.1 = API 35, 4 = Wear OS 6 = API 36. Version 5 is not in
   * Google's table; 36 is an assumption (see `WffBundleLayout.minSdkFor`).
   */
  @Test
  fun minSdkTableMatchesGoogleVersionTable() {
    assertEquals(33, WffBundleLayout.minSdkFor(WffVersion.V1))
    assertEquals(34, WffBundleLayout.minSdkFor(WffVersion.V2))
    assertEquals(35, WffBundleLayout.minSdkFor(WffVersion.V3))
    assertEquals(36, WffBundleLayout.minSdkFor(WffVersion.V4))
    assertEquals(36, WffBundleLayout.minSdkFor(WffVersion.V5))
    // a newer format never needs an older platform
    val all = WffVersion.entries.map { WffBundleLayout.minSdkFor(it) }
    assertEquals(all.sorted(), all)
  }
}
