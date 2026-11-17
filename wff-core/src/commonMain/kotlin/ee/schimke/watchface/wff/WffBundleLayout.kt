package ee.schimke.watchface.wff

/**
 * Values for the `AndroidManifest.xml` of a declarative watch face APK. Mirrors what `ensureWffApk`
 * in compose-ai-tools' `DwfApkDaemonBridge` writes today (no code, watch feature, one
 * `com.google.wear.watchface.format.version` property).
 */
public data class WffManifest(
  val packageName: String,
  val label: String,
  val wffVersion: WffVersion,
  val versionCode: Int = 1,
  val versionName: String = "1.0",
  val minSdk: Int = WffBundleLayout.minSdkFor(wffVersion),
  val targetSdk: Int = WffBundleLayout.DEFAULT_TARGET_SDK,
)

/**
 * `res/xml/watch_face_info.xml`. [preview] is a resource reference such as `@drawable/preview`.
 * Booleans left null are omitted. [extra] carries unrecognised elements verbatim.
 */
public data class WffWatchFaceInfo(
  val preview: String = "@drawable/preview",
  val editable: Boolean? = null,
  val multipleInstancesAllowed: Boolean? = null,
  val flavorsSupported: Boolean? = null,
  val extra: List<WffElement> = emptyList(),
) {
  public fun toDocument(): WffDocument {
    fun value(name: String, v: String) = WffElement(name, listOf(WffAttribute("value", v)))

    val children = buildList {
      add(value("Preview", preview))
      editable?.let { add(value("Editable", it.toString())) }
      multipleInstancesAllowed?.let { add(value("MultipleInstancesAllowed", it.toString())) }
      flavorsSupported?.let { add(value("FlavorsSupported", it.toString())) }
      addAll(extra)
    }
    return WffDocument(WffElement("WatchFaceInfo", children = children))
  }

  public companion object {
    private val known = setOf("Preview", "Editable", "MultipleInstancesAllowed", "FlavorsSupported")

    public fun fromDocument(doc: WffDocument): WffWatchFaceInfo {
      require(doc.root.name == "WatchFaceInfo") { "Root must be <WatchFaceInfo>" }
      fun v(n: String) = doc.root.firstChild(n)?.attr("value")
      return WffWatchFaceInfo(
        preview = v("Preview") ?: "@drawable/preview",
        editable = v("Editable")?.toBooleanStrictOrNull(),
        multipleInstancesAllowed = v("MultipleInstancesAllowed")?.toBooleanStrictOrNull(),
        flavorsSupported = v("FlavorsSupported")?.toBooleanStrictOrNull(),
        extra = doc.root.childElements.filter { it.name !in known },
      )
    }
  }
}

/**
 * A resource file below `res/`. [directory] is the resource directory including qualifiers
 * (`drawable`, `drawable-fr`, `font`), [fileName] includes the extension.
 */
public class WffResourceFile(
  public val directory: String,
  public val fileName: String,
  public val bytes: ByteArray,
)

/**
 * Assets that accompany the document. [strings] maps a locale qualifier ("" = default, "fr") to
 * names.
 */
public class WffAssets(
  public val files: List<WffResourceFile> = emptyList(),
  public val strings: Map<String, Map<String, String>> = emptyMap(),
)

/** Everything needed to lay out a watch face's source bundle. */
public class WffBundle(
  public val manifest: WffManifest,
  public val watchFace: WffDocument,
  public val watchFaceInfo: WffWatchFaceInfo = WffWatchFaceInfo(),
  public val assets: WffAssets = WffAssets(),
)

/**
 * Describes the source layout of a Declarative Watch Face resource bundle and produces its files.
 * There is no aapt2 here; turning this map into an APK is the (later) JVM `wff-packager` module's
 * job.
 *
 * ```
 * AndroidManifest.xml
 * res/raw/watchface.xml
 * res/xml/watch_face_info.xml
 * res/drawable[-qualifiers]/preview.png   (WffResourceFile)
 * res/font/name.ttf                 (WffResourceFile)
 * res/values[-locale]/strings.xml
 * ```
 */
