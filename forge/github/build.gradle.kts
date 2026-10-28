import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
  jvmToolchain(25)
  jvm()
  android {
    namespace = "dev.sargunv.tongs.forge.github"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    minSdk = libs.versions.android.minSdk.get().toInt()
  }

  // The OkHttp engine, shared by desktop and Android; see the engine module.
  @OptIn(ExperimentalKotlinGradlePluginApi::class)
  applyDefaultHierarchyTemplate {
    common {
      group("jvmAndAndroid") {
        withJvm()
        withCompilations { it.platformType == KotlinPlatformType.androidJvm }
      }
    }
  }

  sourceSets {
    commonMain.dependencies {
      api(project(":engine"))
      api(libs.ktor.client.core)
      implementation(libs.kotlinx.serialization.json)
    }
    named("jvmAndAndroidMain").dependencies { implementation(libs.ktor.client.okhttp) }
  }
}
