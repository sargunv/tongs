plugins {
  alias(libs.plugins.tongs.kmp.library)
  alias(libs.plugins.tongs.compose)
  alias(libs.plugins.metro)
  alias(libs.plugins.roborazzi)
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      implementation(project(":core:data"))
      implementation(project(":core:navigation"))
      implementation(project(":core:ui"))
      implementation(project(":core:markdown"))
      implementation(project(":core:composer"))
      implementation(libs.lifecycle.viewmodel.compose)
      implementation(libs.lifecycle.runtime.compose)
    }
    jvmTest.dependencies {
      implementation(compose.desktop.currentOs)
      implementation(libs.compose.ui.test)
      implementation(libs.roborazzi.compose.desktop)
    }
  }
}

// Screenshot tests compare against the PNGs in src/jvmTest/snapshots: `recordRoborazziJvm` to
// update them, `verifyRoborazziJvm` to check.
roborazzi { outputDir = file("src/jvmTest/snapshots") }

// Comments highlight code fences with the engine, so the screenshots need its JNI library.
tasks.named<Test>("jvmTest") {
  dependsOn(":engine:cargoBuild")
  systemProperty("java.library.path", rootDir.resolve("target/release").path)
}
