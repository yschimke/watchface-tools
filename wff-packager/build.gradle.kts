plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.ktfmt)
  id("watchface.maven-publishing")
  application
}

description =
  "Builds signed, validated, Watch Face Push-ready APKs from a Watch Face Format source bundle " +
    "with aapt2, apksig and Google's watch face validators."

ktfmt { googleStyle() }

// No jvmToolchain pin: wff-core's JVM jar is compiled for the JDK running Gradle (21 in CI), and
// the
// tests must run on a JVM at least that new. Google's validator itself only needs 17.
kotlin { explicitApi() }

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
