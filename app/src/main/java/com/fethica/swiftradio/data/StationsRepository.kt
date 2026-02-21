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
import okhttp3.OkHttpClient
import java.io.File
import kotlinx.serialization.encodeToString

class StationsRepository(
    private val context: Context,
    okHttpClient: OkHttpClient
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(OkHttp) {
        engine {
            preconfigured = okHttpClient
        }
        install(ContentNegotiation) {
            json(json)
        }
    }

    suspend fun loadStations(remoteUrl: String? = null): List<RadioStation> = withContext(Dispatchers.IO) {
        val stations = if (remoteUrl != null) {
            try {
                val fetched = loadFromNetwork(remoteUrl)
                saveToCache(fetched)
                fetched
            } catch (e: Exception) {
                val cached = loadFromCache()
                if (cached.isNotEmpty()) cached else throw e
            }
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

    private fun getCacheFile(): File {
        return File(context.filesDir, "cached_stations.json")
    }

    private fun saveToCache(stations: List<RadioStation>) {
        try {
            val response = StationsResponse(stations)
            val jsonString = json.encodeToString(response)
            getCacheFile().writeText(jsonString)
        } catch (e: Exception) {
            android.util.Log.e("StationsRepository", "Failed to cache stations", e)
        }
    }

    private fun loadFromCache(): List<RadioStation> {
        return try {
            val file = getCacheFile()
            if (file.exists()) {
                val jsonString = file.readText()
                json.decodeFromString<StationsResponse>(jsonString).station
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.e("StationsRepository", "Failed to load cached stations", e)
            emptyList()
        }
    }
}
