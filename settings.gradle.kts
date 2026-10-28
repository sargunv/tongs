rootProject.name = "tongs"

pluginManagement {
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

include(":engine", ":codeview", ":desktopApp", ":androidApp")
