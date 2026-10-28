package dev.sargunv.tongs.app

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import coil3.serviceLoaderEnabled
import io.ktor.client.HttpClient
import okio.Path.Companion.toPath

/**
 * Loads avatars and Markdown images for the whole app, over the shared HTTP client. Images go in a
 * disk cache under the app's cache dir, so threads read offline show their pictures too. Requests
 * carry no forge token, so images on private repos that need one don't load yet.
 */
internal fun tongsImageLoader(context: PlatformContext, http: HttpClient, cacheDir: String) =
  ImageLoader.Builder(context)
    .serviceLoaderEnabled(false)
    .components { add(KtorNetworkFetcherFactory(http)) }
    .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
    .diskCache {
      DiskCache.Builder().directory("$cacheDir/images".toPath()).maxSizeBytes(256L shl 20).build()
    }
    .crossfade(true)
    .build()
