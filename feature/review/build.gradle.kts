plugins {
  alias(libs.plugins.tongs.kmp.library)
  alias(libs.plugins.tongs.compose)
  alias(libs.plugins.metro)
}

kotlin {
  sourceSets {
    jvmTest.dependencies { implementation(libs.sqlite.bundled) }
    commonMain.dependencies {
      api(project(":codeview"))
      // For the spike entry points, which open a PR without an account.
      api(project(":forge:github"))
      implementation(project(":core:composer"))
      implementation(project(":core:data"))
      implementation(project(":core:markdown"))
      implementation(project(":core:navigation"))
      implementation(project(":core:ui"))
      implementation(libs.lifecycle.viewmodel.compose)
      implementation(libs.lifecycle.runtime.compose)
    }
  }
}
