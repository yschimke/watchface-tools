package ee.schimke.watchface.apk

import ee.schimke.watchface.packager.PackageTarget
import ee.schimke.watchface.wff.WffAssets
import ee.schimke.watchface.wff.WffBundle
import ee.schimke.watchface.wff.WffBundleLayout
import ee.schimke.watchface.wff.WffManifest
import ee.schimke.watchface.wff.WffResourceFile
import ee.schimke.watchface.wff.WffVersion
import ee.schimke.watchface.wff.WffWatchFaceInfo
import ee.schimke.watchface.wff.WffXmlParser

/** A small but real face: text, a preview image, a font-less layout, strings in two locales. */
object TestBundles {
  const val FACE_XML =
    """<WatchFace width="450" height="450"><Scene><PartText x="0" y="0" width="450" height="450"><Text><Font family="SANS-SERIF" size="40" color="#ffffff"><Template>%s<Parameter expression="[HOUR_0_23_Z]"/></Template></Font></Text></PartText></Scene></WatchFace>"""

  private const val PNG_HEX =
    "89504e470d0a1a0a0000000d4948445200000004000000040806000000a9f19e7e0000001249444154789c63500858f01f19" +
      "33902e000048f820f16a5a355e0000000049454e44ae426082"

  val png: ByteArray = PNG_HEX.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

  fun bundle(
    packageName: String = "com.example.market.watchfacepush.face",
    extraFiles: List<WffResourceFile> = emptyList(),
    strings: Map<String, Map<String, String>> =
      mapOf("" to mapOf("hello" to "Hello"), "fr" to mapOf("hello" to "Bonjour")),
  ): Map<String, ByteArray> =
    WffBundleLayout.sourceFiles(
      WffBundle(
        manifest = WffManifest(packageName, label = "Test face", wffVersion = WffVersion.V2),
        watchFace = WffXmlParser.parse(FACE_XML),
        watchFaceInfo = WffWatchFaceInfo(editable = true),
        assets =
          WffAssets(
            files = listOf(WffResourceFile("drawable", "preview.png", png)) + extraFiles,
            strings = strings,
          ),
      ),
    )

  val pushTarget = PackageTarget.WatchFacePush("com.example.market", "face")
}
