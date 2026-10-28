plugins { alias(libs.plugins.tongs.kmp.library) }

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(libs.ktor.client.core)
      api(libs.kotlinx.coroutines.core)
    }
    jvmMain.dependencies { api(libs.ktor.client.okhttp) }
    androidMain.dependencies { api(libs.ktor.client.okhttp) }
    commonTest.dependencies { implementation(libs.ktor.client.mock) }
  }
}
