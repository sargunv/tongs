plugins {
  alias(libs.plugins.tongs.kmp.library)
  alias(libs.plugins.tongs.compose)
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(libs.compose.material3)
      api(libs.compose.material3.adaptive)
      api(libs.compose.material3.navigationSuite)
      api(libs.kotlinx.datetime)
      implementation(libs.material.kolor)
      implementation(libs.coil.compose)
    }
  }
}
