package com.fethica.swiftradio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.fethica.swiftradio.data.RadioStation
import com.fethica.swiftradio.data.StationsRepository
import com.fethica.swiftradio.data.SquiggleService
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import okhttp3.Request
import okhttp3.OkHttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class AudioService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private var player: ExoPlayer? = null
    
    private var retryJob: Job? = null
    private var bufferingRecoveryJob: Job? = null
    private var stallRecoveryJob: Job? = null
    
    private var currentLiveScore: String? = null
    private var currentIcyTitle: String? = null
    
    private var retryCount = 0
    private var lastObservedPositionMs: Long = 0L
    private var lastPositionRealtimeMs: Long = 0L
    private val audioManager: AudioManager by lazy {
        getSystemService(AudioManager::class.java)
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main)
    private var stations: List<RadioStation> = emptyList()
    private var browseMediaItems: List<MediaItem> = emptyList()
    private val stationsLoaded = CompletableDeferred<Unit>()

    // Cache local asset artwork as byte arrays so Android Auto can display them
    private val artworkCache = mutableMapOf<String, ByteArray?>()

    companion object {
        private const val ROOT_ID = "/"
        private const val TAG = "AudioService"
    }

    private val playerListener = object : Player.Listener {
        override fun onMetadata(metadata: Metadata) {
            val exoPlayer = player ?: return
            serviceScope.launch(Dispatchers.Default) {
                val icyTitle = extractIcyTitle(metadata) ?: return@launch
                currentIcyTitle = icyTitle
                updateDisplayMetadata()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val exoPlayer = player ?: return
            // Ignore transitions caused purely by metadata updates (replaceMediaItem)
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return

            Log.d(TAG, "Svc media item transition reason=$reason uri='${mediaItem?.localConfiguration?.uri}'")
            cancelRetry()
            retryCount = 0
            currentIcyTitle = null
            if (!isEmptyTrackMetadata(exoPlayer.playlistMetadata)) {
                exoPlayer.setPlaylistMetadata(MediaMetadata.Builder().build())
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Svc player error: ${error.errorCodeName} ${error.message}", error)
            retryCurrentItem("player_error")
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val stateName = when (playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN"
            }
            val exoPlayer = player
            Log.d(TAG, "Svc playback state=$stateName playWhenReady=${exoPlayer?.playWhenReady} isPlaying=${exoPlayer?.isPlaying}")
            
            when (playbackState) {
                Player.STATE_READY -> {
                    retryCount = 0
                    cancelBufferingRecovery()
                    startStallRecovery()
                }
                Player.STATE_BUFFERING -> scheduleBufferingRecovery()
                Player.STATE_ENDED -> {
                    cancelStallRecovery()
                    retryCurrentItem("state_ended")
                }
                Player.STATE_IDLE -> {
                    cancelBufferingRecovery()
                    cancelStallRecovery()
                    // Remove aggressive retry on IDLE to prevent loops
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val exoPlayer = player
            // Relaxed check: Only retry if it's been stalled in READY for a long time (handled by stall recovery)
            // or if it's a clear error state.
            if (!isPlaying) {
                startStallRecovery()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) startStallRecovery() else cancelStallRecovery()
        }
    }

    private val libraryCallback = object : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            // Allow all controllers and include library commands
            val connectionResult = super.onConnect(session, controller)
            val availableSessionCommands = connectionResult.availableSessionCommands.buildUpon()
            // Add library commands explicitly
            availableSessionCommands.add(androidx.media3.session.SessionCommand.COMMAND_CODE_LIBRARY_GET_CHILDREN)
            availableSessionCommands.add(androidx.media3.session.SessionCommand.COMMAND_CODE_LIBRARY_GET_ITEM)
            availableSessionCommands.add(androidx.media3.session.SessionCommand.COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT)
            availableSessionCommands.add(androidx.media3.session.SessionCommand.COMMAND_CODE_LIBRARY_SUBSCRIBE)
            availableSessionCommands.add(androidx.media3.session.SessionCommand.COMMAND_CODE_LIBRARY_UNSUBSCRIBE)
            
            return MediaSession.ConnectionResult.accept(
                availableSessionCommands.build(),
                connectionResult.availablePlayerCommands
            )
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .setTitle("Swift Radio")
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (parentId != ROOT_ID) {
                return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            }

            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch {
                try {
                    stationsLoaded.await()
                    future.set(LibraryResult.ofItemList(ImmutableList.copyOf(browseMediaItems), params))
                } catch (e: Exception) {
                    future.setException(e)
                }
            }
            return future
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val future = SettableFuture.create<LibraryResult<MediaItem>>()
            serviceScope.launch {
                try {
                    stationsLoaded.await()
                    val item = browseMediaItems.firstOrNull { it.mediaId == mediaId }
                    if (item != null) {
                        future.set(LibraryResult.ofItem(item, null))
                    } else {
                        future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
                    }
                } catch (e: Exception) {
                    future.setException(e)
                }
            }
            return future
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            val allHaveUri = mediaItems.all { it.localConfiguration?.uri != null }
            if (allHaveUri) {
                return Futures.immediateFuture(mediaItems)
            }

            val future = SettableFuture.create<List<MediaItem>>()
            serviceScope.launch {
                try {
                    stationsLoaded.await()
                    future.set(resolveAutoMediaItems(mediaItems))
                } catch (e: Exception) {
                    future.setException(e)
                }
            }
            return future
        }
    }

    override fun onCreate() {
        super.onCreate()
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("Icy-MetaData" to "1"))
            .setAllowCrossProtocolRedirects(true)

        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
        
        // Increase buffering for smoother HLS playback
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                20_000, // minBufferMs
                50_000, // maxBufferMs
                2_500,  // bufferForPlaybackMs
                5_000   // bufferForPlaybackAfterRebufferMs
            )
            .setBackBuffer(10_000, true)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            .setLiveTargetOffsetMs(10_000L) // Stay behind live edge for better stability
            .setLiveMaxOffsetMs(20_000L)
            .setLiveMinOffsetMs(2_000L)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exoPlayer.setAudioAttributes(audioAttributes, true)
        exoPlayer.volume = 1f
        exoPlayer.repeatMode = Player.REPEAT_MODE_ALL
        exoPlayer.addListener(playerListener)
        player = exoPlayer
        
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = intent?.let {
            android.app.PendingIntent.getActivity(
                this, 0, it, android.app.PendingIntent.FLAG_IMMUTABLE
            )
        }

        mediaSession = MediaLibrarySession.Builder(this, exoPlayer, libraryCallback).apply {
            pendingIntent?.let { setSessionActivity(it) }
        }.build()

        loadStationsForBrowse()
        
        serviceScope.launch {
            val app = application as SwiftRadioApplication
            app.squiggleService.liveScore.collect { score ->
                if (currentLiveScore != score) {
                    currentLiveScore = score
                    updateDisplayMetadata()
                }
            }
        }
    }

    private fun updateDisplayMetadata() {
        val exoPlayer = player ?: return
        serviceScope.launch(Dispatchers.Main) {
            val icyTitle = currentIcyTitle
            val liveScore = currentLiveScore
            
            val parsedMetadata = if (!icyTitle.isNullOrBlank()) {
                buildTrackMetadataFromIcy(icyTitle)
            } else {
                MediaMetadata.Builder().build()
            }
            
            // Update global playlist metadata for the phone UI
            exoPlayer.setPlaylistMetadata(parsedMetadata)
            
            // Update the currently playing MediaItem to force a UI refresh on Android Auto
            val currentItem = exoPlayer.currentMediaItem ?: return@launch
            val baseStationMeta = currentItem.mediaMetadata
            val newMetadataBuilder = baseStationMeta.buildUpon()
            
            val displayTitle = liveScore 
                ?: parsedMetadata.title?.toString() 
                ?: baseStationMeta.title
                
            newMetadataBuilder.setTitle(displayTitle)
            
            if (liveScore == null && parsedMetadata.artist != null) {
                newMetadataBuilder.setArtist(parsedMetadata.artist)
            } else if (liveScore != null) {
                newMetadataBuilder.setArtist(null) // Hide artist when showing scores
            }

            // IMPORTANT: We must retain the original artwork URI and Data
            if (baseStationMeta.artworkUri != null) {
                newMetadataBuilder.setArtworkUri(baseStationMeta.artworkUri)
            }
            if (baseStationMeta.artworkData != null) {
                newMetadataBuilder.setArtworkData(baseStationMeta.artworkData, baseStationMeta.artworkDataType)
            }
            
            val newItem = currentItem.buildUpon()
                .setMediaMetadata(newMetadataBuilder.build())
                .build()
                
            exoPlayer.replaceMediaItem(exoPlayer.currentMediaItemIndex, newItem)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaSession
    }

    override fun onDestroy() {
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        player?.removeListener(playerListener)
        cancelRetry()
        cancelBufferingRecovery()
        cancelStallRecovery()
        player = null
        mediaSession = null
        super.onDestroy()
    }

    private fun loadStationsForBrowse() {
        val app = application as SwiftRadioApplication
        val repository = app.stationsRepository

        serviceScope.launch {
            try {
                val remoteUrl = if (Config.useLocalStations) null else Config.stationsURL
                stations = repository.loadStations(remoteUrl)
                
                val items = mutableListOf<MediaItem>()
                for ((index, station) in stations.withIndex()) {
                    val metadataBuilder = MediaMetadata.Builder()
                        .setTitle(station.name)
                        .setArtist(station.desc)
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        
                    applyArtworkToMetadata(station.resolvedImageUrl, metadataBuilder)
                        
                    items.add(MediaItem.Builder()
                        .setMediaId("station_$index")
                        .setMediaMetadata(metadataBuilder.build())
                        .build())
                }
                browseMediaItems = items
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load stations for browse", e)
            }
            stationsLoaded.complete(Unit)
            mediaSession?.let { session ->
                session.connectedControllers.forEach { controller ->
                    session.notifyChildrenChanged(controller, ROOT_ID, browseMediaItems.size, null)
                }
            }
        }
    }

    private suspend fun resolveAutoMediaItems(mediaItems: List<MediaItem>): List<MediaItem> {
        val requestedId = mediaItems.firstOrNull()?.mediaId
        val selectedIndex = browseMediaItems.indexOfFirst { it.mediaId == requestedId }
            .takeIf { it >= 0 } ?: 0

        val playableItems = mutableListOf<MediaItem>()
        for ((index, station) in stations.withIndex()) {
            val metadataBuilder = MediaMetadata.Builder()
                .setTitle(station.name)
                .setArtist(station.desc)
                .setIsPlayable(true)
                
            applyArtworkToMetadata(station.resolvedImageUrl, metadataBuilder)

            playableItems.add(MediaItem.Builder()
                .setMediaId("station_$index")
                .setUri(station.streamURL)
                .setMediaMetadata(metadataBuilder.build())
                .build())
        }

        return playableItems.subList(selectedIndex, playableItems.size) +
            playableItems.subList(0, selectedIndex)
    }

    /**
     * Android Auto and some controllers cannot read "file:///android_asset/" or might fail with remote URIs.
     * To fix this, we load the artwork as a Bitmap byte array and embed it directly in the metadata.
     */
    private suspend fun applyArtworkToMetadata(imageUrl: String, builder: MediaMetadata.Builder) {
        if (imageUrl.isBlank()) return

        val bytes = artworkCache[imageUrl] ?: withContext(Dispatchers.IO) {
            if (imageUrl.startsWith("http")) {
                try {
                    val app = application as SwiftRadioApplication
                    val request = okhttp3.Request.Builder().url(imageUrl).build()
                    app.sharedOkHttpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            response.body.bytes()
                        } else null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load remote artwork: $imageUrl", e)
                    null
                }
            } else if (imageUrl.startsWith("file:///android_asset/")) {
                val assetName = imageUrl.replace("file:///android_asset/", "")
                try {
                    val inputStream = assets.open(assetName)
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    val outputStream = ByteArrayOutputStream()
                    // Compress to reduce IPC transaction size (critical for Android Auto)
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                    outputStream.toByteArray()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load asset artwork: $assetName", e)
                    null
                }
            } else null
        }

        if (bytes != null) {
            artworkCache[imageUrl] = bytes
            builder.setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }
        
        // Always set URI as well for controllers that prefer it or as fallback
        if (imageUrl.startsWith("http")) {
            builder.setArtworkUri(Uri.parse(imageUrl))
        }
    }

    private fun extractIcyTitle(metadata: Metadata): String? {
        for (index in 0 until metadata.length()) {
            val entry = metadata[index]
            if (entry is IcyInfo) {
                val title = entry.title?.trim()
                if (!title.isNullOrBlank()) return title
            }
        }
        return null
    }

    private fun buildTrackMetadataFromIcy(icyTitle: String): MediaMetadata {
        val (artist, title) = parseArtistAndTitle(icyTitle)
        val normalizedTitle = title.ifBlank { icyTitle.trim() }
        return MediaMetadata.Builder()
            .setTitle(normalizedTitle.ifBlank { null })
            .setArtist(artist.ifBlank { null })
            .build()
    }

    private fun parseArtistAndTitle(value: String): Pair<String, String> {
        val separators = listOf(" - ", " – ", " — ")
        separators.forEach { separator ->
            val separatorIndex = value.indexOf(separator)
            if (separatorIndex > 0 && separatorIndex < value.length - separator.length) {
                val artist = value.substring(0, separatorIndex).trim()
                val title = value.substring(separatorIndex + separator.length).trim()
                if (artist.isNotBlank() && title.isNotBlank()) {
                    return artist to title
                }
            }
        }
        return "" to value.trim()
    }

    private fun trackMetadataEquivalent(left: MediaMetadata, right: MediaMetadata): Boolean {
        return left.title?.toString().orEmpty() == right.title?.toString().orEmpty() &&
            left.artist?.toString().orEmpty() == right.artist?.toString().orEmpty()
    }

    private fun isEmptyTrackMetadata(metadata: MediaMetadata): Boolean {
        return metadata.title.isNullOrBlank() && metadata.artist.isNullOrBlank()
    }

    private fun retryCurrentItem(reason: String) {
        val exoPlayer = player ?: return
        if (exoPlayer.mediaItemCount <= 0) return

        retryCount += 1
        val delayMs = when {
            retryCount <= 1 -> 1000L
            retryCount <= 3 -> 3000L
            else -> 6000L
        }

        Log.d(TAG, "Svc retrying current item reason=$reason count=$retryCount delay=${delayMs}ms")
        cancelRetry()
        retryJob = serviceScope.launch {
            delay(delayMs)
            val activePlayer = player ?: return@launch
            if (activePlayer.mediaItemCount <= 0) return@launch
            val index = activePlayer.currentMediaItemIndex.coerceIn(0, activePlayer.mediaItemCount - 1)
            activePlayer.stop()
            activePlayer.seekTo(index, 0)
            activePlayer.prepare()
            activePlayer.play()
        }
    }

    private fun cancelRetry() {
        retryJob?.cancel()
        retryJob = null
    }

    private fun scheduleBufferingRecovery() {
        cancelBufferingRecovery()
        bufferingRecoveryJob = serviceScope.launch {
            delay(15_000L)
            val exoPlayer = player ?: return@launch
            if (exoPlayer.playbackState == Player.STATE_BUFFERING && exoPlayer.playWhenReady) {
                retryCurrentItem("buffer_timeout")
            }
        }
    }

    private fun startStallRecovery() {
        val exoPlayer = player ?: return
        lastObservedPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
        lastPositionRealtimeMs = SystemClock.elapsedRealtime()
        scheduleStallCheck()
    }

    private fun scheduleStallCheck() {
        cancelStallRecovery()
        stallRecoveryJob = serviceScope.launch {
            delay(5_000L)
            val exoPlayer = player ?: return@launch
            if (!exoPlayer.playWhenReady || exoPlayer.playbackState != Player.STATE_READY) return@launch

            val nowRealtime = SystemClock.elapsedRealtime()
            val currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
            if (currentPosition > lastObservedPositionMs + 100L) {
                lastObservedPositionMs = currentPosition
                lastPositionRealtimeMs = nowRealtime
                scheduleStallCheck()
                return@launch
            }

            val stallDuration = nowRealtime - lastPositionRealtimeMs
            if (stallDuration >= 30_000L) {
                Log.w(TAG, "Svc stall detected! duration=${stallDuration}ms position=$currentPosition")
                retryCurrentItem("stall_ready")
                return@launch
            }
            scheduleStallCheck()
        }
    }

    private fun cancelBufferingRecovery() {
        bufferingRecoveryJob?.cancel()
        bufferingRecoveryJob = null
    }

    private fun cancelStallRecovery() {
        stallRecoveryJob?.cancel()
        stallRecoveryJob = null
    }

    private fun logAudioOutputState(tag: String) {
        val exoPlayer = player ?: return
        val musicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val musicMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        Log.d(TAG, "Svc audio $tag playerVolume=${exoPlayer.volume} deviceMusicVolume=$musicVolume/$musicMax")
    }
}
