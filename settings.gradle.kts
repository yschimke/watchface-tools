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
include(":wff-packager")
include(":bom")

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
