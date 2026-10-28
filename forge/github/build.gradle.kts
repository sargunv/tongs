plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  jvmToolchain(25)
  jvm()

  sourceSets {
    commonMain.dependencies {
      api(project(":engine"))
      api(libs.ktor.client.core)
      implementation(libs.kotlinx.serialization.json)
    }
  }
}
