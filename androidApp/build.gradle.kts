plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

// Counts up with each commit, so a snapshot from main installs over an older one or a local build.
val commitCount =
  providers
    .exec {
      commandLine("git", "rev-list", "--count", "HEAD")
      isIgnoreExitValue = true
    }
    .standardOutput
    .asText
    .map { it.trim().toIntOrNull() ?: 1 }

// CI signs snapshots with a fixed key, since each runner's debug key differs.
val keystore = providers.environmentVariable("TONGS_KEYSTORE").orNull

android {
  namespace = "dev.sargunv.tongs.android"
  compileSdk = libs.versions.android.compileSdk.get().toInt()
  // Strips the engine's native libraries; match the NDK that built them.
  ndkVersion = libs.versions.android.ndk.get()

  defaultConfig {
    applicationId = "dev.sargunv.tongs"
    minSdk = libs.versions.android.minSdk.get().toInt()
    targetSdk = libs.versions.android.compileSdk.get().toInt()
    versionCode = commitCount.get()
    versionName = "0.1.0-dev.${commitCount.get()}"
    // The ABIs the engine is built for; others would crash without libtongs_jni.
    ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
  }

  if (keystore != null) {
    signingConfigs.create("snapshot") {
      storeFile = file(keystore)
      storePassword = providers.environmentVariable("TONGS_KEYSTORE_PASSWORD").get()
      keyAlias = providers.environmentVariable("TONGS_KEY_ALIAS").get()
      keyPassword = providers.environmentVariable("TONGS_KEY_PASSWORD").get()
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
      // Without a snapshot key, the debug key, so benchmarks can run a non-debuggable build.
      signingConfig = signingConfigs.findByName("snapshot") ?: signingConfigs.getByName("debug")
    }
  }
}

dependencies {
  implementation(project(":app"))
  implementation(project(":feature:review"))
  implementation(libs.androidx.activity.compose)
  implementation(libs.compose.foundation)
}
