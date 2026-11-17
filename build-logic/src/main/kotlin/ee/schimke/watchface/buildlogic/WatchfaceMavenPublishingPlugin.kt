package ee.schimke.watchface.buildlogic

import com.vanniktech.maven.publish.MavenPublishBaseExtension
import java.io.File
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Publishes a module to Maven Central as `ee.schimke.watchface:<project name>`.
 *
 * Apply it after the module's Kotlin plugin; set `description` in the module (it becomes the POM
 * description). The artifact id is the project name: `PublishedArtifactsTest` pins that, because
 * `:bom` and the release publish set both address modules by it.
 */
class WatchfaceMavenPublishingPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    project.pluginManager.apply("maven-publish")
    project.pluginManager.apply("com.vanniktech.maven.publish")
    project.group = WATCHFACE_GROUP
    project.version = project.publishedVersion()
    project.afterEvaluate {
      configureWatchfacePublication(
        artifactId = project.name,
        displayName = project.name,
        artifactDescription = project.description ?: error("${project.path}: set `description`"),
      )
    }
  }
}

internal const val WATCHFACE_GROUP = "ee.schimke.watchface"

/**
 * Coordinates, signing and POM metadata shared by the modules and the BOM, so the index and the
 * things it indexes cannot be published under different metadata.
 */
internal fun Project.configureWatchfacePublication(
  artifactId: String,
  displayName: String,
  artifactDescription: String,
) {
  extensions.configure<MavenPublishBaseExtension> {
    publishToMavenCentral(automaticRelease = true)
    if (!version.toString().endsWith("SNAPSHOT")) signAllPublications()
    coordinates(WATCHFACE_GROUP, artifactId, version.toString())
    pom {
      name.set(displayName)
      description.set(artifactDescription)
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

/** `watchfaceToolsVersion` from `gradle.properties`, with `-SNAPSHOT` unless `-Prelease`. */
internal fun Project.platformPublishedVersion(): String {
  val base =
    providers.gradleProperty("watchfaceToolsVersion").orNull
      ?: error("watchfaceToolsVersion is not set in gradle.properties")
  return if (providers.gradleProperty("release").isPresent) base else "$base-SNAPSHOT"
}

/**
 * The version this module publishes at: the tag's, unless a release publish plan skips it, in
 * which case the version it already has on Central ([PublishedVersions]). Outside a release
 * (`-Prelease` absent) there is no plan to honour and everything is the local snapshot.
 */
private fun Project.publishedVersion(): String {
  val version = platformPublishedVersion()
  if (!providers.gradleProperty("release").isPresent) return version
  return PublishedVersions.resolve(
    artifactId = name,
    tagVersion = version,
    publishSet =
      PublishedVersions.parsePublishSet(providers.gradleProperty("watchface.publishSet").orNull),
    manifestText = publishingManifestText(),
  )
}

/**
 * `publishing-manifest.json`, or an empty document when there is none. Not committed: the release
 * plan resolves each coordinate's published version from Central and writes it here.
 */
internal fun Project.publishingManifestText(): String =
  generateSequence(rootDir) { it.parentFile }
    .map { it.resolve("publishing-manifest.json") }
    .firstOrNull(File::isFile)
    ?.readText() ?: "{}"
