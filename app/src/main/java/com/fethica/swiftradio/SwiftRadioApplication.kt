package com.fethica.swiftradio

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.fethica.swiftradio.data.ArtworkService
import com.fethica.swiftradio.data.StationsRepository
import okhttp3.OkHttpClient

class SwiftRadioApplication : Application(), SingletonImageLoader.Factory {

    val sharedOkHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder().build()
    }

    val stationsRepository: StationsRepository by lazy {
        StationsRepository(this, sharedOkHttpClient)
    }

    val artworkService: ArtworkService by lazy {
        ArtworkService(sharedOkHttpClient)
    }

    override fun onCreate() {
        super.onCreate()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { sharedOkHttpClient }))
            }
            .build()
    }
}
