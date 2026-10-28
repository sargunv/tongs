import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.compose.multiplatform)
}

kotlin { jvmToolchain(25) }

dependencies {
  implementation(project(":engine"))
  implementation(project(":codeview"))
  implementation(project(":feature:review"))
  implementation(compose.desktop.currentOs)
  implementation(libs.kotlinx.coroutines.swing)
}

compose.desktop {
  application {
    mainClass = "dev.sargunv.tongs.desktop.MainKt"
    jvmArgs += "-Djava.library.path=${rootDir.resolve("target/release")}"
    nativeDistributions {
      targetFormats(TargetFormat.Deb, TargetFormat.Dmg, TargetFormat.Msi)
      packageName = "tongs"
    }
  }
}

tasks.withType<JavaExec>().configureEach { dependsOn(":engine:cargoBuild") }
