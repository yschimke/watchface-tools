plugins {
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.ktfmt) apply false
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  // Loaded into the root scope so every publishing module shares the plugin's ClassLoader.
  alias(libs.plugins.maven.publish) apply false
}

// The publish task path for each module this build publishes, as `<task>\t<module dir>` rows. The
// release job runs this instead of keeping a task list in YAML that goes stale.
//
// `-Pwatchface.publishSet` names the modules this release uploads, computed by
// `.github/scripts/maven-publish-plan.sh`. Absent means no plan ran: everything publishes (a
// `workflow_dispatch` recovery run). Empty means the plan ran and found nothing: nothing publishes,
// not even the BOM. The two must not be collapsed; this mirrors `PublishedVersions.parsePublishSet`.
val printPublishTasks by
  tasks.registering {
    group = "publishing"
    description = "Print the publish task path for each module this build publishes."
    notCompatibleWithConfigurationCache("Inspects the project tree at execution time")
    val rootDirPath = rootDir
    val publishSet =
      providers
        .gradleProperty("watchface.publishSet")
        .orNull
        ?.split(",")
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.toSet()
    val rows =
      subprojects
        .filter {
          it.plugins.hasPlugin("watchface.maven-publishing") ||
            it.plugins.hasPlugin("watchface.maven-publishing-platform")
        }
        .filter { p ->
          publishSet == null ||
            (p.path == ":bom" && publishSet.isNotEmpty()) ||
            (p.path != ":bom" && p.name in publishSet)
        }
        .map { p ->
          val dir = p.projectDir.relativeTo(rootDirPath).invariantSeparatorsPath
          "${p.path}:publishAndReleaseToMavenCentral" to dir
        }
    doLast { rows.sortedBy { (task, _) -> task }.forEach { (task, dir) -> println("$task\t$dir") } }
  }

// `check` at the root reaches build-logic's own tests: task-name matching does not descend into an
// included build, so they would otherwise run for nobody.
apply(plugin = "lifecycle-base")

tasks.named("check") { dependsOn(gradle.includedBuild("build-logic").task(":test")) }
