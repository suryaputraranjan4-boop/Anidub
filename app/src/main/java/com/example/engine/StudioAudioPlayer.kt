package com.example.engine

import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class AudioPlaybackState(
    val isPlaying: Boolean = false,
    val isPaused: Boolean = false,
    val activeFilePath: String = "",
    val activeLabel: String = "",
    val currentPositionMs: Long = 0L,
    val totalDurationMs: Long = 0L
)

class StudioAudioPlayer(private val scope: CoroutineScope) {
    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null

    private val _playbackState = MutableStateFlow(AudioPlaybackState())
    val playbackState: StateFlow<AudioPlaybackState> = _playbackState.asStateFlow()

    fun togglePlayPause(filePath: String, label: String, startOffsetMs: Long = 0L) {
        val state = _playbackState.value
        if (state.activeFilePath == filePath && mediaPlayer != null) {
            if (state.isPlaying) {
                pause()
            } else if (state.isPaused) {
                resume()
            } else {
                playFile(filePath, label, startOffsetMs)
            }
            return
        }
        playFile(filePath, label, startOffsetMs)
    }

    fun playFile(filePath: String, label: String, startOffsetMs: Long = 0L) {
        val file = File(filePath)
        if (filePath.isBlank() || !file.exists() || file.length() == 0L) {
            Log.w("StudioAudioPlayer", "Audio file missing or empty: $filePath")
            return
        }

        // Toggle pause/resume if same file is active and no explicit jump requested
        val state = _playbackState.value
        if (state.activeFilePath == filePath && mediaPlayer != null && startOffsetMs == 0L) {
            if (state.isPlaying) {
                pause()
                return
            } else if (state.isPaused) {
                resume()
                return
            }
        }

        stop()
        try {
            val mp = MediaPlayer()
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            val duration = mp.duration.toLong().coerceAtLeast(100L)
            val clampedOffset = startOffsetMs.coerceIn(0L, (duration - 50L).coerceAtLeast(0L))
            if (clampedOffset > 0L) {
                mp.seekTo(clampedOffset.toInt())
            }
            mp.setOnCompletionListener {
                stop()
            }
            mp.start()
            mediaPlayer = mp
            _playbackState.value = AudioPlaybackState(
                isPlaying = true,
                isPaused = false,
                activeFilePath = filePath,
                activeLabel = label,
                currentPositionMs = clampedOffset,
                totalDurationMs = duration
            )

            startProgressTicker()
        } catch (e: Exception) {
            Log.e("StudioAudioPlayer", "Failed to play $filePath", e)
            stop()
        }
    }

    fun pause() {
        try {
            val mp = mediaPlayer ?: return
            if (mp.isPlaying) {
                mp.pause()
                _playbackState.value = _playbackState.value.copy(
                    isPlaying = false,
                    isPaused = true,
                    currentPositionMs = mp.currentPosition.toLong()
                )
            }
        } catch (e: Exception) {
            Log.e("StudioAudioPlayer", "Pause failed", e)
        }
    }

    fun resume() {
        try {
            val mp = mediaPlayer ?: return
            if (!mp.isPlaying) {
                mp.start()
                _playbackState.value = _playbackState.value.copy(
                    isPlaying = true,
                    isPaused = false
                )
                startProgressTicker()
            }
        } catch (e: Exception) {
            Log.e("StudioAudioPlayer", "Resume failed", e)
        }
    }

    fun seekTo(positionMs: Long) {
        try {
            val mp = mediaPlayer
            val maxDur = _playbackState.value.totalDurationMs.coerceAtLeast(positionMs)
            val target = positionMs.coerceIn(0L, maxDur)
            if (mp != null) {
                mp.seekTo(target.toInt())
            }
            _playbackState.value = _playbackState.value.copy(currentPositionMs = target)
        } catch (e: Exception) {
            Log.e("StudioAudioPlayer", "Seek failed", e)
        }
    }

    fun stop() {
        progressJob?.cancel()
        progressJob = null
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
        _playbackState.value = _playbackState.value.copy(
            isPlaying = false,
            isPaused = false,
            currentPositionMs = 0L
        )
    }

    private fun startProgressTicker() {
        progressJob?.cancel()
        progressJob = scope.launch(Dispatchers.Main) {
            while (isActive && mediaPlayer != null) {
                val player = mediaPlayer ?: break
                try {
                    if (player.isPlaying) {
                        _playbackState.value = _playbackState.value.copy(
                            currentPositionMs = player.currentPosition.toLong()
                        )
                    }
                } catch (_: Exception) {
                    break
                }
                delay(50L)
            }
        }
    }
}
