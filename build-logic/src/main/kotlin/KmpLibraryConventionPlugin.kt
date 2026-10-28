import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * A Kotlin Multiplatform library for desktop (JVM) and Android. Its Android namespace comes from
 * its path: `:feature:inbox` is `dev.sargunv.tongs.feature.inbox`.
 */
class KmpLibraryConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) =
    with(target) {
      pluginManager.apply("org.jetbrains.kotlin.multiplatform")
      pluginManager.apply("com.android.kotlin.multiplatform.library")
      val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
      fun version(name: String) = libs.findVersion(name).get().requiredVersion.toInt()

      extensions.configure<KotlinMultiplatformExtension> {
        jvmToolchain(25)
        jvm()
        extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
          namespace = "dev.sargunv.tongs." + path.removePrefix(":").replace(':', '.')
          compileSdk = version("android-compileSdk")
          minSdk = version("android-minSdk")
          withHostTest {}
        }
        sourceSets.getByName("commonTest").dependencies {
          implementation(kotlin("test"))
          implementation(libs.findLibrary("kotlinx-coroutines-test").get())
        }
      }
    }
}
