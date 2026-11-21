pluginManagement {
  includeBuild("build-logic")
  repositories {
    google()
    gradlePluginPortal()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositories {
    google()
    mavenCentral()
    // validator-push depends on com.github.xgouchet:AXML, which is only published on JitPack.
    maven("https://jitpack.io") { content { includeGroup("com.github.xgouchet") } }
  }
}

rootProject.name = "watchface-tools"

include(":wff-core")
include(":wff-apk")
include(":push-app")
include(":mobile-app")
include(":wff-packager")
include(":push-server")
include(":bom")

// Catalog renderer builds consume the renderer SDK from source, matching wear-m3-catalog /
// m3-catalog. Opt in explicitly so ordinary library/app builds keep resolving from Maven:
//
//   ./gradlew :catalog-ui-builder-renderer:verifyRendererRuntime \
//     -PcomposeUiBuilderDir=../compose-ui-builder
providers.gradleProperty("composeUiBuilderDir").orNull?.let { path ->
  val directory = file(path).canonicalFile
  require(directory.resolve("settings.gradle.kts").isFile) {
    "-PcomposeUiBuilderDir names $directory, which is not a compose-ui-builder Gradle checkout."
  }
  logger.lifecycle("Composite build: uiBuilder -> $directory")
  includeBuild(directory) {
    dependencySubstitution {
      substitute(module("ee.schimke.composeai:ui-builder-renderer-sdk-source"))
        .using(project(":ui-builder-renderer-sdk"))
    }
  }
  include(":catalog-ui-builder-renderer")
}

// Project paths whose build script applies `watchface.maven-publishing`, handed to `:bom` through a
// system property so its constraints are derived from the build rather than a second list that goes
// stale. Matched with the closing quote: `watchface.maven-publishing-platform` shares the prefix, and
// a bare substring match would make the BOM constrain itself.
val publishedProjectPaths = buildList {
  fun visit(descriptor: org.gradle.api.initialization.ProjectDescriptor) {
    if (
      descriptor.buildFile.exists() &&
        descriptor.buildFile.readText().contains("watchface.maven-publishing\")")
    ) {
      add(descriptor.path)
    }
    descriptor.children.forEach(::visit)
  }
  rootProject.children.forEach(::visit)
}

System.setProperty("watchface.publishedProjectPaths", publishedProjectPaths.joinToString(","))
