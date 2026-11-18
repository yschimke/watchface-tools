package ee.schimke.watchface.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The assumptions `:bom`, the release plan and `expected-poms.py` share, pinned against the real
 * build files: a published module is a top-level project whose directory is its name (so its
 * artifact id, project path and directory agree), and a KMP module's variants follow its targets.
 */
class PublishedArtifactsTest {
  private val repoRoot =
    generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
      .first { it.resolve("settings.gradle.kts").isFile && it.resolve("bom").isDirectory }

  private fun publishedModules(): List<String> {
    val settings = repoRoot.resolve("settings.gradle.kts").readText()
    return Regex("""include\("(:[^"]+)"\)""").findAll(settings).map { it.groupValues[1] }.filter {
      path ->
      val build = repoRoot.resolve(path.removePrefix(":")).resolve("build.gradle.kts")
      // With its closing quote: the platform plugin id shares this one's prefix.
      build.isFile && build.readText().contains("watchface.maven-publishing\")")
    }.toList()
  }

  @Test
  fun `published modules are top-level projects named for their directory`() {
    val modules = publishedModules()
    // A guard on the guard: if the walk stops finding modules the rest passes vacuously.
    assertEquals(listOf(":wff-apk", ":wff-core", ":wff-packager"), modules.sorted())
    for (path in modules) assertEquals(1, path.removePrefix(":").split(":").size, path)
  }

  @Test
  fun `a KMP module yields its target coordinates and a JVM module only itself`() {
    val core = repoRoot.resolve("wff-core/build.gradle.kts").readText()
    assertEquals(
      listOf("wff-core", "wff-core-jvm", "wff-core-wasm-js"),
      PublishedArtifacts.coordinates("wff-core", core),
    )
    val apk = repoRoot.resolve("wff-apk/build.gradle.kts").readText()
    assertEquals(
      listOf("wff-apk", "wff-apk-jvm", "wff-apk-wasm-js"),
      PublishedArtifacts.coordinates("wff-apk", apk),
    )
    val packager = repoRoot.resolve("wff-packager/build.gradle.kts").readText()
    assertEquals(listOf("wff-packager"), PublishedArtifacts.coordinates("wff-packager", packager))
  }
}
