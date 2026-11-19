package ee.schimke.watchface.packager

import ee.schimke.watchface.wff.WffXmlParser
import kotlin.test.Test
import kotlin.test.assertTrue

class PushRulesManifestTest {
  @Test
  fun rejectsResourceReferenceInFormatVersionProperty() {
    val manifest =
      WffXmlParser.parse(
        """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.watchfacepush.test">
          <uses-sdk android:minSdkVersion="34"/>
          <application android:hasCode="false">
            <property android:name="com.google.wear.watchface.format.version" android:value="@integer/watchface_format_version"/>
          </application>
        </manifest>
        """
          .trimIndent(),
      )

    val diagnostics = PushRules.checkManifest(manifest)
    assertTrue(
      diagnostics.any { it.message.contains("must be a literal integer") },
      "Expected literal integer error, got $diagnostics",
    )
  }

  @Test
  fun acceptsLiteralIntegerFormatVersionProperty() {
    val manifest =
      WffXmlParser.parse(
        """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.watchfacepush.test">
          <uses-sdk android:minSdkVersion="34"/>
          <application android:hasCode="false">
            <property android:name="com.google.wear.watchface.format.version" android:value="2"/>
          </application>
        </manifest>
        """
          .trimIndent(),
      )

    val diagnostics = PushRules.checkManifest(manifest)
    assertTrue(diagnostics.isEmpty(), "Expected no errors, got $diagnostics")
  }
}
