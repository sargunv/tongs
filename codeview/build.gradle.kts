plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kotlin.multiplatform.library)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.compose.multiplatform)
}

kotlin {
  jvmToolchain(25)
  jvm()
  android {
    namespace = "dev.sargunv.tongs.codeview"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    minSdk = libs.versions.android.minSdk.get().toInt()
  }

  sourceSets {
    commonMain.dependencies {
      api(project(":engine"))
      implementation(libs.compose.foundation)
      implementation(libs.compose.runtime)
      implementation(libs.compose.ui)
    }
    commonTest.dependencies { implementation(kotlin("test")) }
    jvmTest.dependencies {
      implementation(compose.desktop.currentOs)
      implementation(libs.compose.ui.test)
    }
  }
}
