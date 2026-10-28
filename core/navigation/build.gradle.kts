plugins {
  alias(libs.plugins.tongs.kmp.library)
  alias(libs.plugins.tongs.compose)
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(project(":forge:api"))
      api(libs.navigation3.ui)
      api(libs.compose.material3.adaptive.navigation3)
      api(project(":core:ui"))
      implementation(libs.kotlinx.serialization.json)
    }
  }
}
