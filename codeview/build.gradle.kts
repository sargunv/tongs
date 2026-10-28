plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.compose.multiplatform)
}

kotlin {
  jvmToolchain(25)
  jvm()

  sourceSets {
    commonMain.dependencies {
      api(project(":engine"))
      implementation(libs.compose.foundation)
      implementation(libs.compose.runtime)
      implementation(libs.compose.ui)
    }
    commonTest.dependencies { implementation(kotlin("test")) }
  }
}
