import com.github.triplet.gradle.androidpublisher.ReleaseStatus

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.play.publisher)
}

ktfmt { googleStyle() }

val marketplacePackage: String = providers.gradleProperty("watchface.marketplacePackage").get()

// The app ships with the repository's releases: release-please rewrites watchfaceToolsVersion in
// gradle.properties, and the version code follows it (major * 10_000 + minor * 100 + patch), so
// every release uploads a higher code than the last. See docs/PLAY_PUBLISHING.md.
val appVersionName: String = providers.gradleProperty("watchfaceToolsVersion").get()
val appVersionCode: Int =
  appVersionName.substringBefore('-').split('.').map(String::toInt).let { (major, minor, patch) ->
    major * 10_000 + minor * 100 + patch
  }

android {
  namespace = "ee.schimke.watchface.push"
  compileSdk = 37

  defaultConfig {
    applicationId = marketplacePackage
    // Watch Face Push needs Wear OS 6 (API 36); the library itself declares minSdk 33 but
    // WatchFacePushManagerFactory.isSupported() is false below 36.
    minSdk = 36
    targetSdk = 36
    versionCode = appVersionCode
    versionName = appVersionName

    buildConfigField("String", "MARKETPLACE_PACKAGE", "\"$marketplacePackage\"")
    buildConfigField(
      "String",
      "DEFAULT_BASE_URL",
      "\"${providers.gradleProperty("push.baseUrl").getOrElse("https://preview.coo.ee")}\"",
    )
  }

  // The Play upload key, only in CI (play-release.yml). Without it the release build is unsigned,
  // which is all a local or CI bundle check needs.
  val uploadKeystorePath = System.getenv("PUSH_APP_KEYSTORE_PATH")
  signingConfigs {
    if (uploadKeystorePath != null) {
      create("release") {
        storeFile = file(uploadKeystorePath)
        storePassword = System.getenv("PUSH_APP_KEYSTORE_PASSWORD")
        keyAlias = System.getenv("PUSH_APP_KEY_ALIAS")
        keyPassword = System.getenv("PUSH_APP_KEY_PASSWORD")
      }
    }
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
      if (uploadKeystorePath != null) {
        signingConfig = signingConfigs.getByName("release")
      }
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

// Gradle Play Publisher. A Wear OS-only app publishes to the ordinary tracks; `-Pplay.track=…`
// overrides (for example `wear:internal` if the app ever gains a phone form factor).
play {
  track.set(providers.gradleProperty("play.track").orElse("internal"))
  defaultToAppBundles.set(true)
  releaseStatus.set(ReleaseStatus.COMPLETED)
  enabled.set(System.getenv("ANDROID_PUBLISHER_CREDENTIALS") != null)
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
