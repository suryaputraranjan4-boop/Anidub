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

    fun playFile(filePath: String, label: String, startOffsetMs: Long = 0L) {
        val file = File(filePath)
        if (!file.exists()) {
            Log.w("StudioAudioPlayer", "Audio file does not exist: $filePath")
            return
        }

        // Toggle pause/stop if same file is currently playing
        if (_playbackState.value.isPlaying && _playbackState.value.activeFilePath == filePath && startOffsetMs == 0L) {
            stop()
            return
        }

        stop()
        try {
            val mp = MediaPlayer()
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            val duration = mp.duration.toLong().coerceAtLeast(100L)
            if (startOffsetMs > 0L && startOffsetMs < duration) {
                mp.seekTo(startOffsetMs.toInt())
            }
            mp.setOnCompletionListener {
                stop()
            }
            mp.start()
            mediaPlayer = mp
            _playbackState.value = AudioPlaybackState(
                isPlaying = true,
                activeFilePath = filePath,
                activeLabel = label,
                currentPositionMs = startOffsetMs,
                totalDurationMs = duration
            )

            progressJob = scope.launch(Dispatchers.Main) {
                while (isActive && mediaPlayer != null) {
                    val player = mediaPlayer ?: break
                    if (player.isPlaying) {
                        _playbackState.value = _playbackState.value.copy(
                            currentPositionMs = player.currentPosition.toLong()
                        )
                    }
                    delay(60L)
                }
            }
        } catch (e: Exception) {
            Log.e("StudioAudioPlayer", "Failed to play $filePath", e)
            stop()
        }
    }

    fun seekTo(positionMs: Long) {
        try {
            val mp = mediaPlayer
            if (mp != null) {
                val target = positionMs.coerceIn(0L, _playbackState.value.totalDurationMs)
                mp.seekTo(target.toInt())
                _playbackState.value = _playbackState.value.copy(currentPositionMs = target)
            } else {
                _playbackState.value = _playbackState.value.copy(currentPositionMs = positionMs)
            }
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
            currentPositionMs = 0L
        )
    }
}
