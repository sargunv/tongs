import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/** Compose Multiplatform with Material 3, on top of `tongs.kmp.library`. */
class ComposeConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) =
    with(target) {
      pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
      pluginManager.apply("org.jetbrains.compose")
      val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
      fun lib(name: String) = libs.findLibrary(name).get()

      extensions.configure<KotlinMultiplatformExtension> {
        compilerOptions {
          optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi",
          )
        }
        sourceSets.getByName("commonMain").dependencies {
          implementation(lib("compose-runtime"))
          implementation(lib("compose-foundation"))
          implementation(lib("compose-ui"))
          implementation(lib("compose-material3"))
        }
      }
    }
}
