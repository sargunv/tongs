package dev.sargunv.tongs.core.net

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent

/**
 * The app's HTTP client. One per process: forge clients share it and add their own auth per
 * request, so connections to a host are reused across accounts.
 */
fun createHttpClient(block: HttpClientConfig<*>.() -> Unit = {}): HttpClient = platformHttpClient {
  install(UserAgent) { agent = "Tongs" }
  install(HttpTimeout) {
    connectTimeoutMillis = 15_000
    // Git packs stream for as long as they take; only a stalled socket fails.
    socketTimeoutMillis = 60_000
  }
  block()
}

/** OkHttp on desktop and Android. */
internal expect fun platformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient
