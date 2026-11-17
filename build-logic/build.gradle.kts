plugins { `kotlin-dsl` }

dependencies {
  // The Kotlin Gradle plugin and the publish plugin share one classpath here so the publish plugin
  // can see the Kotlin extensions of the modules it is applied to (jvm and multiplatform).
  implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
  implementation(
    "com.vanniktech:gradle-maven-publish-plugin:${libs.versions.maven.publish.get()}"
  )
}

gradlePlugin {
  plugins {
    register("watchfaceMavenPublishing") {
      id = "watchface.maven-publishing"
      implementationClass = "ee.schimke.watchface.buildlogic.WatchfaceMavenPublishingPlugin"
    }
    register("watchfacePlatformPublishing") {
      id = "watchface.maven-publishing-platform"
      implementationClass = "ee.schimke.watchface.buildlogic.WatchfacePlatformPublishingPlugin"
    }
  }
}

dependencies { testImplementation(kotlin("test-junit5")) }

tasks.test { useJUnitPlatform() }
