plugins {
  alias(libs.plugins.tongs.kmp.library)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
  alias(libs.plugins.room3)
  alias(libs.plugins.metro)
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(project(":forge:api"))
      api(libs.kotlinx.coroutines.core)
      api(libs.kotlinx.datetime)
      api(libs.room3.runtime)
      implementation(libs.sqlite.bundled)
      api(libs.datastore.preferences.core)
      implementation(libs.okio)
      implementation(libs.kotlinx.serialization.json)
    }
    jvmMain.dependencies { implementation(libs.java.keyring) }
    commonTest.dependencies { implementation(libs.turbine) }
  }
}

dependencies {
  add("kspJvm", libs.room3.compiler)
  add("kspAndroid", libs.room3.compiler)
}

room3 { schemaDirectory("$projectDir/schemas") }
