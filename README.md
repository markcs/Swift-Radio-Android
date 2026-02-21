# Swift Radio Android

A fully featured, open-source radio station app for Android built with Kotlin, Jetpack Compose, and Media3.

## Features

- streaming support for live and on-demand audio
- Android Auto integration with full browse tree and playback controls
- album art and track metadata from streams and the iTunes API
- lock screen and notification controls with artwork
- multiple station support from local or remote JSON
- background playback with proper audio focus handling
- localization-ready with all strings extracted to resources

## Built With

- [Media3](https://developer.android.com/media/media3) (ExoPlayer, MediaSession, MediaLibraryService)
- [Jetpack Compose](https://developer.android.com/compose) with Material 3
- [Coil](https://coil-kt.github.io/coil/) for image loading
- [Ktor](https://ktor.io/) for networking

## Getting Started

1. Open the project in [Android Studio](https://developer.android.com/studio)
2. Edit `Config.kt` to set your app name, URLs, and contact info
3. Replace the stations in `app/src/main/assets/stations.json` with your own
4. Run the app

## Station Format

Stations are defined in `stations.json`. Each station has the following fields:

```json
{
  "station": [
    {
      "name": "Station Name",
      "streamURL": "https://example.com/stream",
      "imageURL": "station-image",
      "desc": "Short description",
      "longDesc": "Detailed description shown in the station info sheet.",
      "website": "https://example.com"
    }
  ]
}
```

| Field | Required | Description |
|-------|----------|-------------|
| `name` | Yes | Station name displayed in the list and player |
| `streamURL` | Yes | Direct URL to the audio stream (MP3, AAC, etc.) |
| `imageURL` | Yes | Image filename in `assets/` (without extension) or a full URL |
| `desc` | Yes | Short subtitle shown below the station name |
| `longDesc` | No | Longer description shown in the station info sheet |
| `website` | No | Station website URL shown in the info sheet |

To load stations from a remote URL instead of the local file, set `useLocalStations = false` in `Config.kt` and update `stationsURL`.

## Customizing Text and Translation

All user-facing strings are in `app/src/main/res/values/strings.xml`. To add a new language:

1. Create a new directory: `app/src/main/res/values-XX/` (e.g., `values-fr` for French)
2. Copy `strings.xml` into the new directory
3. Translate the string values

Android will automatically use the correct language based on the user's device settings.

## Dependencies

| Library | Purpose |
|---------|---------|
| [AndroidX Media3](https://developer.android.com/media/media3) | Audio playback, media session, Android Auto |
| [Jetpack Compose](https://developer.android.com/compose) | UI framework |
| [Coil](https://github.com/coil-kt/coil) | Image loading and caching |
| [Ktor](https://github.com/ktorio/ktor) | HTTP client for remote station loading |
| [Kotlinx Serialization](https://github.com/Kotlin/kotlinx.serialization) | JSON parsing |

## Single Station Version

A single-station version is available on [Payhip](https://payhip.com/nicefiction).

## Credits

- Created by [Fethi El Hassasna](https://github.com/fethica) and [Matt Fecher](https://github.com/swiftcodex)
- Based on [Swift Radio Pro](https://github.com/analogcode/Swift-Radio-Pro) for iOS

## License

[MIT License](LICENSE)
