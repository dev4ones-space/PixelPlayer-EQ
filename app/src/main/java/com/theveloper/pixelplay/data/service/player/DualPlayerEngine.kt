package com.theveloper.pixelplay.data.service.player

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp4.Mp4Extractor
//import androidx.media3.exoplayer.ffmpeg.FfmpegAudioRenderer
import com.theveloper.pixelplay.data.model.TransitionSettings
import com.theveloper.pixelplay.utils.envelope
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.asStateFlow // Added
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

import com.theveloper.pixelplay.data.netease.NeteaseStreamProxy
import com.theveloper.pixelplay.data.navidrome.NavidromeStreamProxy
import com.theveloper.pixelplay.data.qqmusic.QqMusicStreamProxy
import com.theveloper.pixelplay.data.telegram.TelegramRepository
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import android.net.Uri
import java.io.File

/**
 * Manages two ExoPlayer instances (A and B) to enable seamless transitions.
 *
 * Player A is the designated "master" player, which is exposed to the MediaSession.
 * Player B is the auxiliary player used to pre-buffer and fade in the next track.
 * After a transition, Player A adopts the state of Player B, ensuring continuity.
 */
@OptIn(UnstableApi::class)
@Singleton
class DualPlayerEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telegramRepository: TelegramRepository,
    private val telegramStreamProxy: com.theveloper.pixelplay.data.telegram.TelegramStreamProxy,
    private val neteaseStreamProxy: NeteaseStreamProxy,
    private val qqMusicStreamProxy: QqMusicStreamProxy,
    private val navidromeStreamProxy: NavidromeStreamProxy,
    private val jellyfinStreamProxy: com.theveloper.pixelplay.data.jellyfin.JellyfinStreamProxy,
    private val telegramCacheManager: com.theveloper.pixelplay.data.telegram.TelegramCacheManager,
    private val connectivityStateHolder: com.theveloper.pixelplay.presentation.viewmodel.ConnectivityStateHolder
) {
    private companion object {
        private const val AUDIO_OFFLOAD_BUFFERING_FALLBACK_MS = 4_000L
        private val LOCAL_MEDIA_SCHEMES = setOf("content", "file", "android.resource")
        private val REMOTE_MEDIA_SCHEMES = setOf("http", "https", "telegram", "netease", "qqmusic", "navidrome", "jellyfin", "gdrive")
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    var hiFiModeEnabled: Boolean = false
        private set
    // Always disabled: offload routes compressed audio to hardware DSP, bypassing the
    // Android audio mixer entirely — system-wide DSP (ViperFX) cannot attach to offloaded streams.
    private var audioOffloadEnabled = false
    private var transitionJob: Job? = null
    private var bufferingFallbackJob: Job? = null
    private var transitionRunning = false

    private lateinit var playerA: ExoPlayer
    private lateinit var playerB: ExoPlayer

    private val onPlayerSwappedListeners = mutableListOf<(Player) -> Unit>()
    private val onTransitionFinishedListeners = mutableListOf<() -> Unit>()
    
    // Active Audio Session ID Flow
    private val _activeAudioSessionId = kotlinx.coroutines.flow.MutableStateFlow(0)
    val activeAudioSessionId: kotlinx.coroutines.flow.StateFlow<Int> = _activeAudioSessionId.asStateFlow()

    // Audio Focus Management
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var isFocusLossPause = false
    private var lastPlayWhenReadyAtMs: Long = 0L
    private var lastPlayingAtMs: Long = 0L

    /**
     * Set by MusicService once ReplayGain for the incoming track is known.
     * The crossfade loop reads this at the end instead of hard-coding 1f,
     * so the incoming track reaches its correct RG volume without a jump.
     * Reset to null after each transition.
     */
    var incomingTrackReplayGainVolume: Float? = null

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Timber.tag("TransitionDebug").d("AudioFocus LOSS. Pausing.")
                isFocusLossPause = false
                playerA.playWhenReady = false
                playerB.playWhenReady = false
                abandonAudioFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Timber.tag("TransitionDebug").d("AudioFocus LOSS_TRANSIENT. Pausing.")
                isFocusLossPause = true
                playerA.playWhenReady = false
                playerB.playWhenReady = false
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Timber.tag("TransitionDebug").d("AudioFocus GAIN. Resuming if paused by loss.")
                if (isFocusLossPause) {
                    isFocusLossPause = false
                    playerA.playWhenReady = true
                    if (transitionRunning) playerB.playWhenReady = true
                }
            }
        }
    }

    // Listener to attach to the active master player (playerA)
    private val masterPlayerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) {
                lastPlayWhenReadyAtMs = SystemClock.elapsedRealtime()
                requestAudioFocus()
            } else {
                cancelAudioOffloadFallback()
                if (!isFocusLossPause) {
                    abandonAudioFocus()
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                lastPlayingAtMs = SystemClock.elapsedRealtime()
                cancelAudioOffloadFallback()
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            // Integración de test/telegram-streaming-integration
            if (audioSessionId != 0 && _activeAudioSessionId.value != audioSessionId) {
                _activeAudioSessionId.value = audioSessionId
                Timber.tag("TransitionDebug").d("Master audio session changed: %d", audioSessionId)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            cancelAudioOffloadFallback()
            // Integración de feature/telegram-cloud-sync
            val uri = mediaItem?.localConfiguration?.uri
            if (uri?.scheme == "telegram") {
                scope.launch {
                    val result = telegramRepository.resolveTelegramUri(uri.toString())
                    val fileId = result?.first
                    telegramCacheManager.setActivePlayback(fileId)
                    Timber.tag("DualPlayerEngine").d("Telegram playback active: fileId=$fileId")
                }
            } else {
                // Limpieza para canciones que no son de Telegram
                telegramCacheManager.setActivePlayback(null)
            }
            // Upgrade/downgrade wake policy so local playback does not keep the radio awake
            // and cloud/remote playback still holds the network wake lock.
            applyWakeModeForCurrentItem()

            // --- Pre-Resolve Next/Prev Tracks para Performance ---
            try {
                val currentIndex = playerA.currentMediaItemIndex
                if (currentIndex != C.INDEX_UNSET) {
                    // 1. Pre-resolver SIGUIENTE
                    if (currentIndex + 1 < playerA.mediaItemCount) {
                        val nextItem = playerA.getMediaItemAt(currentIndex + 1)
                        val nextUri = nextItem.localConfiguration?.uri
                        if (nextUri?.scheme == "telegram") {
                            telegramRepository.preResolveTelegramUri(nextUri.toString())
                        } else if (nextUri?.scheme == "netease" || nextUri?.scheme == "qqmusic" || nextUri?.scheme == "navidrome" || nextUri?.scheme == "jellyfin") {
                            scope.launch { resolveCloudUri(nextUri) }
                        }
                    }
                    // 2. Pre-resolver ANTERIOR (para rapidez al retroceder)
                    if (currentIndex - 1 >= 0) {
                        val prevItem = playerA.getMediaItemAt(currentIndex - 1)
                        val prevUri = prevItem.localConfiguration?.uri
                        if (prevUri?.scheme == "telegram") {
                            telegramRepository.preResolveTelegramUri(prevUri.toString())
                        } else if (prevUri?.scheme == "netease" || prevUri?.scheme == "qqmusic" || prevUri?.scheme == "navidrome" || prevUri?.scheme == "jellyfin") {
                            scope.launch { resolveCloudUri(prevUri) }
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Error during pre-resolution in onMediaItemTransition")
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    // Detect HAL offload reset: device advertises offload support but the
                    // audio HAL disconnects the compress-offload stream within milliseconds
                    // of starting playback (seen as PLAYING -> BUFFERING within ~500ms).
                    // When this happens, immediately fall back to PCM rendering without
                    // waiting for the 4-second timeout — works on any device generically.
                    val timeSincePlayingMs = SystemClock.elapsedRealtime() - lastPlayingAtMs
                    if (audioOffloadEnabled && !transitionRunning &&
                        lastPlayingAtMs > 0L && timeSincePlayingMs < 500L) {
                        disableAudioOffloadForSession(
                            reason = "HAL offload reset detected: STATE_BUFFERING after ${timeSincePlayingMs}ms of playback " +
                                "(mediaId=${playerA.currentMediaItem?.mediaId})"
                        )
                    } else {
                        scheduleAudioOffloadFallbackIfNeeded(playerA)
                    }
                }
                Player.STATE_READY, Player.STATE_IDLE, Player.STATE_ENDED -> cancelAudioOffloadFallback()
            }
        }
    }

    fun addPlayerSwapListener(listener: (Player) -> Unit) {
        onPlayerSwappedListeners.add(listener)
    }

    fun removePlayerSwapListener(listener: (Player) -> Unit) {
        onPlayerSwappedListeners.remove(listener)
    }

    fun addTransitionFinishedListener(listener: () -> Unit) {
        onTransitionFinishedListeners.add(listener)
    }

    fun removeTransitionFinishedListener(listener: () -> Unit) {
        onTransitionFinishedListeners.remove(listener)
    }

    /** The master player instance that should be connected to the MediaSession. */
    val masterPlayer: Player
        get() = playerA

    fun isTransitionRunning(): Boolean = transitionRunning

    /**
     * Returns the audio session ID of the master player.
     * Use this to attach audio effects like Equalizer.
     */
    /**
     * Returns the audio session ID of the master player.
     * Use this to attach audio effects like Equalizer.
     */
    fun getAudioSessionId(): Int = playerA.audioSessionId

    private var isReleased = false

    // Cache of pre-resolved URIs: original cloud URI string -> resolved playable URI
    private val resolvedUriCache = java.util.concurrent.ConcurrentHashMap<String, Uri>()

    init {
        initialize()
    }

    fun initialize() {
        if (!isReleased && ::playerA.isInitialized && playerA.applicationLooper.thread.isAlive) return

        // Clean up if needed (though unlikely to be called if already initialized and alive)
        if (::playerA.isInitialized) {
            try { playerA.release() } catch (e: Exception) { /* Ignore */ }
        }
        if (::playerB.isInitialized) {
            try { playerB.release() } catch (e: Exception) { /* Ignore */ }
        }

        // We initialize BOTH players with NO internal focus handling.
        // We manage Audio Focus manually via AudioFocusManager.
        playerA = buildPlayer(handleAudioFocus = false)
        playerB = buildPlayer(handleAudioFocus = false)

        // Attach listener to initial master
        playerA.addListener(masterPlayerListener)

        // Initialize active session ID
        _activeAudioSessionId.value = playerA.audioSessionId
        
        isReleased = false
    }

    private fun requestAudioFocus() {
        if (audioFocusRequest != null) return // Already have or requested

        val attributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusChangeListener)
            .build()

        val result = audioManager.requestAudioFocus(request)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioFocusRequest = request
        } else {
            Timber.tag("TransitionDebug").w("AudioFocus Request Failed: $result")
            playerA.playWhenReady = false
        }
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
            audioFocusRequest = null
        }
    }

    private fun scheduleAudioOffloadFallbackIfNeeded(player: ExoPlayer) {
        cancelAudioOffloadFallback()
        if (!audioOffloadEnabled || transitionRunning || !player.playWhenReady) return
        if (!isLikelyLocalMedia(player.currentMediaItem)) return

        val watchedMediaId = player.currentMediaItem?.mediaId ?: return
        bufferingFallbackJob = scope.launch {
            delay(AUDIO_OFFLOAD_BUFFERING_FALLBACK_MS)

            val currentMediaId = player.currentMediaItem?.mediaId
            if (!audioOffloadEnabled || transitionRunning || player !== playerA) return@launch
            if (currentMediaId != watchedMediaId) return@launch
            if (player.playbackState != Player.STATE_BUFFERING || player.isPlaying || !player.playWhenReady) return@launch
            if (player.currentPosition > 1_000L || player.bufferedPosition <= 0L) return@launch

            val timeSincePlayRequestMs = (SystemClock.elapsedRealtime() - lastPlayWhenReadyAtMs).coerceAtLeast(0L)
            if (timeSincePlayRequestMs < AUDIO_OFFLOAD_BUFFERING_FALLBACK_MS) return@launch

            disableAudioOffloadForSession(
                reason = "Local media stayed buffering for ${AUDIO_OFFLOAD_BUFFERING_FALLBACK_MS}ms after play request " +
                    "(mediaId=$watchedMediaId, buffered=${player.bufferedPosition})"
            )
        }
    }

    private fun cancelAudioOffloadFallback() {
        bufferingFallbackJob?.cancel()
        bufferingFallbackJob = null
    }

    private fun isLikelyLocalMedia(mediaItem: MediaItem?): Boolean {
        val scheme = mediaItem?.localConfiguration?.uri?.scheme?.lowercase()
        return scheme == null || scheme in LOCAL_MEDIA_SCHEMES
    }

    /**
     * Selects the cheapest wake policy that still keeps playback reliable.
     * Network wake is only needed when the current queue item actually talks to the network —
     * local file playback keeps only CPU awake so the device can sleep the radio.
     */
    private fun wakeModeFor(mediaItem: MediaItem?): Int {
        val scheme = mediaItem?.localConfiguration?.uri?.scheme?.lowercase()
        return if (scheme != null && scheme in REMOTE_MEDIA_SCHEMES) {
            C.WAKE_MODE_NETWORK
        } else {
            C.WAKE_MODE_LOCAL
        }
    }

    private fun applyWakeModeForCurrentItem() {
        if (!::playerA.isInitialized) return
        val mode = wakeModeFor(playerA.currentMediaItem)
        try {
            playerA.setWakeMode(mode)
            if (::playerB.isInitialized) {
                playerB.setWakeMode(mode)
            }
        } catch (e: Exception) {
            Timber.tag("DualPlayerEngine").w(e, "Failed to update wake mode")
        }
    }

    private fun shouldDisableAudioOffloadByDefault(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        val isXiaomiFamilyDevice = manufacturer == "xiaomi" || brand == "xiaomi" || brand == "redmi" || brand == "poco"
        if (isXiaomiFamilyDevice && Build.VERSION.SDK_INT >= 36) return true

        // Pixel devices on Android 14+ (SDK 34+) advertise Opus compress-offload support,
        // but the speaker HAL port rejects the format at runtime ("format not found in profile
        // list") and immediately disconnects the offload stream, causing stuttering on playback
        // start. Offload is disabled upfront so ExoPlayer falls back to PCM rendering.
        val isGooglePixel = manufacturer == "google" && brand == "google"
        if (isGooglePixel && Build.VERSION.SDK_INT >= 34) return true

        return false
    }

    private fun disableAudioOffloadForSession(reason: String) {
        if (!audioOffloadEnabled) return
        if (transitionRunning) {
            Timber.tag("DualPlayerEngine").w("Skipping offload fallback during active transition. %s", reason)
            return
        }

        audioOffloadEnabled = false
        rebuildPlayersPreservingMasterState(
            logMessage = "Audio offload disabled for current session after playback stall. $reason"
        )
    }

    private fun rebuildPlayersPreservingMasterState(logMessage: String) {
        cancelAudioOffloadFallback()

        val desiredPlayWhenReady = playerA.playWhenReady
        val positionMs = playerA.currentPosition
        val currentIndex = playerA.currentMediaItemIndex.coerceAtLeast(0)
        val mediaItems = (0 until playerA.mediaItemCount).map { playerA.getMediaItemAt(it) }
        val repeatMode = playerA.repeatMode
        val shuffleMode = playerA.shuffleModeEnabled
        val volume = playerA.volume
        val pauseAtEnd = playerA.pauseAtEndOfMediaItems
        val playbackParameters: PlaybackParameters = playerA.playbackParameters

        playerA.removeListener(masterPlayerListener)
        playerA.release()
        playerB.release()

        playerA = buildPlayer(handleAudioFocus = false)
        playerB = buildPlayer(handleAudioFocus = false)

        playerA.addListener(masterPlayerListener)
        playerA.volume = volume
        playerA.pauseAtEndOfMediaItems = pauseAtEnd
        playerA.playbackParameters = playbackParameters

        if (mediaItems.isNotEmpty()) {
            playerA.setMediaItems(mediaItems, currentIndex, positionMs)
            playerA.repeatMode = repeatMode
            playerA.shuffleModeEnabled = shuffleMode
            playerA.prepare()
            playerA.playWhenReady = desiredPlayWhenReady
            applyWakeModeForCurrentItem()
        }

        _activeAudioSessionId.value = playerA.audioSessionId
        onPlayerSwappedListeners.forEach { it(playerA) }

        Timber.tag("DualPlayerEngine").d(logMessage)
    }

    private fun buildPlayer(handleAudioFocus: Boolean): ExoPlayer {
        val mediaCodecSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val decoderInfos = MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder
            )

            // Some devices advertise ALAC decoders that stall on high-bitrate M4A files.
            // Prefer stable software codecs when available, otherwise let the FFmpeg
            // extension renderer handle ALAC by hiding the platform candidates.
            if (mimeType.equals(MimeTypes.AUDIO_ALAC, ignoreCase = true)) {
                val softwareDecoders = decoderInfos.filterNot { it.hardwareAccelerated }
                softwareDecoders.ifEmpty { emptyList() }
            } else {
                // Media3 returns decoders in raw MediaCodecList order and only re-sorts
                // by format support, never by hardware preference. On some Samsung/OneUI
                // builds the platform enumerates c2.android.* (software) ahead of the
                // vendor c2.sec.* hardware decoders, so ExoPlayer ends up picking software
                // decoding even when a Samsung hardware decoder is available. Re-sort
                // hardware-accelerated decoders to the front so c2.sec.mp3.decoder,
                // c2.sec.flac.decoder, etc. are tried first. sortedByDescending is stable,
                // so the relative order within each group is preserved.
                decoderInfos.sortedByDescending { it.hardwareAccelerated }
            }
        }
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean
            ): AudioSink {
                // Keep Media3's default renderer wiring intact and only customize the sink.
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(hiFiModeEnabled)
                    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                    .setAudioProcessorChain(
                        // Downsample >192 kHz before AudioTrack to avoid ultra-hi-res device hangs,
                        // then downmix multichannel PCM when stereo output is required.
                        DefaultAudioSink.DefaultAudioProcessorChain(
                            HiResSampleRateCapAudioProcessor(),
                            SurroundDownmixProcessor()
                        )
                    )
                    .build()
            }
        }.setEnableAudioFloatOutput(hiFiModeEnabled) // Disable Float output helper
         .setMediaCodecSelector(mediaCodecSelector)
         .setEnableDecoderFallback(true)
         .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
            
        // Lightweight synchronous resolver: only performs cache lookups, NEVER blocks.
        // All heavy resolution (network I/O, proxy readiness) is done ahead of time
        // in resolveCloudUri() which is called from coroutines before ExoPlayer sees the URI.
        val resolver = object : ResolvingDataSource.Resolver {
            override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
                val uri = dataSpec.uri
                val scheme = uri.scheme
                if (scheme == "telegram" || scheme == "netease" || scheme == "qqmusic" || scheme == "navidrome" || scheme == "jellyfin") {
                    val originalUri = uri.toString()
                    val resolved = resolvedUriCache[originalUri]
                    if (resolved != null) {
                        Timber.tag("DualPlayerEngine").d("resolveDataSpec: cache hit for $scheme URI")
                        return dataSpec.buildUpon().setUri(resolved).build()
                    }
                    
                    Timber.tag("DualPlayerEngine").w("resolveDataSpec: cache MISS for %s — scheduling async pre-resolution", originalUri)
                    scope.launch(Dispatchers.IO) {
                        runCatching { resolveCloudUri(uri) }
                            .onFailure { error ->
                                Timber.tag("DualPlayerEngine").e(error, "resolveDataSpec: Async resolution failed for %s", originalUri)
                            }
                    }
                }
                return dataSpec
            }
        }
        
        val dataSourceFactory = DefaultDataSource.Factory(context)
        val resolvingFactory = ResolvingDataSource.Factory(dataSourceFactory, resolver)
        val extractorsFactory = DefaultExtractorsFactory()
            // Some vendor-produced M4A files expose broken edit lists that make seek
            // drift or snap back. Ignore them so MP4-family local playback stays seekable.
            .setMp4ExtractorFlags(Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)

        // Tune LoadControl to prevent "loop of death" (underrun -> start -> underrun)
        // Increase bufferForPlaybackMs to wait for more data before starting/resuming.
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                30_000, // Min buffer 30s
                60_000, // Max buffer 60s
                5_000,  // Buffer for playback start (Increased from 2.5s for stability)
                5_000   // Buffer for rebuffer (Increased to 5s to stop rapid cycling)
            )
            .build()

        return ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolvingFactory, extractorsFactory))
            .setLoadControl(loadControl)
            .build().apply {
            setAudioAttributes(audioAttributes, handleAudioFocus)
            val offloadPreferences = TrackSelectionParameters.AudioOffloadPreferences.Builder()
                .setAudioOffloadMode(
                    if (audioOffloadEnabled) {
                        TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                    } else {
                        TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
                    }
                )
                .build()
            setTrackSelectionParameters(
                trackSelectionParameters
                    .buildUpon()
                    .setAudioOffloadPreferences(offloadPreferences)
                    .build()
            )
            setHandleAudioBecomingNoisy(true) // Force player to pause automatically when audio is rerouted from a headset to device speakers
            // Default to CPU-only wake. Upgraded to WAKE_MODE_NETWORK dynamically when the
            // current item is a remote/cloud source via applyWakeModeForCurrentItem().
            // Keeping local playback on WAKE_MODE_LOCAL lets the radio sleep, which is one
            // of the biggest heat savings for long sessions on weaker phones.
            setWakeMode(C.WAKE_MODE_LOCAL)
            // Explicitly keep both players live so they can overlap without affecting each other
            playWhenReady = false
            Timber.tag("DualPlayerEngine").d("Built player with audio offload %s", if (audioOffloadEnabled) "enabled" else "disabled")
        }
    }

    /**
     * Enables or disables pausing at the end of media items for the master player.
     * This is crucial for controlling the transition manually.
     */
    fun setPauseAtEndOfMediaItems(shouldPause: Boolean) {
        playerA.pauseAtEndOfMediaItems = shouldPause
    }

    /**
     * Applies Hi-Fi mode (float output) setting. Rebuilds both players to apply the change.
     * Must be called from the main thread.
     */
    fun setHiFiMode(enabled: Boolean) {
        if (hiFiModeEnabled == enabled) return
        if (enabled && !HiFiCapabilityChecker.isSupported()) {
            Timber.tag("DualPlayerEngine").w("Hi-Fi mode requested but device does not support PCM_FLOAT AudioTrack — ignoring")
            return
        }
        hiFiModeEnabled = enabled

        rebuildPlayersPreservingMasterState("Hi-Fi mode set to $enabled — players rebuilt")
    }

    /**
     * Resolves a cloud URI (telegram:// or netease://) to a playable URI.
     * Performs all network I/O and proxy readiness checks on the calling coroutine,
     * keeping ExoPlayer's playback thread free from blocking.
     *
     * Results are cached in [resolvedUriCache] for the synchronous [resolveDataSpec] to use.
     *
     * @return The resolved playable URI, or the original URI if resolution fails/not needed.
     */
    suspend fun resolveCloudUri(uri: Uri): Uri {
        val uriString = uri.toString()

        // Fast path: already resolved
        resolvedUriCache[uriString]?.let { return it }

        val resolved: Uri? = when (uri.scheme) {
            "telegram" -> resolveTelegramUriAsync(uri, uriString)
            "netease" -> resolveNeteaseUriAsync(uriString)
            "qqmusic" -> resolveQqMusicUriAsync(uriString)
            "navidrome" -> resolveNavidromeUriAsync(uriString)
            "jellyfin" -> resolveJellyfinUriAsync(uriString)
            else -> null
        }

        if (resolved != null) {
            resolvedUriCache[uriString] = resolved
            return resolved
        }
        return uri
    }

    private suspend fun resolveTelegramUriAsync(uri: Uri, uriString: String): Uri? {
        var fileId: Int? = null
        var fileSize: Long = 0L

        val pathSegments = uri.pathSegments
        if (pathSegments.isNotEmpty()) {
            val result = telegramRepository.resolveTelegramUri(uriString)
            fileId = result?.first
            fileSize = result?.second ?: 0L
        } else {
            // Fallback to Legacy Scheme: telegram://fileId (host)
            fileId = uri.host?.toIntOrNull()
        }

        if (fileId == null) return null

        Timber.tag("DualPlayerEngine").d("Async resolving Telegram URI for fileId: $fileId")

        // Check if file is already downloaded to use direct file access
        val fileInfo = telegramRepository.getFile(fileId)
        if (fileInfo?.local?.isDownloadingCompleted == true && fileInfo.local.path.isNotEmpty()) {
            Timber.tag("DualPlayerEngine").d("File $fileId is downloaded. Using direct file playback.")
            return Uri.fromFile(File(fileInfo.local.path))
        }

        // Not cached locally. Check connectivity.
        val isOnline = connectivityStateHolder.isOnline.value
        if (!isOnline) {
            Timber.tag("DualPlayerEngine").w("Blocked playback: Offline and not cached (fileId=$fileId).")
            connectivityStateHolder.triggerOfflineBlockedEvent()
            return null
        }

        Timber.tag("DualPlayerEngine").d("File $fileId not downloaded. Using StreamProxy.")

        val proxyReady = telegramStreamProxy.ensureReady(5_000L)
        if (!proxyReady) {
            Timber.tag("DualPlayerEngine").e("StreamProxy not ready after timeout")
            return null
        }

        val proxyUrl = telegramStreamProxy.getProxyUrl(fileId, fileSize)
        return if (proxyUrl.isNotEmpty()) Uri.parse(proxyUrl) else null
    }

    private suspend fun resolveNeteaseUriAsync(uriString: String): Uri? {
        Timber.tag("DualPlayerEngine").d("Async resolving Netease URI: $uriString")

        val proxyReady = neteaseStreamProxy.ensureReady(5_000L)
        if (!proxyReady) {
            Timber.tag("DualPlayerEngine").e("NeteaseStreamProxy not ready after timeout")
            return null
        }

        val proxyUrl = neteaseStreamProxy.resolveNeteaseUri(uriString)
        if (!proxyUrl.isNullOrBlank()) {
            return Uri.parse(proxyUrl)
        }

        Timber.tag("DualPlayerEngine").w("Failed to resolve Netease URI: $uriString")
        return null
    }

    private suspend fun resolveQqMusicUriAsync(uriString: String): Uri? {
        Timber.tag("DualPlayerEngine").d("Async resolving QQ Music URI: $uriString")

        val proxyReady = qqMusicStreamProxy.ensureReady(5_000L)
        if (!proxyReady) {
            Timber.tag("DualPlayerEngine").e("QqMusicStreamProxy not ready after timeout")
            return null
        }

        // Pre-fetch the real stream URL now (network call) so the proxy cache is
        // warm by the time ExoPlayer makes its HTTP request to the local proxy.
        qqMusicStreamProxy.warmUpStreamUrl(uriString)

        val proxyUrl = qqMusicStreamProxy.resolveQqMusicUri(uriString)
        if (!proxyUrl.isNullOrBlank()) {
            return Uri.parse(proxyUrl)
        }

        Timber.tag("DualPlayerEngine").w("Failed to resolve QQ Music URI: $uriString")
        return null
    }

    private suspend fun resolveNavidromeUriAsync(uriString: String): Uri? {
        Timber.tag("DualPlayerEngine").d("Async resolving Navidrome URI: $uriString")

        val proxyReady = navidromeStreamProxy.ensureReady(5_000L)
        if (!proxyReady) {
            Timber.tag("DualPlayerEngine").e("NavidromeStreamProxy not ready after timeout")
            return null
        }

        // Pre-fetch the real stream URL now (network call) so the proxy cache is
        // warm by the time ExoPlayer makes its HTTP request to the local proxy.
        navidromeStreamProxy.warmUpStreamUrl(uriString)

        val proxyUrl = navidromeStreamProxy.resolveNavidromeUri(uriString)
        if (!proxyUrl.isNullOrBlank()) {
            return Uri.parse(proxyUrl)
        }

        Timber.tag("DualPlayerEngine").w("Failed to resolve Navidrome URI: $uriString")
        return null
    }

    private suspend fun resolveJellyfinUriAsync(uriString: String): Uri? {
        Timber.tag("DualPlayerEngine").d("Async resolving Jellyfin URI: $uriString")

        val proxyReady = jellyfinStreamProxy.ensureReady(5_000L)
        if (!proxyReady) {
            Timber.tag("DualPlayerEngine").e("JellyfinStreamProxy not ready after timeout")
            return null
        }

        jellyfinStreamProxy.warmUpStreamUrl(uriString)

        val proxyUrl = jellyfinStreamProxy.resolveJellyfinUri(uriString)
        if (!proxyUrl.isNullOrBlank()) {
            return Uri.parse(proxyUrl)
        }

        Timber.tag("DualPlayerEngine").w("Failed to resolve Jellyfin URI: $uriString")
        return null
    }

    /**
     * Resolves a MediaItem's cloud URI (if any) and returns a copy with the resolved URI.
     * For non-cloud URIs, returns the original MediaItem unchanged.
     */
    suspend fun resolveMediaItem(mediaItem: MediaItem): MediaItem {
        val uri = mediaItem.localConfiguration?.uri ?: return mediaItem
        val scheme = uri.scheme
        if (scheme != "telegram" && scheme != "netease" && scheme != "qqmusic" && scheme != "navidrome" && scheme != "jellyfin") return mediaItem

        val resolvedUri = resolveCloudUri(uri)
        if (resolvedUri == uri) return mediaItem // Resolution failed or not needed

        // Rebuild MediaItem with resolved URI, preserving metadata
        return mediaItem.buildUpon()
            .setUri(resolvedUri)
            .build()
    }

    /**
     * Prepares the auxiliary player (Player B) with the next media item.
     * Cloud URIs are resolved asynchronously before passing to ExoPlayer.
     */
    suspend fun prepareNext(mediaItem: MediaItem, startPositionMs: Long = 0L) {
        try {
            Timber.tag("TransitionDebug").d("Engine: prepareNext called for %s", mediaItem.mediaId)

            // Pre-resolve cloud URI on the coroutine (non-blocking for ExoPlayer)
            val resolvedItem = resolveMediaItem(mediaItem)

            playerB.stop()
            playerB.clearMediaItems()
            playerB.playWhenReady = false
            playerB.setMediaItem(resolvedItem)
            
            // Wake mode is configured in buildPlayer(), so we don't reapply it per item here.
            playerB.prepare()
            playerB.volume = 0f // Start silent
            if (startPositionMs > 0) {
                playerB.seekTo(startPositionMs)
            } else {
                playerB.seekTo(0)
            }
            // Critical: leave B paused so it can start instantly when asked
            playerB.pause()
            Timber.tag("TransitionDebug").d("Engine: Player B prepared, paused, volume=0f")
        } catch (e: Exception) {
            Timber.tag("TransitionDebug").e(e, "Failed to prepare next player")
        }
    }

    /**
     * If a track was pre-buffered in Player B, this cancels it.
     */
    fun cancelNext() {
        transitionJob?.cancel()
        transitionRunning = false
        if (playerB.mediaItemCount > 0) {
            Timber.tag("TransitionDebug").d("Engine: Cancelling next player")
            playerB.stop()
            playerB.clearMediaItems()
        }
        // Restore the master player volume. Use the current player volume if it's
        // already RG-adjusted (i.e. not 1f), otherwise fall back to 1f.
        // MusicService will re-apply the correct RG volume via onTimelineChanged.
        if (playerA.volume >= 0.99f) {
            // Volume was already at 1f or close — don't touch it, let MusicService handle it
        }
        incomingTrackReplayGainVolume = null
        setPauseAtEndOfMediaItems(false)
    }

    /**
     * Executes a transition based on the provided settings.
     */
    fun performTransition(settings: TransitionSettings) {
        transitionJob?.cancel()
        transitionRunning = true
        transitionJob = scope.launch {
            try {
                // Force Overlap for now as per instructions
                performOverlapTransition(settings)
            } catch (e: Exception) {
                Timber.tag("TransitionDebug").e(e, "Error performing transition")
                // Fallback: Restore volume and reset logic
                playerA.volume = 1f
                setPauseAtEndOfMediaItems(false)
                playerB.stop()
            } finally {
                transitionRunning = false
                onTransitionFinishedListeners.forEach { it() }
            }
        }
    }

    private suspend fun performOverlapTransition(settings: TransitionSettings) {
        Timber.tag("TransitionDebug").d("Starting Overlap/Crossfade. Duration: %d ms", settings.durationMs)

        if (playerB.mediaItemCount == 0) {
            Timber.tag("TransitionDebug").w("Skipping overlap - next player not prepared (count=0)")
            playerA.volume = 1f
            setPauseAtEndOfMediaItems(false)
            return
        }

        // Ensure B is fully buffered and paused at the starting position
        if (playerB.playbackState == Player.STATE_IDLE) {
            Timber.tag("TransitionDebug").d("Player B idle. Preparing now.")
            playerB.prepare()
        }

        // Wait until READY using a listener instead of polling to save CPU
        if (playerB.playbackState == Player.STATE_BUFFERING) {
            val ready = awaitPlayerReady(playerB, timeoutMs = 3000L)
            if (!ready) {
                Timber.tag("TransitionDebug").w("Player B not ready for overlap. State=%d", playerB.playbackState)
                playerA.volume = 1f
                setPauseAtEndOfMediaItems(false)
                return
            }
        } else if (playerB.playbackState != Player.STATE_READY) {
            Timber.tag("TransitionDebug").w("Player B not ready for overlap. State=%d", playerB.playbackState)
            playerA.volume = 1f
            setPauseAtEndOfMediaItems(false)
            return
        }

        // 1. Start Player B (Next Song) paused with volume=0 then immediately request play so overlap is audible
        // NOTE: playerA is currently playing "Old Song". playerB is "Next Song".
        // Capture the outgoing track's current volume (may be ReplayGain-adjusted) so the
        // fade-out envelope starts from the correct level instead of jumping back to 1.0.
        val outgoingStartVolume = playerA.volume.coerceIn(0f, 1f)
        playerB.volume = 0f
        // Do NOT force playerA.volume = 1f here — it would override the RG-adjusted value and
        // cause an audible jump on the outgoing track at the crossfade start.
        if (!playerA.isPlaying && playerA.playbackState == Player.STATE_READY) {
            // Ensure the outgoing track keeps rendering during the crossfade window
            playerA.play()
        }

        // Make sure PlayWhenReady is honored even if we had paused earlier
        playerB.playWhenReady = true
        playerB.play()

        Timber.tag("TransitionDebug").d("Player B started for overlap. Playing=%s state=%d", playerB.isPlaying, playerB.playbackState)

        // Ensure Player B is actually outputting audio before we begin the fade
        if (!playerB.isPlaying) {
            val playing = awaitPlayerPlaying(playerB, timeoutMs = 2000L)
            if (!playing) {
                Timber.tag("TransitionDebug").e("Player B failed to start in time. Aborting crossfade.")
                playerA.volume = 1f
                setPauseAtEndOfMediaItems(false)
                return
            }
        }

        // Small warmup to guarantee audible overlap
        delay(75)

        // --- SWAP PLAYERS EARLY (Before Fade) ---
        // This ensures the UI updates to show the "Next Song" immediately when the transition starts.

        // 1. Identify Outgoing (Old A) and Incoming (Old B / New A)
        val outgoingPlayer = playerA
        val incomingPlayer = playerB

        val isSelfTransition = outgoingPlayer.currentMediaItem?.mediaId == incomingPlayer.currentMediaItem?.mediaId

        val currentOutgoingIndex = outgoingPlayer.currentMediaItemIndex

        // History: All songs up to and including the current one (Old Song)
        val historyToTransfer = mutableListOf<MediaItem>()
        val historyEndIndex = if (isSelfTransition) currentOutgoingIndex else currentOutgoingIndex + 1
        for (i in 0 until historyEndIndex) {
            historyToTransfer.add(outgoingPlayer.getMediaItemAt(i))
        }

        // Future: Songs AFTER the Next Song
        // We skip the immediate next one because incomingPlayer already has it.
        val futureToTransfer = mutableListOf<MediaItem>()
        val futureStartIndex = if (isSelfTransition) currentOutgoingIndex + 1 else currentOutgoingIndex + 2
        for (i in futureStartIndex until outgoingPlayer.mediaItemCount) {
            futureToTransfer.add(outgoingPlayer.getMediaItemAt(i))
        }

        // 2. Transfer playback settings (repeat mode, shuffle mode) before swap
        val repeatModeToTransfer = outgoingPlayer.repeatMode
        val shuffleModeToTransfer = outgoingPlayer.shuffleModeEnabled
        incomingPlayer.repeatMode = repeatModeToTransfer
        incomingPlayer.shuffleModeEnabled = shuffleModeToTransfer
        Timber.tag("TransitionDebug").d("Transferred playback settings: repeatMode=%d, shuffle=%s", repeatModeToTransfer, shuffleModeToTransfer)

        // 3. Move manual focus management to the new master player
        outgoingPlayer.removeListener(masterPlayerListener)

        // 4. Swap References
        playerA = incomingPlayer
        playerB = outgoingPlayer
        
        // Critical: Reset pauseAtEndOfMediaItems on both players after swap.
        // The outgoing player (now B) had pauseAtEndOfMediaItems=true set before the transition started.
        // If we don't disable it, the outgoing player will pause itself when it reaches the end,
        // causing the "stops then restarts" glitch during crossfade.
        playerB.pauseAtEndOfMediaItems = false
        playerA.pauseAtEndOfMediaItems = false

        playerA.addListener(masterPlayerListener)
        // Ensure we hold focus for the new master
        if (playerA.playWhenReady) {
             requestAudioFocus()
        }

        // 4. Transfer History to New A (Prepend)
        if (historyToTransfer.isNotEmpty()) {
             playerA.addMediaItems(0, historyToTransfer)
             Timber.tag("TransitionDebug").d("Transferred %d history items to new player.", historyToTransfer.size)
        }

        // 5. Transfer Future to New A (Append)
        if (futureToTransfer.isNotEmpty()) {
             playerA.addMediaItems(futureToTransfer)
             Timber.tag("TransitionDebug").d("Transferred %d future items to new player.", futureToTransfer.size)
        }

        // 6. Notify Service to update MediaSession
        onPlayerSwappedListeners.forEach { it(playerA) }
        
        // Update Session ID for Equalizer
        _activeAudioSessionId.value = playerA.audioSessionId
        
        Timber.tag("TransitionDebug").d("Players swapped EARLY. UI should now show next song.")

        // *** FADE LOOP ***
        // playerA is now the Incoming/New Master.
        // playerB is now the Outgoing/Aux.

        val duration = settings.durationMs.toLong().coerceAtLeast(500L)
        // 30Hz volume ramp is indistinguishable from 60Hz at the AudioTrack frame boundary
        // for a multi-second crossfade; halves wake-ups during overlap.
        val stepMs = 32L
        var elapsed = 0L
        var lastLog = 0L

        while (elapsed <= duration) {
            val progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
            val volIn = envelope(progress, settings.curveIn)  // Incoming (Now A): 0 → 1
            val volOut = 1f - envelope(progress, settings.curveOut) // Outgoing (Now B): 1 → 0

            // Scale fade-in by the incoming track's ReplayGain target volume so the fade
            // goes from 0 → RG-volume instead of 0 → 1.0, eliminating the audible jump
            // at the end of the crossfade when the RG volume is applied.
            val incomingTarget = incomingTrackReplayGainVolume ?: 1f
            playerA.volume = (volIn * incomingTarget).coerceIn(0f, 1f)
            // Scale fade-out by the outgoing track's starting volume so a ReplayGain-adjusted
            // track (e.g. 0.75) fades from 0.75 → 0 instead of jumping to 1.0 first.
            playerB.volume = (volOut * outgoingStartVolume).coerceIn(0f, 1f)

            if (elapsed - lastLog >= 250) {
                Timber.tag("TransitionDebug").v("Loop: Progress=%.2f, VolNew=%.2f (Act: %.2f), VolOld=%.2f (Act: %.2f)",
                    progress, volIn, playerA.volume, volOut, playerB.volume)
                lastLog = elapsed
            }

            // Break early if either player stops in a non-ready state to avoid stuck fades.
            if (playerA.playbackState == Player.STATE_ENDED || playerB.playbackState == Player.STATE_ENDED) {
                Timber.tag("TransitionDebug").w("One of the players ended during crossfade (A=%d, B=%d)", playerA.playbackState, playerB.playbackState)
                break
            }

            delay(stepMs)
            elapsed += stepMs
        }

        Timber.tag("TransitionDebug").d("Overlap loop finished.")
        playerB.volume = 0f
        // Use the RG-computed volume for the incoming track if available,
        // otherwise fall back to 1f. This prevents the audible jump when
        // the crossfade ends and onTransitionFinished() fires.
        playerA.volume = incomingTrackReplayGainVolume ?: 1f
        incomingTrackReplayGainVolume = null

        // Clean up Old Player (now B)
        playerB.pause()
        playerB.stop()
        playerB.clearMediaItems()

        // Fresh Player Strategy: Release and recreate playerB to avoid OEM "stale session" tracking
        playerB.release()
        playerB = buildPlayer(handleAudioFocus = false)
        Timber.tag("TransitionDebug").d("Old Player (B) released and recreated fresh.")

        // Ensure New Player (A) is fully active and unrestricted
        setPauseAtEndOfMediaItems(false)
    }

    /**
     * Suspends until the player reaches STATE_READY, or until [timeoutMs] elapses.
     * Uses a Player.Listener callback instead of polling to avoid CPU burn.
     */
    private suspend fun awaitPlayerReady(player: ExoPlayer, timeoutMs: Long): Boolean {
        // Fast path: already ready
        if (player.playbackState == Player.STATE_READY) return true
        if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) return false

        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState != Player.STATE_BUFFERING) {
                            player.removeListener(this)
                            if (cont.isActive) cont.resume(playbackState == Player.STATE_READY)
                        }
                    }
                }
                player.addListener(listener)
                cont.invokeOnCancellation { player.removeListener(listener) }
                // Re-check after attaching listener to avoid race
                if (player.playbackState != Player.STATE_BUFFERING) {
                    player.removeListener(listener)
                    if (cont.isActive) cont.resume(player.playbackState == Player.STATE_READY)
                }
            }
        } ?: false
    }

    /**
     * Suspends until the player reports isPlaying == true, or until [timeoutMs] elapses.
     * Uses a Player.Listener callback instead of polling to avoid CPU burn.
     */
    private suspend fun awaitPlayerPlaying(player: ExoPlayer, timeoutMs: Long): Boolean {
        if (player.isPlaying) return true

        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            player.removeListener(this)
                            if (cont.isActive) cont.resume(true)
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        // If player reaches ENDED or IDLE, it will never start playing
                        if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                            player.removeListener(this)
                            if (cont.isActive) cont.resume(false)
                        }
                    }
                }
                player.addListener(listener)
                cont.invokeOnCancellation { player.removeListener(listener) }
                // Re-check after attaching listener to avoid race
                if (player.isPlaying) {
                    player.removeListener(listener)
                    if (cont.isActive) cont.resume(true)
                }
            }
        } ?: false
    }

    /**
     * Cleans up resources when the engine is no longer needed.
     */
    fun release() {
        transitionJob?.cancel()
        cancelAudioOffloadFallback()
        // OPT #11: Cancel the scope to prevent coroutine leaks after release().
        // Without this, any in-flight scope.launch { } coroutines (e.g. resolveCloudUri,
        // preResolveTelegramUri) would continue running even after both ExoPlayers are released.
        scope.coroutineContext[Job]?.cancel()
        abandonAudioFocus()
        if (::playerA.isInitialized) {
            playerA.removeListener(masterPlayerListener)
            playerA.release()
        }
        if (::playerB.isInitialized) playerB.release()
        isReleased = true
    }
}
