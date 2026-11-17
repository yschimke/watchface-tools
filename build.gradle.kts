plugins {
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.ktfmt) apply false
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.kotlin.serialization) apply false
}

allprojects {
  group = "ee.schimke.watchface"
  version = providers.gradleProperty("VERSION_NAME").getOrElse("0.1.0-SNAPSHOT")
}
