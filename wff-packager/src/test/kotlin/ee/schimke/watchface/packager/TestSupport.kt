package ee.schimke.watchface.packager

import ee.schimke.watchface.wff.WffAssets
import ee.schimke.watchface.wff.WffBundle
import ee.schimke.watchface.wff.WffBundleLayout
import ee.schimke.watchface.wff.WffDocument
import ee.schimke.watchface.wff.WffElement
import ee.schimke.watchface.wff.WffManifest
import ee.schimke.watchface.wff.WffResourceFile
import ee.schimke.watchface.wff.WffValidator
import ee.schimke.watchface.wff.WffVersion
import ee.schimke.watchface.wff.WffWatchFaceInfo
import ee.schimke.watchface.wff.WffXmlParser
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assumptions.assumeTrue

object TestSupport {
  val sdk: AndroidSdk? by lazy { AndroidSdk.locate() }

  /** Skips the calling test, with a message saying how to enable it, when there is no SDK. */
  fun requireSdk(): AndroidSdk {
    // CI sets WFF_REQUIRE_SDK so a missing SDK is a failure there, not a silent skip.
    check(sdk != null || System.getenv("WFF_REQUIRE_SDK") == null) {
      "WFF_REQUIRE_SDK is set but no Android SDK was found: ${AndroidSdk.INSTALL_HINT}"
    }
    assumeTrue(sdk != null) {
      "Android SDK not found (need aapt2, zipalign, android.jar under ANDROID_HOME or " +
        "ANDROID_SDK_ROOT): ${AndroidSdk.INSTALL_HINT}"
    }
    return sdk!!
  }

  val samplesDir: File = File(checkNotNull(System.getProperty("wff.samples.dir")))

  val sampleNames: List<String> =
    samplesDir.listFiles { f -> f.isDirectory }!!.map { it.name }.sorted()

  fun png(size: Int = 64): ByteArray {
    val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.color = Color(0x2255AA)
    g.fillOval(0, 0, size, size)
    g.dispose()
    return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
  }

  /** Builds a source bundle from a wear-os-samples watch face (`raw/watchface.xml`, `xml/...`). */
  fun sampleBundle(
    name: String,
    packageName: String = "ee.schimke.sample.${name.lowercase()}",
  ): Map<String, ByteArray> {
    val dir = File(samplesDir, name)
    val face = WffXmlParser.parse(File(dir, "raw/watchface.xml").readText())
    val info =
      WffWatchFaceInfo.fromDocument(
        WffXmlParser.parse(File(dir, "xml/watch_face_info.xml").readText()),
      )
    val version = WffValidator.minimumVersion(face)
    return WffBundleLayout.sourceFiles(
      WffBundle(
        manifest = WffManifest(packageName, label = name, wffVersion = version),
        watchFace = face,
        watchFaceInfo = info,
        assets =
          WffAssets(
            // The sample fixtures only ship the XML, so stand in for every referenced image.
            files =
              (referencedImages(face) + "preview").sorted().map {
                WffResourceFile("drawable", "$it.png", png())
              },
          ),
      ),
    )
  }

  /** Names of `res/drawable` images the watch face references (not `[COMPLICATION.*]` data). */
  private fun referencedImages(doc: WffDocument): Set<String> {
    val out = sortedSetOf<String>()

    fun walk(e: WffElement) {
      for (a in e.attributes) {
        if ((a.name == "resource" || a.name == "defaultImageResource") && !a.value.startsWith("["))
          out += a.value
      }
      e.childElements.forEach(::walk)
    }
    walk(doc.root)
    return out
  }

  fun pushOptions(
    versionCode: Int = 7,
    keyDir: File,
    marketplace: String = "com.example.market",
    face: String = "face",
  ) =
    PackagerOptions(
      PackageTarget.WatchFacePush(marketplace, face),
      versionCode,
      SigningConfig.GeneratedDebugKey(keyDir),
    )

  fun fakeValidators(
    token: String? = "FAKE-TOKEN",
    failures: List<PackagingDiagnostic> = emptyList(),
  ) =
    Validators(
      push = { _, _ -> PushValidation(if (failures.isEmpty()) token else null, failures) },
      memory = { _, _ -> MemoryFootprintResult(MemoryFootprintReport(1, 2, 3), null) },
      format = { _, _ -> emptyList() },
    )

  fun WffVersion.label() = "v$number"
}
