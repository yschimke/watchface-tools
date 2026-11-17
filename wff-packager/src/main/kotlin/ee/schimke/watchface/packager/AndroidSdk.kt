package ee.schimke.watchface.packager

import java.io.File

/**
 * The pieces of an Android SDK packaging needs: `aapt2` and `zipalign` from `build-tools/<v>` and
 * `android.jar` from `platforms/android-<api>`.
 */
public class AndroidSdk(
  public val root: File,
  public val aapt2: File,
  public val zipalign: File?,
  public val androidJar: File,
) {
  public companion object {
    /** Installs everything [locate] needs; shown in diagnostics and used by CI. */
    public const val INSTALL_HINT: String =
      "sdkmanager \"build-tools;36.0.0\" \"platforms;android-36\" " +
        "(and point ANDROID_HOME or ANDROID_SDK_ROOT at the SDK)"

    /** Finds the SDK from ANDROID_HOME / ANDROID_SDK_ROOT (or [roots]); null if unusable. */
    public fun locate(
      roots: List<File> =
        listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").mapNotNull {
          System.getenv(it)?.takeIf(String::isNotBlank)?.let(::File)
        } + listOfNotNull(sdkDirFromLocalProperties()),
    ): AndroidSdk? = roots.firstNotNullOfOrNull(::fromRoot)

    private fun sdkDirFromLocalProperties(): File? =
      generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { File(it, "local.properties") }
        .firstOrNull { it.isFile }
        ?.let { f ->
          java.util
            .Properties()
            .apply { f.reader().use(::load) }
            .getProperty("sdk.dir")
            ?.let(::File)
        }

    /** Like [locate] but fails with install instructions. */
    public fun require(): AndroidSdk =
      locate()
        ?: throw PackagingException(
          "sdk",
          "No usable Android SDK: need build-tools with aapt2 and a platforms/android-N/android.jar " +
            "under ANDROID_HOME, ANDROID_SDK_ROOT or local.properties sdk.dir. Install with: $INSTALL_HINT",
        )

    private fun fromRoot(root: File): AndroidSdk? {
      val tools =
        root
          .resolve("build-tools")
          .listFiles { f -> f.isDirectory && f.resolve("aapt2").canExecute() }
          ?.sortedWith(compareByDescending { versionKey(it.name) })
          ?.firstOrNull() ?: return null
      val jar =
        root
          .resolve("platforms")
          .listFiles { f -> f.isDirectory && f.resolve("android.jar").isFile }
          ?.sortedByDescending { it.name.removePrefix("android-").toIntOrNull() ?: 0 }
          ?.firstOrNull()
          ?.resolve("android.jar") ?: return null
      return AndroidSdk(
        root,
        tools.resolve("aapt2"),
        tools.resolve("zipalign").takeIf { it.canExecute() },
        jar,
      )
    }

    private fun versionKey(name: String): Long =
      name.split('.', '-').take(3).fold(0L) { acc, p ->
        acc * 1000 + (p.toLongOrNull()?.coerceAtMost(999) ?: 0)
      }
  }
}
