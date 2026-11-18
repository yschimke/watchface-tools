import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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

  // Java 17 bytecode, whatever JDK runs Gradle: consumers hold a 17 floor (compose-ui-builder's
  // server-side runtime, compose-preview-server), and a jar compiled for the build JDK (21 in CI)
  // fails there with UnsupportedClassVersionError. commonMain has no dependencies, so nothing here
  // needs a newer class-file version.
  jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

  @OptIn(ExperimentalWasmDsl::class) wasmJs { browser() }

  sourceSets {
    commonMain.dependencies {
      // Deliberately empty: commonMain stays dependency-free so the browser editor can emit XML.
    }
    commonTest.dependencies { implementation(libs.kotlin.test) }
  }
}
