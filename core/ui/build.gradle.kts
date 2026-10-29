plugins {
  alias(libs.plugins.tongs.kmp.library)
  alias(libs.plugins.tongs.compose)
}

kotlin {
  sourceSets {
    jvmTest.dependencies {
      implementation(compose.desktop.currentOs)
      implementation(libs.compose.ui.test)
    }
    jvmMain.dependencies { implementation(libs.nucleus.systemColor) }
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
