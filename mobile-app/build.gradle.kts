plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ktfmt)
}

ktfmt { googleStyle() }

// The phone half of Faces: the watch's marketplace (`:push-app`) has a companion that shows a
// published face as a launcher widget. It shares the watch app's applicationId, as a Play listing's
// phone and Wear builds do, so the two can later talk over the Wear Data Layer.
val marketplacePackage: String = providers.gradleProperty("watchface.marketplacePackage").get()
val appVersionName: String = providers.gradleProperty("watchfaceToolsVersion").get()
val appVersionCode: Int =
  appVersionName.substringBefore('-').split('.').map(String::toInt).let { (major, minor, patch) ->
    major * 10_000 + minor * 100 + patch
  }

android {
  namespace = "ee.schimke.watchface.mobile"
  compileSdk = 37

  defaultConfig {
    applicationId = marketplacePackage
    // Remote Compose launcher widgets (RemoteViews.DrawInstructions) start at Android 16; Android
    // 17 (API 37) adds system colours.
    minSdk = 36
    targetSdk = 37
    versionCode = appVersionCode
    versionName = appVersionName

    // Where faces are listed (the Watch Face Push site the watch app installs from) and the design
    // server that compiles one to Remote Compose (POST /api/cards/resolve with a `watchface`
    // card). Override with -Pfaces.server / -Pfaces.renderer.
    buildConfigField(
      "String",
      "FACES_SERVER",
      "\"${providers.gradleProperty("faces.server").getOrElse("https://ui.coo.ee")}\"",
    )
    buildConfigField(
      "String",
      "RENDERER_URL",
      "\"${providers.gradleProperty("faces.renderer").getOrElse("https://ha.coo.ee")}\"",
    )
  }

  buildTypes { release { isMinifyEnabled = false } }

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
    // As :push-app: Robolectric 4.17 sets up SDK 36's shared memory through jdk.internal.access.
    unitTests.all { it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED") }
  }
}

dependencies {
  implementation(platform(libs.compose.bom))
  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(platform(libs.rc.players.bom))
  implementation(libs.rc.widget.launcher.core)
  implementation(libs.okhttp)
  implementation(libs.coil.compose)
  implementation(libs.coil.network.okhttp)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.android)

  testImplementation(libs.kotlin.test.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.okhttp.mockwebserver)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.test.junit)
}
