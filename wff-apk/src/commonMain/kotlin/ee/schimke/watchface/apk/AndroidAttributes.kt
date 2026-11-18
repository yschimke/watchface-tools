package ee.schimke.watchface.apk

/** How an `android:` attribute's text becomes a typed value, following the attribute's format. */
internal enum class AttrKind {
  /** Text, no references (`android:name`). */
  STRING,
  /** Text or a reference (`android:label`). */
  STRING_OR_REF,
  /** Decimal or `0x` integer, or a reference. */
  INT,
  /** An integer, or text such as a release codename (`android:minSdkVersion`). */
  INT_OR_STRING,
  BOOL,
  REF,
  /** `android:value`: bool, integer, colour, reference or text, by what the text looks like. */
  ANY,
}

/**
 * The framework attributes a Watch Face manifest (and `res/xml` documents) can use, with the
 * resource ids `android.jar` assigns them. An attribute's id decides its place in the binary XML
 * resource map and the order the platform reads attributes in, so a missing entry is an error
 * rather than a guess. Ids checked against `aapt2 dump resources android.jar` (API 36).
 */
internal object AndroidAttributes {
  const val NAMESPACE = "http://schemas.android.com/apk/res/android"

  class Attr(val id: Int, val kind: AttrKind)

  private val table =
    mapOf(
      "theme" to Attr(0x01010000, AttrKind.REF),
      "label" to Attr(0x01010001, AttrKind.STRING_OR_REF),
      "icon" to Attr(0x01010002, AttrKind.REF),
      "name" to Attr(0x01010003, AttrKind.STRING),
      "hasCode" to Attr(0x0101000c, AttrKind.BOOL),
      "debuggable" to Attr(0x0101000f, AttrKind.BOOL),
      "description" to Attr(0x01010020, AttrKind.STRING_OR_REF),
      "value" to Attr(0x01010024, AttrKind.ANY),
      "resource" to Attr(0x01010025, AttrKind.REF),
      "minSdkVersion" to Attr(0x0101020c, AttrKind.INT_OR_STRING),
      "versionCode" to Attr(0x0101021b, AttrKind.INT),
      "versionName" to Attr(0x0101021c, AttrKind.STRING),
      "targetSdkVersion" to Attr(0x01010270, AttrKind.INT_OR_STRING),
      "maxSdkVersion" to Attr(0x01010271, AttrKind.INT),
      "allowBackup" to Attr(0x01010280, AttrKind.BOOL),
      "glEsVersion" to Attr(0x01010281, AttrKind.INT),
      "required" to Attr(0x0101028e, AttrKind.BOOL),
      "roundIcon" to Attr(0x0101052c, AttrKind.REF),
      "compileSdkVersion" to Attr(0x01010572, AttrKind.INT),
      "compileSdkVersionCodename" to Attr(0x01010573, AttrKind.STRING),
    )

  fun find(name: String): Attr? = table[name]
}
