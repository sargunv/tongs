import dev.nucleusframework.desktop.application.dsl.TargetFormat

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.nucleus)
}

kotlin { jvmToolchain(25) }

dependencies {
  testImplementation(kotlin("test"))
  implementation(project(":engine"))
  implementation(project(":codeview"))
  implementation(project(":feature:review"))
  implementation(project(":app"))
  implementation(compose.desktop.currentOs)
  implementation(libs.compose.material3)
  implementation(libs.kotlinx.coroutines.swing)
  implementation(libs.nucleus.application)
  implementation(libs.nucleus.decoratedWindowTao)
}

nucleus.application {
  mainClass = "dev.sargunv.tongs.desktop.MainKt"
  jvmArgs += "-Djava.library.path=${rootDir.resolve("target/release")}"
  if (System.getProperty("os.name").startsWith("Mac")) jvmArgs += "-XstartOnFirstThread"
  nativeDistributions {
    targetFormats(TargetFormat.Deb, TargetFormat.Dmg, TargetFormat.Msi)
    packageName = "tongs"
  }
}

tasks.withType<JavaExec>().configureEach { dependsOn(":engine:cargoBuild") }
