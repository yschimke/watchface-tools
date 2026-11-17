plugins {
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.ktfmt) apply false
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.maven.publish) apply false
}

val baseVersion = providers.gradleProperty("watchfaceToolsVersion").get()
val isRelease = providers.gradleProperty("release").isPresent

allprojects {
  group = "ee.schimke.watchface"
  version = if (isRelease) baseVersion else "$baseVersion-SNAPSHOT"
}

// Coordinates, signing and POM metadata shared by every module that publishes to Maven Central.
subprojects {
  // Configured after the module's own build script, so `description` is set and the plugin has
  // finished applying (it finalises its coordinates while it is being applied).
  plugins.withId("com.vanniktech.maven.publish") { afterEvaluate { configurePublishing() } }
}

fun Project.configurePublishing() {
  run {
    extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
      publishToMavenCentral(automaticRelease = true)
      if (isRelease) signAllPublications()
      coordinates("ee.schimke.watchface", project.name, project.version.toString())
      pom {
        name.set(project.name)
        description.set(project.description)
        url.set("https://github.com/yschimke/watchface-tools")
        inceptionYear.set("2026")
        licenses {
          license {
            name.set("The Apache License, Version 2.0")
            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            distribution.set("repo")
          }
        }
        developers {
          developer {
            id.set("yschimke")
            name.set("Yuri Schimke")
            url.set("https://github.com/yschimke")
          }
        }
        scm {
          url.set("https://github.com/yschimke/watchface-tools")
          connection.set("scm:git:https://github.com/yschimke/watchface-tools.git")
          developerConnection.set("scm:git:ssh://git@github.com/yschimke/watchface-tools.git")
        }
      }
    }
  }
}
