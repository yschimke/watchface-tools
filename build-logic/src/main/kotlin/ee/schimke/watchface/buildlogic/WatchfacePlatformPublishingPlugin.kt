package ee.schimke.watchface.buildlogic

import javax.inject.Inject
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property

abstract class WatchfacePlatformPublishingExtension @Inject constructor(objects: ObjectFactory) {
  val artifactId: Property<String> = objects.property(String::class.java)
  val displayName: Property<String> = objects.property(String::class.java)
  val description: Property<String> = objects.property(String::class.java)

  fun coordinates(artifactId: String, displayName: String, description: String) {
    this.artifactId.set(artifactId)
    this.displayName.set(displayName)
    this.description.set(description)
  }
}

/**
 * Publishes a Gradle `java-platform` (the BOM) to the same group, and at the tag version, of the
 * modules it constrains. Deliberately not [WatchfaceMavenPublishingPlugin]'s concern: it has no
 * sources and takes the tag version, never a recorded one, because the BOM is the index of a
 * release, not a member of it.
 */
class WatchfacePlatformPublishingPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    project.pluginManager.apply("java-platform")
    project.pluginManager.apply("maven-publish")
    project.pluginManager.apply("com.vanniktech.maven.publish")
    val extension =
      project.extensions.create(
        "watchfacePlatformPublishing",
        WatchfacePlatformPublishingExtension::class.java,
      )
    project.group = WATCHFACE_GROUP
    project.version = project.platformPublishedVersion()
    project.afterEvaluate {
      configureWatchfacePublication(
        artifactId = extension.artifactId.get(),
        displayName = extension.displayName.get(),
        artifactDescription = extension.description.get(),
      )
    }
  }
}
