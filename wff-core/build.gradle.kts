import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.ktfmt)
  `maven-publish`
}

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
