plugins {
  alias(libs.plugins.tongs.kmp.library)
  alias(libs.plugins.tongs.compose)
  alias(libs.plugins.roborazzi)
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(project(":forge:api"))
      implementation(project(":core:ui"))
      implementation(libs.markdown.renderer)
      implementation(libs.markdown.renderer.m3)
      implementation(libs.markdown.renderer.coil3)
      implementation(libs.coil.compose)
    }
    jvmTest.dependencies {
      implementation(compose.desktop.currentOs)
      implementation(libs.compose.ui.test)
      implementation(libs.roborazzi.compose.desktop)
    }
  }
}

// Screenshot tests compare against the PNGs in src/jvmTest/snapshots: `recordRoborazziJvm` to
// update them, `verifyRoborazziJvm` to check. They highlight code fences with the engine, so they
// need its JNI library.
roborazzi { outputDir = file("src/jvmTest/snapshots") }

tasks.named<Test>("jvmTest") {
  dependsOn(":engine:cargoBuild")
  systemProperty("java.library.path", rootDir.resolve("target/release").path)
}
