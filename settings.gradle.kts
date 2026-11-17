pluginManagement {
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
include(":push-app")
include(":wff-packager")
