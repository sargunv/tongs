import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
  jvmToolchain(25)
  jvm()
  android {
    namespace = "dev.sargunv.tongs.engine"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    minSdk = libs.versions.android.minSdk.get().toInt()
  }

  // JNI code, shared by desktop and Android. `withAndroidTarget()` doesn't match the Android
  // library plugin's target, so match its platform instead.
  @OptIn(ExperimentalKotlinGradlePluginApi::class)
  applyDefaultHierarchyTemplate {
    common {
      group("jvmAndAndroid") {
        withJvm()
        withCompilations { it.platformType == KotlinPlatformType.androidJvm }
      }
    }
  }

  sourceSets { commonMain.dependencies { api(libs.kotlinx.coroutines.core) } }
}

val cargoSources =
  fileTree(rootDir.resolve("engine-rs")) { include("**/*.rs", "**/*.scm", "**/Cargo.toml") } +
    files(rootDir.resolve("Cargo.lock"))

/** Builds the Rust JNI library that `NativeEngine` loads. */
tasks.register<Exec>("cargoBuild") {
  group = "build"
  description = "Builds libtongs_jni with cargo."
  workingDir = rootDir
  commandLine("cargo", "build", "--release", "-p", "tongs-jni")
  inputs.files(cargoSources)
  outputs.dir(rootDir.resolve("target/release"))
}

val ndkDir =
  androidComponents.sdkComponents.sdkDirectory
    .get()
    .dir("ndk/${libs.versions.android.ndk.get()}")
    .asFile
    .path

/**
 * Builds each tree-sitter grammar as its own compressed shared library, which the Android engine
 * embeds and extracts on first use instead of linking (see `engine-rs/highlight/src/bundled.rs`).
 */
val grammarsDir = layout.buildDirectory.dir("grammars")
val buildGrammars =
  tasks.register<Exec>("buildGrammars") {
    group = "build"
    description = "Builds the tree-sitter grammars for Android with tongs-grammars."
    workingDir = rootDir
    commandLine(
      "cargo",
      "run",
      "--release",
      "-p",
      "tongs-grammars",
      "--",
      "--ndk=$ndkDir",
      "--api=${libs.versions.android.minSdk.get()}",
      "--out=${grammarsDir.get().asFile.path}",
      "aarch64-linux-android",
      "x86_64-linux-android",
    )
    inputs.files(
      fileTree(rootDir.resolve("engine-rs/grammars")) { include("**/*.rs", "Cargo.toml") },
      rootDir.resolve("engine-rs/highlight/Cargo.toml"),
      rootDir.resolve("Cargo.lock"),
    )
    outputs.dir(grammarsDir)
  }

/** Cross-compiles libtongs_jni for Android into a `jniLibs` layout. */
abstract class CargoNdkBuild : Exec() {
  @get:OutputDirectory abstract val jniLibs: DirectoryProperty
}

val cargoNdkBuild =
  tasks.register<CargoNdkBuild>("cargoNdkBuild") {
    group = "build"
    description = "Builds libtongs_jni for Android with cargo-ndk."
    jniLibs = layout.buildDirectory.dir("cargo-ndk/jniLibs")
    workingDir = rootDir
    // cargo-ndk also exports CC, AR, and friends per target, so C deps built with `cc` work.
    environment("ANDROID_NDK_HOME", ndkDir)
    environment("TONGS_GRAMMARS_DIR", grammarsDir.get().asFile.path)
    dependsOn(buildGrammars)
    inputs.dir(grammarsDir)
    commandLine(
      "cargo",
      "ndk",
      "--target=arm64-v8a",
      "--target=x86_64",
      "--platform=${libs.versions.android.minSdk.get()}",
      "--output-dir=${jniLibs.get().asFile.path}",
      "build",
      "--release",
      "-p",
      "tongs-jni",
    )
    inputs.files(cargoSources)
  }

androidComponents {
  onVariants { variant ->
    variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoNdkBuild, CargoNdkBuild::jniLibs)
  }
}