public object WffBundleLayout {
  public const val MANIFEST_PATH: String = "AndroidManifest.xml"
  public const val WATCH_FACE_PATH: String = "res/raw/watchface.xml"
  public const val WATCH_FACE_INFO_PATH: String = "res/xml/watch_face_info.xml"
  public const val WFF_VERSION_PROPERTY: String = "com.google.wear.watchface.format.version"
  public const val DEFAULT_TARGET_SDK: Int = 35

  /**
   * Minimum SDK the manifest declares per format version. verify: V1 = Wear OS 4 (API 33), V2 =
   * Wear OS 5 (API 34), V3 = API 35, V4 = API 36. `ensureWffApk` in compose-ai-tools declares 33
   * even for version 2 because it only renders; real packaging should use this table.
   */
  public fun minSdkFor(version: WffVersion): Int =
    when (version) {
      WffVersion.V1 -> 33
      WffVersion.V2 -> 34
      WffVersion.V3 -> 35 // verify
      WffVersion.V4 -> 36 // verify
    }

  public fun resourcePath(file: WffResourceFile): String = "res/${file.directory}/${file.fileName}"

  public fun stringsPath(locale: String): String =
    if (locale.isEmpty()) "res/values/strings.xml" else "res/values-$locale/strings.xml"

  public fun manifestDocument(m: WffManifest): WffDocument {
    fun android(name: String, value: String) = WffAttribute("android:$name", value)
    val root =
      WffElement(
        "manifest",
        listOf(
          WffAttribute("xmlns:android", "http://schemas.android.com/apk/res/android"),
          WffAttribute("package", m.packageName),
          android("versionCode", m.versionCode.toString()),
          android("versionName", m.versionName),
        ),
        listOf(
          WffElement(
            "uses-sdk",
            listOf(
              android("minSdkVersion", m.minSdk.toString()),
              android("targetSdkVersion", m.targetSdk.toString()),
            ),
          ),
          WffElement(
            "uses-feature",
            listOf(android("name", "android.hardware.type.watch")),
          ),
          WffElement(
            "application",
            listOf(android("hasCode", "false"), android("label", m.label)),
            listOf(
              WffElement(
                "property",
                listOf(
                  android("name", WFF_VERSION_PROPERTY),
                  android("value", m.wffVersion.number.toString()),
                ),
              ),
            ),
          ),
        ),
      )
    return WffDocument(root)
  }

  public fun stringsDocument(strings: Map<String, String>): WffDocument =
    WffDocument(
      WffElement(
        "resources",
        children =
          strings.entries
            .sortedBy { it.key }
            .map { (k, v) ->
              WffElement("string", listOf(WffAttribute("name", k)), listOf(WffText(v)))
            },
      ),
    )

  /**
   * The source files of [bundle], keyed by bundle-relative path. Iteration order is deterministic
   * (manifest, watch face, info, strings by locale, then resource files sorted by path).
   */
  public fun sourceFiles(bundle: WffBundle): Map<String, ByteArray> {
    val out = LinkedHashMap<String, ByteArray>()
    out[MANIFEST_PATH] = manifestDocument(bundle.manifest).toXml().encodeToByteArray()
    out[WATCH_FACE_PATH] = bundle.watchFace.toXml().encodeToByteArray()
    out[WATCH_FACE_INFO_PATH] = bundle.watchFaceInfo.toDocument().toXml().encodeToByteArray()
    for (locale in bundle.assets.strings.keys.sorted()) {
      out[stringsPath(locale)] =
        stringsDocument(bundle.assets.strings.getValue(locale)).toXml().encodeToByteArray()
    }
    for (f in bundle.assets.files.sortedBy(::resourcePath)) {
      val p = resourcePath(f)
      require(p !in out) { "duplicate bundle path $p" }
      out[p] = f.bytes
    }
    return out
  }
}
