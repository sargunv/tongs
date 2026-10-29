plugins {
  alias(libs.plugins.tongs.kmp.library)
  alias(libs.plugins.tongs.compose)
}

kotlin {
  sourceSets {
    jvmTest.dependencies {
      implementation(libs.sqlite.bundled)
      implementation(compose.desktop.currentOs)
      implementation(libs.compose.ui.test)
      implementation(libs.kotlinx.coroutines.swing)
    }
    commonMain.dependencies {
      api(project(":core:data"))
      implementation(project(":core:ui"))
      implementation(project(":core:markdown"))
    }
  }
}
