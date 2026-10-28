package dev.sargunv.tongs.forge.github

import dev.sargunv.tongs.core.net.createHttpClient
import io.ktor.client.HttpClient

/**
 * A client for [GitHub], tuned for a review's parallel fetches; the app's shared one will do too.
 */
fun gitHubHttpClient(): HttpClient = createHttpClient()
