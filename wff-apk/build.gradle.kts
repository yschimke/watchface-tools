import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.ktfmt)
  id("watchface.maven-publishing")
}

description =
  "Packages a Watch Face Format source bundle into a signed, installable APK in pure Kotlin: " +
    "binary XML, resources.arsc, zip alignment and APK Signature Scheme v2/v3 (JVM and Kotlin/Wasm)."

ktfmt { googleStyle() }

kotlin {
  explicitApi()

  // Same Java 17 floor as wff-core, for the same consumers.
  jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

  @OptIn(ExperimentalWasmDsl::class) wasmJs { browser() }

  sourceSets {
    commonMain.dependencies {
      api(project(":wff-core"))
      // Deliberately nothing else: SHA-256, CRC-32, DEFLATE, DER and the APK container are all
      // written here, and the only platform seam is the signature primitive (see `ApkSigner`).
    }
    commonTest.dependencies { implementation(libs.kotlin.test) }
    // Test-only: apksig is the reference verifier for what this module signs. Not on any POM.
    jvmTest.dependencies { implementation(libs.apksig) }
  }
}
