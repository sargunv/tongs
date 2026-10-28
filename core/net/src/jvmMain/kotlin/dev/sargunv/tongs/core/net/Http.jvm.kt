package dev.sargunv.tongs.core.net

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.Dispatcher

internal actual fun platformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient =
  HttpClient(OkHttp) {
    // Review blob batches run in parallel; OkHttp would otherwise queue past 5 per host.
    engine { config { dispatcher(Dispatcher().apply { maxRequestsPerHost = 16 }) } }
    block()
  }
