package dev.sargunv.tongs.forge.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.Dispatcher

/** An OkHttp-backed client for [GitHub], tuned for a review's parallel fetches. */
fun gitHubHttpClient(): HttpClient =
  HttpClient(OkHttp) {
    // Blob batches run in parallel; OkHttp would otherwise queue past 5 per host.
    engine { config { dispatcher(Dispatcher().apply { maxRequestsPerHost = 16 }) } }
  }
