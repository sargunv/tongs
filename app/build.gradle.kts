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
      implementation(libs.coil.compose)
      implementation(libs.coil.network.ktor3)
      implementation(libs.lifecycle.runtime.compose)
      implementation(libs.lifecycle.viewmodel.compose)
      implementation(libs.lifecycle.viewmodel.navigation3)
    }
    // Posting end to end against a fake GitHub: the real adapter, Room, and the repositories.
    jvmTest.dependencies {
      implementation(libs.ktor.client.mock)
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.sqlite.bundled)
    }
  }
}
