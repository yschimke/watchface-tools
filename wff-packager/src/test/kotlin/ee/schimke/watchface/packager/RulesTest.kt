package ee.schimke.watchface.packager

import ee.schimke.watchface.wff.WffBundleLayout
import ee.schimke.watchface.wff.WffVersion
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** Rule checks run before any SDK tool, so none of these need the Android SDK. */
class RulesTest {
  @TempDir lateinit var tmp: File

  private val packager
    get() = WatchFacePackager(sdk = null, validators = TestSupport.fakeValidators())

  private fun rejected(bundle: Map<String, ByteArray>, options: PackagerOptions): String =
    assertFailsWith<PackagingException> { packager.pack(bundle, options) }.message!!

  private fun bundle() = TestSupport.sampleBundle("SimpleAnalog")

  private fun withManifest(edit: (String) -> String) =
    bundle().toMutableMap().also {
      it[WffBundleLayout.MANIFEST_PATH] =
        edit(it.getValue(WffBundleLayout.MANIFEST_PATH).decodeToString()).encodeToByteArray()
    }

  @Test
  fun packageNamingRule() {
    assertEquals(
      "com.example.market.watchfacepush.my_face",
      PushRules.packageName("com.example.market", "my_face"),
    )
    assertEquals(
      emptyList(),
      PushRules.checkPackageName(
        "com.example.market.watchfacepush.another_watchface",
        "com.example.market",
      ),
    )
    for (bad in
      listOf(
        "com.example.market.face",
        "com.example.market.watchfacepush.",
        "com.example.market.watchfacepush.a.b",
        "com.other.watchfacepush.face",
      )) {
      assertTrue(PushRules.checkPackageName(bad, "com.example.market").isNotEmpty(), bad)
    }
  }

  @Test
  fun pushTargetRejectsBadFaceAndMarketplaceNames() {
    for (face in listOf("", "1face", "my-face", "a.b", "has space")) {
      val msg = rejected(bundle(), TestSupport.pushOptions(keyDir = tmp, face = face))
      assertTrue("Face name" in msg, "$face -> $msg")
    }
    val msg = rejected(bundle(), TestSupport.pushOptions(keyDir = tmp, marketplace = "market"))
    assertTrue("Marketplace package" in msg, msg)
  }

  @Test
  fun rejectsMinSdkBelow33() {
    val msg =
      rejected(
        withManifest { it.replace("android:minSdkVersion=\"33\"", "android:minSdkVersion=\"30\"") },
        TestSupport.pushOptions(keyDir = tmp),
      )
    assertTrue("minSdkVersion is 30" in msg, msg)
  }

  @Test
  fun rejectsHasCodeNotFalse() {
    val msg =
      rejected(
        withManifest { it.replace("android:hasCode=\"false\"", "android:hasCode=\"true\"") },
        TestSupport.pushOptions(keyDir = tmp),
      )
    assertTrue("hasCode" in msg, msg)
  }

  @Test
  fun rejectsDisallowedManifestTags() {
    val msg =
      rejected(
        withManifest {
          it.replace(
            "<uses-feature",
            "<uses-permission android:name=\"android.permission.INTERNET\"/><uses-feature",
          )
        },
        TestSupport.pushOptions(keyDir = tmp),
      )
    assertTrue("<uses-permission> is not allowed" in msg, msg)
  }

  @Test
  fun rejectsSecondWatchFaceAndStrayFiles() {
    val two = bundle().toMutableMap()
    two["res/raw/second.xml"] = two.getValue(WffBundleLayout.WATCH_FACE_PATH)
    assertTrue("Exactly one watch face" in rejected(two, TestSupport.pushOptions(keyDir = tmp)))

    val stray = bundle().toMutableMap()
    stray["classes.dex"] = byteArrayOf(1)
    assertTrue("classes.dex" in rejected(stray, TestSupport.pushOptions(keyDir = tmp)))
  }

  @Test
  fun rejectsApkEntriesOutsideTheAllowedSet() {
    assertEquals(
      emptyList(),
      PushRules.checkApkEntries(
        listOf(
          "AndroidManifest.xml",
          "resources.arsc",
          "res/raw/watchface.xml",
          "META-INF/CERT.RSA",
        ),
      ),
    )
    val bad =
      PushRules.checkApkEntries(
        listOf("AndroidManifest.xml", "resources.arsc", "classes.dex", "assets/x"),
      )
    assertEquals(2, bad.size)
  }

  @Test
  fun versionCodeMustIncrease() {
    val o = PackagerOptions(PackageTarget.Sideload(), versionCode = 3, previousVersionCode = 3)
    assertTrue("greater than the previous 3" in rejected(bundle(), o))
    assertTrue(
      "versionCode must be >= 1" in
        rejected(bundle(), PackagerOptions(PackageTarget.Sideload(), 0)),
    )
  }

  @Test
  fun rejectsWatchFaceNewerThanDeclaredVersion() {
    // SimpleAnalog is v1; Complications needs v2. Declare v1 on a v2 face.
    val b = TestSupport.sampleBundle("Complications").toMutableMap()
    b[WffBundleLayout.MANIFEST_PATH] =
      b.getValue(WffBundleLayout.MANIFEST_PATH)
        .decodeToString()
        .replace("android:value=\"2\"", "android:value=\"1\"")
        .encodeToByteArray()
    val msg = rejected(b, TestSupport.pushOptions(keyDir = tmp))
    assertTrue("wff" in msg, msg)
  }

  @Test
  fun missingWffVersionPropertyIsReported() {
    val msg =
      rejected(
        withManifest { it.replace(WffBundleLayout.WFF_VERSION_PROPERTY, "some.other.property") },
        TestSupport.pushOptions(keyDir = tmp),
      )
    assertTrue(WffBundleLayout.WFF_VERSION_PROPERTY in msg, msg)
    assertTrue(WffVersion.V1.number == 1)
  }

  @Test
  fun wffValidatorRefusesDoctypeAndExternalEntities() {
    val secret = File(tmp, "secret.txt").also { it.writeText("TOP-SECRET") }
    val xml =
      """<?xml version="1.0"?>
      <!DOCTYPE WatchFace [<!ENTITY x SYSTEM "${secret.toURI()}">]>
      <WatchFace width="450" height="450">&x;</WatchFace>"""
        .trimIndent()
    val errors = GoogleWffValidator.validate(xml, WffVersion.V1)
    assertTrue(errors.isNotEmpty())
    assertTrue(errors.none { "TOP-SECRET" in it }, errors.toString())
    assertTrue(errors.any { "DOCTYPE" in it || "doctype" in it }, errors.toString())
  }
}
