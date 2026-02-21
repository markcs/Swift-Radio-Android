package com.fethica.swiftradio.data

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StationsRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    suspend fun loadStations(remoteUrl: String? = null): List<RadioStation> = withContext(Dispatchers.IO) {
        val stations = if (remoteUrl != null) {
            loadFromNetwork(remoteUrl)
        } else {
            loadFromAssets()
        }
        
        val assetFiles = context.assets.list("")?.toSet() ?: emptySet()
        val extensions = listOf("png", "jpg", "jpeg")
        
        stations.forEach { station ->
            station.resolvedImageUrl = if (station.imageURL.startsWith("http")) {
                station.imageURL
            } else if (station.imageURL.isNotBlank()) {
                val match = extensions.firstOrNull { "${station.imageURL}.$it" in assetFiles }
                if (match != null) "file:///android_asset/${station.imageURL}.$match"
                else "file:///android_asset/stationImage.png"
            } else {
                "file:///android_asset/stationImage.png"
            }
        }
        
        stations
    }

    private fun loadFromAssets(): List<RadioStation> {
        val jsonString = context.assets.open("stations.json")
            .bufferedReader()
            .use { it.readText() }
        return json.decodeFromString<StationsResponse>(jsonString).station
    }

    private suspend fun loadFromNetwork(url: String): List<RadioStation> {
        return client.get(url).body<StationsResponse>().station
    }
}
