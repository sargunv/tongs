plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "dev.sargunv.tongs.android"
  compileSdk = libs.versions.android.compileSdk.get().toInt()
  // Strips the engine's native libraries; match the NDK that built them.
  ndkVersion = libs.versions.android.ndk.get()

  defaultConfig {
    applicationId = "dev.sargunv.tongs"
    minSdk = libs.versions.android.minSdk.get().toInt()
    targetSdk = libs.versions.android.compileSdk.get().toInt()
    versionCode = 1
    versionName = "0.1.0"
    // The ABIs the engine is built for; others would crash without libtongs_jni.
    ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
      // Spike: sign with the debug key so benchmarks can run a non-debuggable build.
      signingConfig = signingConfigs.getByName("debug")
    }
  }
}

dependencies {
  implementation(project(":app"))
  implementation(project(":feature:review"))
  implementation(libs.androidx.activity.compose)
  implementation(libs.compose.foundation)
}
