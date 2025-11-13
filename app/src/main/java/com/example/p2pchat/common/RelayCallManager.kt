package com.example.p2pchat.common

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.*
import kotlin.math.max

class RelayCallManager(
    private val context: Context,
    private val onSendAudio: (audioData: ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "RelayCallManager"
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SIZE_MS = 20
        private const val FRAME_SIZE_BYTES = (SAMPLE_RATE * FRAME_SIZE_MS / 1000) * 2 // 16kHz, 20ms, 16-bit PCM
    }

    // Audio Hardware
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var sendJob: Job? = null
    private var isCalling = false

    // Config
    private val channelConfigIn = AudioFormat.CHANNEL_IN_MONO
    private val channelConfigOut = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private fun initializeAudioRecorder(): Boolean {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted!")
            return false
        }
        try {
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfigIn, audioFormat)
            val bufferSize = max(minBufferSize, FRAME_SIZE_BYTES * 5)

            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, channelConfigIn, audioFormat, bufferSize
            )
            if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("AudioRecord failed to initialize")
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init recorder: ${e.message}")
            return false
        }
    }

    private fun initializeAudioPlayer() {
        try {
            val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, channelConfigOut, audioFormat)
            val bufferSize = max(minBufferSize, FRAME_SIZE_BYTES * 5)

            player = AudioTrack.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(channelConfigOut)
                        .setEncoding(audioFormat)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (player?.state != AudioTrack.STATE_INITIALIZED) {
                throw IllegalStateException("AudioTrack failed to initialize")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init player: ${e.message}")
        }
    }

    fun startCall(scope: CoroutineScope) {
        if (isCalling) return
        if (!initializeAudioRecorder()) {
            Log.e(TAG, "Cannot start, recorder failed to init")
            return
        }
        initializeAudioPlayer()

        isCalling = true

        try {
            recorder?.startRecording()
            player?.play()

            sendJob = scope.launch(Dispatchers.IO) {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                val audioData = ByteArray(FRAME_SIZE_BYTES)
                while (isActive && isCalling) {
                    val readBytes = recorder!!.read(audioData, 0, FRAME_SIZE_BYTES)
                    if (readBytes == FRAME_SIZE_BYTES) {
                        // Send the audio data to the ViewModel
                        onSendAudio(audioData.clone())
                    } else {
                        Log.w(TAG, "AudioRecord read error: $readBytes")
                    }
                }
            }
            Log.d(TAG, "Relay call started")
        } catch (e: Exception) {
            Log.e(TAG, "startCall failed: ${e.message}")
            stopCall()
        }
    }

    /**
     * Called by the ViewModel when it receives an AudioData payload.
     */
    fun onAudioReceived(data: ByteArray) {
        if (player?.playState == AudioTrack.PLAYSTATE_PLAYING) {
            player?.write(data, 0, data.size)
        }
    }

    fun stopCall() {
        if (!isCalling) return
        isCalling = false
        sendJob?.cancel()
        sendJob = null

        try {
            recorder?.stop()
            recorder?.release()
        } catch (e: Exception) { Log.e(TAG, "Recorder stop error", e) }

        try {
            player?.stop()
            player?.release()
        } catch (e: Exception) { Log.e(TAG, "Player stop error", e) }

        recorder = null
        player = null
        Log.d(TAG, "Relay call stopped")
    }
}