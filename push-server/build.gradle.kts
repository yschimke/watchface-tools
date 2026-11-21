plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.ktfmt)
  application
}

description =
  "Standalone HTTP service for Watch Face Format validation and Watch Face Push APK packaging."

ktfmt { googleStyle() }

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
  implementation(project(":wff-packager"))

  testImplementation(libs.kotlin.test)
  testImplementation(kotlin("test-junit5"))
}

application {
  mainClass.set("ee.schimke.watchface.pushserver.PushServerMainKt")
  applicationName = "watchface-push-server"
}

tasks.named<Tar>("distTar") {
  compression = Compression.GZIP
  archiveExtension.set("tar.gz")
}

tasks.test {
  useJUnitPlatform()
  systemProperty(
    "wff.samples.dir",
    project(":wff-core")
      .layout
      .projectDirectory
      .dir("src/jvmTest/resources/wear-os-samples")
      .asFile
      .absolutePath,
  )
}
