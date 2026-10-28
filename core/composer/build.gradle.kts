plugins {
  alias(libs.plugins.tongs.kmp.library)
  alias(libs.plugins.tongs.compose)
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(project(":core:data"))
      implementation(project(":core:ui"))
      implementation(project(":core:markdown"))
    }
  }
}
