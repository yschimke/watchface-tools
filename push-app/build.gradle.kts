plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ktfmt)
}

ktfmt { googleStyle() }

val marketplacePackage: String = providers.gradleProperty("watchface.marketplacePackage").get()

android {
  namespace = "ee.schimke.watchface.push"
  compileSdk = 37

  defaultConfig {
    applicationId = marketplacePackage
    // Watch Face Push needs Wear OS 6 (API 36); the library itself declares minSdk 33 but
    // WatchFacePushManagerFactory.isSupported() is false below 36.
    minSdk = 36
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0"

    buildConfigField("String", "MARKETPLACE_PACKAGE", "\"$marketplacePackage\"")
    buildConfigField(
      "String",
      "DEFAULT_BASE_URL",
      "\"${providers.gradleProperty("push.baseUrl").getOrElse("https://preview.coo.ee")}\"",
    )
  }

  buildTypes {
    debug {
      // Debug builds talk to the in-app fake server unless -Ppush.fake=false.
      buildConfigField(
        "boolean",
        "DEFAULT_FAKE_BACKEND",
        providers.gradleProperty("push.fake").getOrElse("true"),
      )
    }
    release {
      buildConfigField("boolean", "DEFAULT_FAKE_BACKEND", "false")
      isMinifyEnabled = false
    }
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  testOptions {
    unitTests.isIncludeAndroidResources = true
    unitTests.all {
      it.systemProperty("screenshotDir", providers.gradleProperty("screenshotDir").getOrElse(""))
    }
  }
}

dependencies {
  implementation(platform(libs.compose.bom))
  implementation(libs.compose.ui)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.wear.compose.material3)
  implementation(libs.wear.compose.foundation)
  implementation(libs.wear.compose.navigation)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.wear.watchfacepush)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.okhttp)
  implementation(libs.coil.compose)
  implementation(libs.coil.network.okhttp)
  implementation(libs.zxing.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.android)
  debugImplementation(libs.compose.ui.tooling)

  testImplementation(libs.kotlin.test.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.okhttp.mockwebserver)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.test.junit)
  testImplementation(platform(libs.compose.bom))
  testImplementation(libs.compose.ui.test.junit4)
  debugImplementation(libs.compose.ui.test.manifest)
}
