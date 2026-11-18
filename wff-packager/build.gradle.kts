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

// Java 17 bytecode, whatever JDK runs Gradle, like wff-core: consumers hold a 17 floor
// (compose-preview-server's `:server` is compiled for and checked against 17, because
// compose-ai-tools' `:cli` compiles against the released server). Google's validator itself is
// class
// file 61, so nothing here needs a newer version. Both settings, because the JVM target is what the
// bytecode says and `java.targetCompatibility` is the `org.gradle.jvm.version` attribute Gradle
// publishes, which is what a 17 consumer's resolution refuses on.
kotlin {
  explicitApi()
  compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
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
