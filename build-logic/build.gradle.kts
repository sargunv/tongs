plugins { `kotlin-dsl` }

kotlin { jvmToolchain(25) }

dependencies {
  // The root build puts these plugins on the classpath, at the versions in the catalog.
  compileOnly(libs.gradlePlugin.android)
  compileOnly(libs.gradlePlugin.compose)
  compileOnly(libs.gradlePlugin.composeCompiler)
  compileOnly(libs.gradlePlugin.kotlin)
}

gradlePlugin {
  plugins {
    register("kmpLibrary") {
      id = "tongs.kmp.library"
      implementationClass = "KmpLibraryConventionPlugin"
    }
    register("compose") {
      id = "tongs.compose"
      implementationClass = "ComposeConventionPlugin"
    }
  }
}
