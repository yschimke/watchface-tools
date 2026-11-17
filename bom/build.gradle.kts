import ee.schimke.watchface.buildlogic.PublishedArtifacts
import ee.schimke.watchface.buildlogic.PublishedVersions

plugins {
  id("watchface.maven-publishing-platform")
  alias(libs.plugins.ktfmt)
}

ktfmt { googleStyle() }

// The BOM for everything this repository publishes. A consumer names one version:
//
//     implementation(platform("ee.schimke.watchface:watchface-tools-bom:<version>"))
//     implementation("ee.schimke.watchface:wff-core")
//     implementation("ee.schimke.watchface:wff-packager")
//
// The constraints are derived, not listed: `settings.gradle.kts` collects every project whose build
// script applies `watchface.maven-publishing`, and each module's coordinates (a Kotlin
// Multiplatform
// module also contributes its `-jvm` / `-wasm-js` variants) come from `PublishedArtifacts`. Each
// takes its module's EFFECTIVE version through `PublishedVersions.resolve`, the same call that sets
// `project.version`, so the versions the BOM promises and the versions the POMs name cannot differ.
val publishedProjectPaths =
  providers.systemProperty("watchface.publishedProjectPaths").get().split(",").filter {
    it.isNotBlank()
  }

val publishSet =
  PublishedVersions.parsePublishSet(providers.gradleProperty("watchface.publishSet").orNull)

val manifestText = providers.provider {
  rootProject.layout.projectDirectory
    .file("publishing-manifest.json")
    .asFile
    .takeIf { it.isFile }
    ?.readText() ?: "{}"
}

dependencies {
  constraints {
    publishedProjectPaths.sorted().forEach { path ->
      val moduleId = path.removePrefix(":")
      val version =
        PublishedVersions.resolve(
          artifactId = moduleId,
          tagVersion = project.version.toString(),
          publishSet = publishSet,
          manifestText = manifestText.get(),
        )
      val buildScript = rootProject.file("$moduleId/build.gradle.kts").readText()
      PublishedArtifacts.coordinates(moduleId, buildScript).forEach { artifactId ->
        api("ee.schimke.watchface:$artifactId:$version")
      }
    }
  }
}

watchfacePlatformPublishing {
  coordinates(
    artifactId = "watchface-tools-bom",
    displayName = "Watchface Tools - Bill of Materials",
    description =
      "Version constraints for every published watchface-tools library, so a consumer aligns " +
        "wff-core and wff-packager with one coordinate.",
  )
}
