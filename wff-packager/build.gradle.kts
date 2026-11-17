plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.ktfmt)
  application
}

ktfmt { googleStyle() }

kotlin {
  explicitApi()
  jvmToolchain(17)
}

dependencies {
  api(project(":wff-core"))
  api(libs.apksig)
  // Google's official validators: Watch Face Push checks + validation token, WFF XSD validation
  // and the memory-footprint evaluator all ship inside this one artifact.
  implementation(libs.validator.push)

  testImplementation(libs.kotlin.test)
  testImplementation(kotlin("test-junit5"))
}

application {
  mainClass.set("ee.schimke.watchface.packager.MainKt")
  applicationName = "wff-packager"
}

tasks.test {
  useJUnitPlatform()
  // The wear-os-samples live with wff-core's JVM tests.
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
