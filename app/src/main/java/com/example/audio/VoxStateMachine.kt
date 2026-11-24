package com.example.audio

import com.example.model.VoiceSensitivity

enum class VoxState {
    IDLE,
    LISTENING,
    SPEECH_CONFIRMING,
    RECORDING,
    HANGOVER_SILENCE
}

sealed interface VoxAction {
    /** Do nothing on disk; frame remains only in pre-roll ring buffer. */
    data object KeepListening : VoxAction

    /** Speech confirmed: open a new chunk, flush pre-roll buffer, and stream audio. */
    data object OpenChunkAndFlushPreRoll : VoxAction

    /** Continue streaming the current live frame into the open chunk. */
    data object WriteLiveFrame : VoxAction

    /** Max continuous chunk length reached (15 min): rotate seamlessly to next chunk. */
    data object RotateChunkAndWriteLiveFrame : VoxAction

    /** Silence hangover elapsed: flush, fsync, finalize chunk, and return to LISTENING. */
    data object FinalizeChunk : VoxAction
}

/**
 * Deterministic 4-state hysteresis VOX state machine:
 * 1. LISTENING: Fill 2.0s pre-roll ring buffer, write nothing to disk.
 * 2. SPEECH_CONFIRMING: Require ~96 ms of speech frames above start threshold.
 * 3. RECORDING: Flush pre-roll into active chunk and stream live frames; auto-rotate at 15 min.
 * 4. HANGOVER_SILENCE: Keep recording for [silenceDelayMs] (default 10s); resume seamlessly if
 *    speech returns before timeout, or finalize & fsync chunk when countdown finishes.
 */
class VoxStateMachine(
    private val confirmDurationMs: Long = 96L,
    private val maxChunkDurationMs: Long = 15L * 60L * 1000L,
    silenceDelaySeconds: Int = 10,
    sensitivity: VoiceSensitivity = VoiceSensitivity.QUIET_VOICES
) {
    var state: VoxState = VoxState.IDLE
        private set

    private var silenceDelayMs: Long = silenceDelaySeconds.coerceAtLeast(1) * 1000L
    private var startThreshold: Float = sensitivity.speechStartThreshold
    private var stopThreshold: Float = sensitivity.speechStopThreshold

    private var speechConfirmElapsedMs: Long = 0L
    private var hangoverElapsedMs: Long = 0L
    private var currentChunkDurationMs: Long = 0L

    val remainingHangoverMs: Long
        get() = if (state == VoxState.HANGOVER_SILENCE) {
            (silenceDelayMs - hangoverElapsedMs).coerceAtLeast(0L)
        } else {
            0L
        }

    fun startListening() {
        state = VoxState.LISTENING
        speechConfirmElapsedMs = 0L
        hangoverElapsedMs = 0L
        currentChunkDurationMs = 0L
    }

    fun stop(): VoxAction {
        val needsFinalize = state == VoxState.RECORDING || state == VoxState.HANGOVER_SILENCE
        state = VoxState.IDLE
        speechConfirmElapsedMs = 0L
        hangoverElapsedMs = 0L
        currentChunkDurationMs = 0L
        return if (needsFinalize) VoxAction.FinalizeChunk else VoxAction.KeepListening
    }

    fun updateConfig(silenceDelaySeconds: Int, sensitivity: VoiceSensitivity) {
        silenceDelayMs = silenceDelaySeconds.coerceAtLeast(1) * 1000L
        startThreshold = sensitivity.speechStartThreshold
        stopThreshold = sensitivity.speechStopThreshold
    }

    /**
     * Advances the state machine by one audio frame of [frameDurationMs] (typically 32 ms).
     */
    fun onFrame(speechProbability: Float, frameDurationMs: Long = 32L): VoxAction {
        val aboveStart = speechProbability >= startThreshold
        val belowStop = speechProbability <= stopThreshold

        return when (state) {
            VoxState.IDLE -> VoxAction.KeepListening

            VoxState.LISTENING -> {
                if (aboveStart) {
                    state = VoxState.SPEECH_CONFIRMING
                    speechConfirmElapsedMs = frameDurationMs
                    if (speechConfirmElapsedMs >= confirmDurationMs) {
                        state = VoxState.RECORDING
                        currentChunkDurationMs = frameDurationMs
                        hangoverElapsedMs = 0L
                        VoxAction.OpenChunkAndFlushPreRoll
                    } else {
                        VoxAction.KeepListening
                    }
                } else {
                    speechConfirmElapsedMs = 0L
                    VoxAction.KeepListening
                }
            }

            VoxState.SPEECH_CONFIRMING -> {
                if (aboveStart) {
                    speechConfirmElapsedMs += frameDurationMs
                    if (speechConfirmElapsedMs >= confirmDurationMs) {
                        state = VoxState.RECORDING
                        currentChunkDurationMs = frameDurationMs
                        hangoverElapsedMs = 0L
                        VoxAction.OpenChunkAndFlushPreRoll
                    } else {
                        VoxAction.KeepListening
                    }
                } else {
                    state = VoxState.LISTENING
                    speechConfirmElapsedMs = 0L
                    VoxAction.KeepListening
                }
            }

            VoxState.RECORDING -> {
                currentChunkDurationMs += frameDurationMs
                if (belowStop) {
                    state = VoxState.HANGOVER_SILENCE
                    hangoverElapsedMs = frameDurationMs
                }
                if (currentChunkDurationMs >= maxChunkDurationMs) {
                    currentChunkDurationMs = frameDurationMs
                    VoxAction.RotateChunkAndWriteLiveFrame
                } else {
                    VoxAction.WriteLiveFrame
                }
            }

            VoxState.HANGOVER_SILENCE -> {
                currentChunkDurationMs += frameDurationMs
                if (aboveStart) {
                    // Speech returned before timeout: seamlessly stay in RECORDING
                    state = VoxState.RECORDING
                    hangoverElapsedMs = 0L
                    if (currentChunkDurationMs >= maxChunkDurationMs) {
                        currentChunkDurationMs = frameDurationMs
                        VoxAction.RotateChunkAndWriteLiveFrame
                    } else {
                        VoxAction.WriteLiveFrame
                    }
                } else {
                    hangoverElapsedMs += frameDurationMs
                    if (hangoverElapsedMs >= silenceDelayMs) {
                        state = VoxState.LISTENING
                        speechConfirmElapsedMs = 0L
                        hangoverElapsedMs = 0L
                        currentChunkDurationMs = 0L
                        VoxAction.FinalizeChunk
                    } else if (currentChunkDurationMs >= maxChunkDurationMs) {
                        currentChunkDurationMs = frameDurationMs
                        VoxAction.RotateChunkAndWriteLiveFrame
                    } else {
                        VoxAction.WriteLiveFrame
                    }
                }
            }
        }
    }
}
