plugins {
  alias(libs.plugins.tongs.kmp.library)
  alias(libs.plugins.tongs.compose)
  alias(libs.plugins.metro)
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(project(":core:data"))
      api(project(":core:navigation"))
      implementation(project(":core:net"))
      implementation(project(":core:ui"))
      implementation(project(":forge:github"))
      implementation(project(":feature:auth"))
      implementation(project(":feature:feed"))
      implementation(project(":feature:inbox"))
      implementation(project(":feature:review"))
      implementation(project(":feature:settings"))
      implementation(project(":feature:thread"))
      implementation(libs.lifecycle.runtime.compose)
      implementation(libs.lifecycle.viewmodel.compose)
      implementation(libs.lifecycle.viewmodel.navigation3)
    }
  }
}
