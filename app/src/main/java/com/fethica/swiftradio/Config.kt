package com.fethica.swiftradio

import androidx.compose.ui.graphics.Color

object Config {
    val gradientColor: Color = Color.White

    const val useLocalStations = true
    const val stationsURL = "https://fethica.com/assets/swift-radio/stations.json"

    const val hideNextPreviousButtons = false
    const val enableSearch = true // Toggle to show/hide search bar on the Stations Screen

    const val email = "contact@fethica.com"
    const val feedbackURL = "https://fethica.com/#contact"
    const val licenseURL = "https://raw.githubusercontent.com/fethica/Swift-Radio-Android/refs/heads/main/LICENSE"
    const val shareText = "Check out Swift Radio!"

    data class LibraryItem(val owner: String, val repo: String)

    val libraries = listOf(
        LibraryItem("fethica", "Swift-Radio-Android"),
        LibraryItem("coil-kt", "coil"),
        LibraryItem("ktorio", "ktor"),
    )
}
