package com.fethica.swiftradio

object Config {
    const val useLocalStations = true
    const val stationsURL = "https://fethica.com/assets/swift-radio/stations.json"

    const val debugLog = true

    const val hideNextPreviousButtons = false

    const val website = "https://github.com/analogcode/Swift-Radio-Pro"
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
