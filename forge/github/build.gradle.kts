plugins {
  alias(libs.plugins.tongs.kmp.library)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.apollo)
  alias(libs.plugins.metro)
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(project(":engine"))
      api(project(":forge:api"))
      api(project(":core:net"))
      api(libs.ktor.client.core)
      implementation(libs.apollo.runtime)
      implementation(libs.kotlinx.serialization.json)
    }
    commonTest.dependencies { implementation(libs.ktor.client.mock) }
  }
}

apollo {
  service("github") {
    packageName = "dev.sargunv.tongs.forge.github.graphql"
    // GitHub's public schema, from https://docs.github.com/public/fpt/schema.docs.graphql.
    schemaFiles.from(file("src/commonMain/graphql/schema.graphqls"))
    srcDir("src/commonMain/graphql")
    mapScalar("DateTime", "kotlin.String")
    mapScalar("URI", "kotlin.String")
    mapScalar("HTML", "kotlin.String")
    mapScalar("GitObjectID", "kotlin.String")
  }
}
