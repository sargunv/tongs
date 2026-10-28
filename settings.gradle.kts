rootProject.name = "tongs"

pluginManagement {
  includeBuild("build-logic")
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositories {
    google()
    mavenCentral()
  }
}

include(
  ":engine",
  ":codeview",
  ":core:net",
  ":core:data",
  ":core:navigation",
  ":core:ui",
  ":core:markdown",
  ":core:composer",
  ":forge:api",
  ":forge:github",
  ":feature:auth",
  ":feature:feed",
  ":feature:inbox",
  ":feature:review",
  ":feature:settings",
  ":feature:thread",
  ":app",
  ":desktopApp",
  ":androidApp",
)
