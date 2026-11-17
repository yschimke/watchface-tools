package ee.schimke.watchface.buildlogic

/**
 * Which version each coordinate carries on a release where only some modules publish.
 *
 * A pure function over the three inputs a release has: the tag's version, the set of modules that
 * are publishing, and the record of what everything last published at (`publishing-manifest.json`,
 * written by the release job's plan from Maven Central). `WatchfaceMavenPublishingPlugin` uses it
 * to set `project.version`, which is what a POM names its project dependencies at; `:bom` uses it
 * to pin every constraint, so the two cannot disagree.
 */
object PublishedVersions {
  /**
   * The version [artifactId] carries.
   *
   * [publishSet] is `null` when the release publishes everything (a `workflow_dispatch` recovery
   * run, or any build with no plan), in which case everything takes [tagVersion].
   *
   * A module outside the publish set takes the version it last published at. Not finding one is an
   * error rather than a fallback to [tagVersion]: stamping the tag onto a module that is not being
   * uploaded publishes a POM naming a coordinate that does not exist.
   */
  fun resolve(
    artifactId: String,
    tagVersion: String,
    publishSet: Collection<String>?,
    manifestText: String,
  ): String {
    if (publishSet == null || artifactId in publishSet) return tagVersion
    return recordedVersion(artifactId, manifestText)
      ?: error(
        "$artifactId is not in the publish set and has no entry in publishing-manifest.json, " +
          "so there is no version it can safely carry. Publish it, or fix the manifest."
      )
  }

  /** The version the manifest records for [artifactId], or null. */
  fun recordedVersion(artifactId: String, manifestText: String): String? =
    Regex("\"${Regex.escape(artifactId)}\"\\s*:\\s*\"([^\"]+)\"")
      .find(manifestText)
      ?.groupValues
      ?.get(1)

  /**
   * Parses `-Pwatchface.publishSet`. Absent (`null`) means no plan ran: publish everything. Present
   * but empty means the plan ran and found nothing to publish. The two must not be collapsed.
   */
  fun parsePublishSet(property: String?): Set<String>? =
    property?.split(",")?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
}
