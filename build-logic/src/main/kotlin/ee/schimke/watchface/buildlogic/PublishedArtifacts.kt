package ee.schimke.watchface.buildlogic

/** The coordinates a published module produces, derived from its build script. */
object PublishedArtifacts {
  /** Gradle target accessor to the artifactId suffix of the publication it adds. */
  private val kmpTargetSuffixes = mapOf("jvm" to "jvm", "wasmJs" to "wasm-js")

  /**
   * [artifactId] and, for a Kotlin Multiplatform module, one coordinate per declared target
   * (`wff-core`, `wff-core-jvm`, `wff-core-wasm-js`). A consumer names the root coordinate, but a
   * Maven consumer resolves a variant directly, so the BOM constrains all of them.
   */
  fun coordinates(artifactId: String, buildScript: String): List<String> {
    if (!buildScript.contains("kotlin.multiplatform")) return listOf(artifactId)
    val variants =
      kmpTargetSuffixes.mapNotNull { (accessor, suffix) ->
        val declared = Regex("^\\s*(@\\S+\\s+)?$accessor\\s*[({]", RegexOption.MULTILINE)
        if (declared.containsMatchIn(buildScript)) "$artifactId-$suffix" else null
      }
    return listOf(artifactId) + variants
  }
}
