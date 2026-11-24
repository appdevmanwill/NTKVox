package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.audiofx.LoudnessEnhancer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.MainActivity
import com.example.R
import com.example.data.ClearVoxGraph
import com.example.model.AudioChunk
import com.example.model.RecordingSession
import com.example.storage.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlaybackServiceState(
    val sessionId: String? = null,
    val sessionTitle: String = "",
    val isPlaying: Boolean = false,
    val currentChunkIndex: Int = 0,
    val chunkCount: Int = 0,
    val globalPositionMs: Long = 0L,
    val totalDurationMs: Long = 1L,
    val playbackSpeed: Float = 1.0f,
    val boostDb: Int = 0,
    val loopStartMs: Long? = null,
    val loopEndMs: Long? = null
) {
    val hasLoadedSession: Boolean
        get() = sessionId != null

    val isLoopActive: Boolean
        get() = loopStartMs != null && loopEndMs != null && loopEndMs > loopStartMs
}

/**
 * Foreground Media Playback Service owning ExoPlayer, native Android [MediaSession] lock-screen
 * controls, and real-time [LoudnessEnhancer] quiet-voice volume boost.
 */
class VoxPlaybackService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): VoxPlaybackService = this@VoxPlaybackService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var sessionRepository: SessionRepository
    private lateinit var notificationManager: NotificationManager
    private lateinit var exoPlayer: ExoPlayer
    private lateinit var mediaSession: MediaSession

    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var positionJob: Job? = null

    private var loadedChunks: List<AudioChunk> = emptyList()
    private var chunkStartOffsets: List<Long> = emptyList()
    private var totalDurationMs: Long = 1L
    private var isForeground: Boolean = false

    private val _state = MutableStateFlow(PlaybackServiceState())
    val state: StateFlow<PlaybackServiceState> = _state.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        sessionRepository = ClearVoxGraph.sessionRepository(applicationContext)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createPlaybackChannel()

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttrs, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        mediaSession = MediaSession(this, "NTK_VOX_Playback").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    play()
                }

                override fun onPause() {
                    pause()
                }

                override fun onRewind() {
                    skipBy(-10_000L)
                }

                override fun onFastForward() {
                    skipBy(10_000L)
                }

                override fun onSeekTo(pos: Long) {
                    seekToGlobal(pos)
                }

                override fun onStop() {
                    stopAndDismiss()
                }
            })
            isActive = false
        }

        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                syncStateFromPlayer(forceNotification = true)
                if (isPlaying) {
                    startPositionTicker()
                } else {
                    positionJob?.cancel()
                    positionJob = null
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                syncStateFromPlayer(forceNotification = true)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    val loopA = _state.value.loopStartMs
                    val loopB = _state.value.loopEndMs
                    if (loopA != null && loopB != null && loopB > loopA) {
                        seekToGlobal(loopA)
                        exoPlayer.play()
                    } else {
                        positionJob?.cancel()
                        positionJob = null
                        _state.value = _state.value.copy(
                            isPlaying = false,
                            globalPositionMs = totalDurationMs
                        )
                        updateMediaSessionState()
                        updateNotification()
                    }
                } else {
                    syncStateFromPlayer(forceNotification = false)
                }
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                attachLoudnessEnhancer(audioSessionId)
            }
        })

        attachLoudnessEnhancer(exoPlayer.audioSessionId)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_FOREGROUND -> promoteToForegroundIfNeeded()
            ACTION_TOGGLE_PLAY -> {
                if (!isForeground && !exoPlayer.isPlaying) {
                    promoteToForegroundIfNeeded()
                }
                togglePlayPause()
            }
            ACTION_REWIND_10 -> skipBy(-10_000L)
            ACTION_FORWARD_10 -> skipBy(10_000L)
            ACTION_CLOSE -> stopAndDismiss()
        }
        return START_NOT_STICKY
    }

    fun loadSession(
        session: RecordingSession,
        autoPlay: Boolean = false,
        initialSpeed: Float? = null
    ) {
        val playable = session.chunks.filter { sessionRepository.getChunkFile(session, it).exists() }
        val sameSession = _state.value.sessionId == session.id &&
            loadedChunks.size == playable.size &&
            loadedChunks.zip(playable).all { (a, b) -> a.fileName == b.fileName && a.sizeBytes == b.sizeBytes }

        if (sameSession) {
            // Keep current position seamlessly when returning to the same recording
            if (_state.value.sessionTitle != session.title) {
                _state.value = _state.value.copy(sessionTitle = session.title)
                updateMediaSessionMetadata()
                if (isForeground) updateNotification()
            }
            if (autoPlay && !exoPlayer.isPlaying) play()
            return
        }

        loadedChunks = playable
        var acc = 0L
        chunkStartOffsets = playable.map { chunk ->
            val start = acc
            acc += chunk.durationMillis.coerceAtLeast(1L)
            start
        }
        totalDurationMs = playable.sumOf { it.durationMillis.coerceAtLeast(0L) }.coerceAtLeast(1L)

        val mediaItems = playable.map { chunk ->
            MediaItem.fromUri(Uri.fromFile(sessionRepository.getChunkFile(session, chunk)))
        }
        exoPlayer.stop()
        exoPlayer.setMediaItems(mediaItems)
        exoPlayer.prepare()

        val appliedSpeed = (initialSpeed ?: _state.value.playbackSpeed).coerceIn(0.75f, 2.0f)
        exoPlayer.setPlaybackSpeed(appliedSpeed)

        _state.value = _state.value.copy(
            sessionId = session.id,
            sessionTitle = session.title,
            isPlaying = false,
            currentChunkIndex = 0,
            chunkCount = playable.size,
            globalPositionMs = 0L,
            totalDurationMs = totalDurationMs,
            playbackSpeed = appliedSpeed,
            loopStartMs = null,
            loopEndMs = null
        )

        mediaSession.isActive = playable.isNotEmpty()
        updateMediaSessionMetadata()
        updateMediaSessionState()

        if (autoPlay && playable.isNotEmpty()) {
            play()
        }
    }

    fun play() {
        if (loadedChunks.isEmpty()) return
        if (exoPlayer.playbackState == Player.STATE_ENDED) {
            seekToGlobal(0L)
        }
        val startIntent = Intent(applicationContext, VoxPlaybackService::class.java).apply {
            action = ACTION_START_FOREGROUND
        }
        runCatching { ContextCompat.startForegroundService(applicationContext, startIntent) }
        promoteToForegroundIfNeeded()
        exoPlayer.play()
        syncStateFromPlayer(forceNotification = true)
    }

    fun pause() {
        exoPlayer.pause()
        syncStateFromPlayer(forceNotification = true)
    }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) pause() else play()
    }

    fun seekToGlobal(targetMs: Long) {
        if (loadedChunks.isEmpty()) return
        val clamped = targetMs.coerceIn(0L, totalDurationMs)
        var targetIndex = 0
        var offsetInChunk = 0L
        for (i in loadedChunks.indices) {
            val start = chunkStartOffsets[i]
            val end = start + loadedChunks[i].durationMillis.coerceAtLeast(1L)
            if (clamped <= end || i == loadedChunks.lastIndex) {
                targetIndex = i
                offsetInChunk = (clamped - start).coerceAtLeast(0L)
                break
            }
        }
        exoPlayer.seekTo(targetIndex, offsetInChunk)
        _state.value = _state.value.copy(
            currentChunkIndex = targetIndex,
            globalPositionMs = clamped
        )
        updateMediaSessionState()
    }

    fun seekToChunk(chunkIndex: Int, autoPlay: Boolean = true) {
        if (loadedChunks.isEmpty()) return
        val safeIdx = chunkIndex.coerceIn(0, loadedChunks.lastIndex)
        exoPlayer.seekTo(safeIdx, 0L)
        val start = chunkStartOffsets.getOrElse(safeIdx) { 0L }
        _state.value = _state.value.copy(
            currentChunkIndex = safeIdx,
            globalPositionMs = start
        )
        if (autoPlay) play() else updateMediaSessionState()
    }

    fun skipBy(deltaMs: Long) {
        seekToGlobal(_state.value.globalPositionMs + deltaMs)
    }

    fun setPlaybackSpeed(speed: Float) {
        val safe = speed.coerceIn(0.75f, 2.0f)
        exoPlayer.setPlaybackSpeed(safe)
        _state.value = _state.value.copy(playbackSpeed = safe)
        updateMediaSessionState()
    }

    fun setBoostDb(db: Int) {
        val safe = db.coerceIn(0, 18)
        _state.value = _state.value.copy(boostDb = safe)
        applyLoudnessGain(safe)
    }

    /**
     * Cycles A–B loop repeat:
     * 1st tap -> marks Point A at current playback position.
     * 2nd tap -> marks Point B (swaps if before A) and starts looping A..B.
     * 3rd tap -> clears the A–B loop.
     */
    fun toggleAbLoop() {
        if (!state.value.hasLoadedSession || totalDurationMs <= 500L) return
        val current = _state.value
        val pos = current.globalPositionMs.coerceIn(0L, totalDurationMs)
        when {
            current.loopStartMs == null -> {
                _state.value = current.copy(loopStartMs = pos, loopEndMs = null)
            }
            current.loopEndMs == null -> {
                val a = current.loopStartMs
                val start = minOf(a, pos)
                val end = maxOf(a, pos).coerceAtLeast((start + 500L).coerceAtMost(totalDurationMs))
                if (end > start) {
                    _state.value = current.copy(loopStartMs = start, loopEndMs = end)
                    seekToGlobal(start)
                    if (!exoPlayer.isPlaying) play()
                } else {
                    _state.value = current.copy(loopStartMs = null, loopEndMs = null)
                }
            }
            else -> {
                clearAbLoop()
            }
        }
    }

    fun clearAbLoop() {
        _state.value = _state.value.copy(loopStartMs = null, loopEndMs = null)
    }

    fun stopAndDismiss() {
        positionJob?.cancel()
        positionJob = null
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        loadedChunks = emptyList()
        chunkStartOffsets = emptyList()
        totalDurationMs = 1L
        mediaSession.isActive = false
        _state.value = PlaybackServiceState(
            playbackSpeed = _state.value.playbackSpeed,
            boostDb = _state.value.boostDb
        )
        if (isForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        notificationManager.cancel(NOTIFICATION_ID)
        stopSelf()
    }

    private fun attachLoudnessEnhancer(audioSessionId: Int) {
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET || audioSessionId == 0) return
        runCatching { loudnessEnhancer?.release() }
        loudnessEnhancer = runCatching {
            LoudnessEnhancer(audioSessionId).apply {
                val db = _state.value.boostDb
                if (db > 0) {
                    setTargetGain(db * 100) // millibels
                    enabled = true
                } else {
                    setTargetGain(0)
                    enabled = false
                }
            }
        }.onFailure {
            Log.w(TAG, "LoudnessEnhancer unavailable on this device", it)
        }.getOrNull()
    }

    private fun applyLoudnessGain(db: Int) {
        if (loudnessEnhancer == null && exoPlayer.audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
            attachLoudnessEnhancer(exoPlayer.audioSessionId)
        }
        runCatching {
            loudnessEnhancer?.let { enhancer ->
                if (db > 0) {
                    enhancer.setTargetGain(db * 100) // 1 dB = 100 mB
                    enhancer.enabled = true
                } else {
                    enhancer.setTargetGain(0)
                    enhancer.enabled = false
                }
            }
        }
    }

    private fun startPositionTicker() {
        positionJob?.cancel()
        positionJob = serviceScope.launch {
            while (exoPlayer.isPlaying) {
                if (loadedChunks.isNotEmpty() && _state.value.hasLoadedSession) {
                    syncStateFromPlayer(forceNotification = false)
                }
                delay(200L)
            }
        }
    }

    private fun syncStateFromPlayer(forceNotification: Boolean) {
        if (loadedChunks.isEmpty()) return
        val idx = exoPlayer.currentMediaItemIndex.coerceIn(0, loadedChunks.lastIndex)
        val base = chunkStartOffsets.getOrElse(idx) { 0L }
        val posInChunk = exoPlayer.currentPosition.coerceAtLeast(0L)
        val globalPos = (base + posInChunk).coerceIn(0L, totalDurationMs)
        val playing = exoPlayer.isPlaying

        val prev = _state.value
        val loopA = prev.loopStartMs
        val loopB = prev.loopEndMs
        if (playing && loopA != null && loopB != null && loopB > loopA && globalPos >= loopB) {
            seekToGlobal(loopA)
            return
        }

        _state.value = prev.copy(
            isPlaying = playing,
            currentChunkIndex = idx,
            globalPositionMs = globalPos,
            totalDurationMs = totalDurationMs
        )
        if (forceNotification || prev.isPlaying != playing || prev.currentChunkIndex != idx) {
            updateMediaSessionState()
            if (isForeground) {
                updateNotification()
            }
        }
    }

    private fun promoteToForegroundIfNeeded() {
        val notification = buildPlaybackNotification()
        if (!isForeground) {
            try {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    } else {
                        0
                    }
                )
                isForeground = true
            } catch (e: Exception) {
                Log.w(TAG, "Unable to promote playback foreground service", e)
            }
        } else {
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        if (!isForeground) return
        notificationManager.notify(NOTIFICATION_ID, buildPlaybackNotification())
    }

    private fun updateMediaSessionMetadata() {
        val current = _state.value
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, current.sessionTitle.ifBlank { getString(R.string.app_name) })
            .putString(MediaMetadata.METADATA_KEY_ARTIST, getString(R.string.app_name))
            .putLong(MediaMetadata.METADATA_KEY_DURATION, current.totalDurationMs)
            .build()
        mediaSession.setMetadata(metadata)
    }

    private fun updateMediaSessionState() {
        val current = _state.value
        val actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_REWIND or
            PlaybackState.ACTION_FAST_FORWARD or
            PlaybackState.ACTION_STOP

        val pbState = when {
            !current.hasLoadedSession -> PlaybackState.STATE_STOPPED
            current.isPlaying -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }

        val stateObj = PlaybackState.Builder()
            .setActions(actions)
            .setState(pbState, current.globalPositionMs, if (current.isPlaying) current.playbackSpeed else 0f)
            .build()
        mediaSession.setPlaybackState(stateObj)
    }

    private fun buildPlaybackNotification(): Notification {
        val current = _state.value
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_SESSION_ID, current.sessionId)
        }
        val contentPending = PendingIntent.getActivity(
            this,
            20,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun serviceAction(reqCode: Int, action: String): PendingIntent {
            val intent = Intent(this, VoxPlaybackService::class.java).apply { this.action = action }
            return PendingIntent.getService(
                this,
                reqCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val playPauseIcon = if (current.isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        val playPauseLabel = if (current.isPlaying) {
            getString(R.string.action_pause)
        } else {
            getString(R.string.cd_play)
        }

        val subtitle = buildString {
            append(if (current.isPlaying) "Playing" else "Paused")
            if (current.playbackSpeed != 1.0f) append(" • ${current.playbackSpeed}×")
            if (current.boostDb > 0) append(" • +${current.boostDb} dB")
        }

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(current.sessionTitle.ifBlank { getString(R.string.app_name) })
            .setContentText(subtitle)
            .setContentIntent(contentPending)
            .setOngoing(current.isPlaying)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_rew,
                    "-10s",
                    serviceAction(21, ACTION_REWIND_10)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    playPauseIcon,
                    playPauseLabel,
                    serviceAction(22, ACTION_TOGGLE_PLAY)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_ff,
                    "+10s",
                    serviceAction(23, ACTION_FORWARD_10)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Close",
                    serviceAction(24, ACTION_CLOSE)
                ).build()
            )
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )

        return builder.build()
    }

    private fun createPlaybackChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_playback_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_playback_desc)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        notificationManager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        positionJob?.cancel()
        runCatching { loudnessEnhancer?.release() }
        loudnessEnhancer = null
        mediaSession.isActive = false
        mediaSession.release()
        exoPlayer.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VoxPlaybackService"
        private const val CHANNEL_ID = "ntkvox_playback_channel"
        private const val NOTIFICATION_ID = 1002

        const val EXTRA_OPEN_SESSION_ID = "com.example.ntkvox.EXTRA_OPEN_SESSION_ID"
        const val ACTION_START_FOREGROUND = "com.example.ntkvox.PLAYBACK_START"
        const val ACTION_TOGGLE_PLAY = "com.example.ntkvox.PLAYBACK_TOGGLE"
        const val ACTION_REWIND_10 = "com.example.ntkvox.PLAYBACK_REWIND"
        const val ACTION_FORWARD_10 = "com.example.ntkvox.PLAYBACK_FORWARD"
        const val ACTION_CLOSE = "com.example.ntkvox.PLAYBACK_CLOSE"
    }
}
