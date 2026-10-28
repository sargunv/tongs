plugins { alias(libs.plugins.kotlin.multiplatform) }

kotlin {
  jvmToolchain(25)
  jvm()
}

/** Builds the Rust JNI library that `NativeEngine` loads. */
val cargoBuild by
  tasks.registering(Exec::class) {
    group = "build"
    description = "Builds libtongs_jni with cargo."
    workingDir = rootDir
    commandLine("cargo", "build", "--release", "-p", "tongs-jni")
    inputs.files(fileTree(rootDir.resolve("engine-rs")) { include("**/*.rs", "**/Cargo.toml") })
    inputs.files(rootDir.resolve("Cargo.lock"))
    outputs.dir(rootDir.resolve("target/release"))
  }
