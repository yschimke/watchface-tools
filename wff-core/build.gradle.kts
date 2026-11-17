import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.ktfmt)
  id("watchface.maven-publishing")
}

description =
  "Lossless Watch Face Format document model, canonical XML writer and parser, and an " +
    "XSD-generated, version-aware schema and validator (JVM and Kotlin/Wasm)."

ktfmt { googleStyle() }

kotlin {
  explicitApi()

  jvm()

  @OptIn(ExperimentalWasmDsl::class) wasmJs { browser() }

  sourceSets {
    commonMain.dependencies {
      // Deliberately empty: commonMain stays dependency-free so the browser editor can emit XML.
    }
    commonTest.dependencies { implementation(libs.kotlin.test) }
  }
}
