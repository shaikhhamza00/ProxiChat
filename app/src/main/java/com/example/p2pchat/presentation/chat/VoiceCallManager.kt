package com.example.p2pchat.presentation.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class VoiceCallManager(private val context: Context) {

    private var isCalling = false
    private var callJob: Job? = null
    private var audioSocket: DatagramSocket? = null

    // --- Audio Configuration ---
    private val audioSource = MediaRecorder.AudioSource.MIC
    private val sampleRate = 44100 // Standard sample rate
    private val channelConfigIn = AudioFormat.CHANNEL_IN_MONO
    private val channelConfigOut = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfigIn, audioFormat)

    // --- Audio Hardware ---
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null

    // --- The Port for all UDP audio traffic ---
    companion object {
        const val AUDIO_PORT = 9876 // All call audio will happen on this port
    }

    /**
     * Starts the audio recorder and player and begins listening for/sending audio.
     */
    fun startCall(scope: CoroutineScope, targetIp: InetAddress) {
        if (isCalling) return
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e("VoiceCallManager", "RECORD_AUDIO permission not granted!")
            return
        }

        isCalling = true
        Log.d("VoiceCallManager", "Starting call... Buffer size: $bufferSize bytes")

        callJob = scope.launch(Dispatchers.IO) {
            try {
                audioSocket = DatagramSocket(AUDIO_PORT)
                audioSocket?.reuseAddress = true

                recorder = AudioRecord(audioSource, sampleRate, channelConfigIn, audioFormat, bufferSize)
                player = AudioTrack.Builder()
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfigOut)
                            .setEncoding(audioFormat)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .build()

                recorder?.startRecording()
                player?.play()

                // This launch is a child of callJob.
                // When callJob is cancelled, this will be too.
                scope.launch(Dispatchers.IO) { sendAudio(targetIp) }

                // This runs directly in callJob.
                receiveAudio()

            } catch (e: Exception) {
                // --- MODIFIED: Check isActive from the coroutine scope ---
                if (isActive) { // Check if we are still active before logging error
                    Log.e("VoiceCallManager", "Call failed to start: ${e.message}", e)
                }
            } finally {
                Log.d("VoiceCallManager", "Call job ending, cleaning up.")
                stopCallCleanup()
            }
        }
    }

    /**
     * Loop to read from mic and send UDP packets
     */
    private fun sendAudio(targetIp: InetAddress) {
        val audioData = ByteArray(bufferSize)
        Log.d("VoiceCallManager", "Starting to send audio to $targetIp")
        // --- MODIFIED: Removed 'isActive' ---
        while (isCalling && recorder != null && recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            val readBytes = recorder!!.read(audioData, 0, bufferSize)
            if (readBytes > 0) {
                try {
                    val packet = DatagramPacket(audioData, readBytes, targetIp, AUDIO_PORT)
                    audioSocket?.send(packet)
                } catch (e: Exception) {
                    if (isCalling) Log.e("VoiceCallManager", "Send error: ${e.message}", e)
                }
            }
        }
        Log.d("VoiceCallManager", "Send loop stopped.")
    }

    /**
     * Loop to listen for UDP packets and play them
     */
    private fun receiveAudio() {
        val audioData = ByteArray(bufferSize)
        val packet = DatagramPacket(audioData, audioData.size)
        Log.d("VoiceCallManager", "Starting to receive audio")
        // --- MODIFIED: Removed 'isActive' ---
        while (isCalling && audioSocket != null && !audioSocket!!.isClosed) {
            try {
                audioSocket!!.receive(packet) // This blocks until a packet is received or socket is closed
                if (packet.length > 0) {
                    player?.write(packet.data, 0, packet.length)
                }
            } catch (e: Exception) {
                if (isCalling) Log.e("VoiceCallManager", "Receive error: ${e.message}", e)
            }
        }
        Log.d("VoiceCallManager", "Receive loop stopped.")
    }

    /**
     * Stops the call and cleans up all resources.
     */
    // --- FUNCTION 1: MODIFIED ---
    fun stopCall() {
        if (!isCalling) return
        Log.d("VoiceCallManager", "StopCall requested.")
        isCalling = false // This will signal the loops to stop
        callJob?.cancel() // This will interrupt blocking calls like receive() & trigger finally
        callJob = null

        // --- REMOVED THE REDUNDANT COROUTINE LAUNCH ---
        // The 'finally' block in startCall() is now the only
        // place that calls stopCallCleanup(), which fixes the race condition.
    }

    // --- FUNCTION 2: MODIFIED ---
    private fun stopCallCleanup() {
        // --- ADDED STATE CHECKS TO PREVENT CRASH ---
        // Only stop the recorder if it's actually recording
        if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            recorder?.stop()
        }
        recorder?.release()
        recorder = null

        // Only stop the player if it's actually playing
        if (player?.playState == AudioTrack.PLAYSTATE_PLAYING) {
            player?.stop()
        }
        player?.release()
        player = null

        audioSocket?.close()
        audioSocket = null
        Log.d("VoiceCallManager", "All resources released.")
    }
}