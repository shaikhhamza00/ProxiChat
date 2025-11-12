package com.example.p2pchat.presentation.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import com.example.p2pchat.common.AudioPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.min

class VoiceCallManager(private val context: Context) {

    private var isCalling = false
    private var callJob: Job? = null
    private var durationJob: Job? = null
    private var metricsJob: Job? = null

    private var audioSocket: DatagramSocket? = null
    private var listeningSocket: DatagramSocket? = null

    // Sequence tracking
    private val sendSequenceNumber = AtomicInteger(0)
    private var expectedReceiveSequence = AtomicInteger(0)

    // Enhanced jitter buffer with timestamp tracking
    private val receiveBuffer = PriorityQueue<AudioPacket>(11) { a, b ->
        a.sequenceNumber.compareTo(b.sequenceNumber)
    }
    private val bufferLock = ReentrantLock()
    private val maxJitterBufferSize = 15
    private val minJitterBufferSize = 3 // Wait for some packets before playing
    private val maxSequenceGap = 10
    private var isBufferPrimed = false

    // Track last packet time and statistics
    private var lastPacketTime = AtomicLong(0L)
    private var firstPacketReceived = false
    private var consecutiveTimeouts = AtomicInteger(0)
    private val maxConsecutiveTimeouts = 50 // ~5 seconds at 100ms timeout

    // Call duration tracking
    private var callStartTime = 0L
    private val _callDuration = MutableStateFlow(0L)
    val callDuration: StateFlow<Long> = _callDuration

    // Incoming call state
    private val _incomingCall = MutableStateFlow<InetAddress?>(null)
    val incomingCall: StateFlow<InetAddress?> = _incomingCall

    // Ringtone for incoming calls
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    // Audio Configuration
    private val audioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION
    private val sampleRate = 16000
    private val channelConfigIn = AudioFormat.CHANNEL_IN_MONO
    private val channelConfigOut = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private val audioRecordMinBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfigIn, audioFormat)
    private val bufferSize = max(audioRecordMinBufferSize * 2, 6400) // Ensure minimum size

    private val FRAME_SIZE_BYTES = 640 // 20ms at 16kHz, 16-bit
    private val HEADER_SIZE = 8 // Increased for timestamp
    private val TIMEOUT_CHECK_INTERVAL = 100L // ms

    // Audio Hardware
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null

    // Packet loss concealment - store last good packet
    private var lastGoodPacket: AudioPacket? = null

    // Debug metrics
    private var packetsReceived = AtomicLong(0)
    private var packetsLost = AtomicLong(0)
    private var packetsSent = AtomicLong(0)
    private var concealedPackets = AtomicLong(0)

    // Listener for incoming calls
    private var listeningJob: Job? = null

    companion object {
        const val AUDIO_PORT = 9876
        const val CALL_REQUEST_MAGIC = "CALL_REQUEST"
        const val CALL_ACCEPT_MAGIC = "CALL_ACCEPT"
    }

    // Start listening for incoming calls
    fun startListening(scope: CoroutineScope) {
        if (listeningJob != null && listeningJob?.isActive == true) return

        listeningJob = scope.launch(Dispatchers.IO) {
            try {
                // Use separate socket for listening
                listeningSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(AUDIO_PORT))
                    soTimeout = 500
                }

                Log.d("VoiceCallManager", "Started listening on port $AUDIO_PORT")

                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)

                while (isActive && !isCalling) {
                    try {
                        listeningSocket?.receive(packet)
                        val data = String(packet.data, 0, packet.length)

                        // Check if it's a call request
                        if (data.startsWith(CALL_REQUEST_MAGIC)) {
                            Log.d("VoiceCallManager", "Incoming call from ${packet.address}")
                            if (!isCalling && _incomingCall.value == null) {
                                _incomingCall.value = packet.address
                                startRinging()
                            }
                        }
                    } catch (e: SocketTimeoutException) {
                        continue
                    } catch (e: Exception) {
                        if (isActive && !isCalling) {
                            Log.e("VoiceCallManager", "Listening error: ${e.message}")
                        }
                    }
                }

                listeningSocket?.close()
                listeningSocket = null
                Log.d("VoiceCallManager", "Stopped listening")
            } catch (e: Exception) {
                Log.e("VoiceCallManager", "Failed to start listening: ${e.message}", e)
            }
        }
    }

    fun stopListening() {
        listeningJob?.cancel()
        listeningJob = null
        listeningSocket?.close()
        listeningSocket = null
        stopRinging()
    }

    private fun startRinging() {
        try {
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(context, ringtoneUri)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ringtone?.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                ringtone?.streamType = AudioManager.STREAM_VOICE_CALL
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone?.isLooping = true
            }
            ringtone?.play()

            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            val pattern = longArrayOf(0, 1000, 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }

            Log.d("VoiceCallManager", "Started ringing")
        } catch (e: Exception) {
            Log.e("VoiceCallManager", "Failed to start ringing: ${e.message}")
        }
    }

    private fun stopRinging() {
        try {
            ringtone?.stop()
            ringtone = null
            vibrator?.cancel()
            vibrator = null
            Log.d("VoiceCallManager", "Stopped ringing")
        } catch (e: Exception) {
            Log.e("VoiceCallManager", "Error stopping ringing: ${e.message}")
        }
    }

    fun answerCall(scope: CoroutineScope) {
        val caller = _incomingCall.value ?: return
        stopRinging()
        _incomingCall.value = null
        startCall(scope, caller, isAnswering = true)
    }

    fun rejectCall() {
        stopRinging()
        _incomingCall.value = null
    }

    private fun sendCallRequest(targetIp: InetAddress) {
        try {
            val message = CALL_REQUEST_MAGIC.toByteArray()
            val packet = DatagramPacket(message, message.size, targetIp, AUDIO_PORT)
            audioSocket?.send(packet)
            Log.d("VoiceCallManager", "Sent call request to $targetIp")
        } catch (e: Exception) {
            Log.e("VoiceCallManager", "Failed to send call request: ${e.message}")
        }
    }

    private fun sendCallAccept(targetIp: InetAddress) {
        try {
            val message = CALL_ACCEPT_MAGIC.toByteArray()
            val packet = DatagramPacket(message, message.size, targetIp, AUDIO_PORT)
            audioSocket?.send(packet)
            Log.d("VoiceCallManager", "Sent call accept to $targetIp")
        } catch (e: Exception) {
            Log.e("VoiceCallManager", "Failed to send call accept: ${e.message}")
        }
    }

    fun startCall(scope: CoroutineScope, targetIp: InetAddress, isAnswering: Boolean = false) {
        if (isCalling) {
            Log.w("VoiceCallManager", "Already in a call")
            return
        }

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("VoiceCallManager", "RECORD_AUDIO permission not granted!")
            return
        }

        // Stop listening
        stopListening()

        // Reset state
        isCalling = true
        sendSequenceNumber.set(0)
        expectedReceiveSequence.set(0)
        bufferLock.withLock { receiveBuffer.clear() }
        isBufferPrimed = false
        firstPacketReceived = false
        callStartTime = System.currentTimeMillis()
        lastPacketTime.set(0L)
        consecutiveTimeouts.set(0)
        packetsReceived.set(0)
        packetsLost.set(0)
        packetsSent.set(0)
        concealedPackets.set(0)
        lastGoodPacket = null

        Log.d("VoiceCallManager", "Starting call to $targetIp (answering=$isAnswering)")

        // Start call duration tracker
        durationJob = scope.launch {
            while (isActive && isCalling) {
                val elapsed = (System.currentTimeMillis() - callStartTime) / 1000
                _callDuration.value = elapsed
                delay(1000)
            }
        }

        // Debug metrics logger
        metricsJob = scope.launch(Dispatchers.IO) {
            while (isActive && isCalling) {
                delay(5000)
                val bufferSize = bufferLock.withLock { receiveBuffer.size }
                Log.d("VoiceCallManager",
                    "Metrics: Sent=${packetsSent.get()}, Received=${packetsReceived.get()}, " +
                            "Lost=${packetsLost.get()}, Concealed=${concealedPackets.get()}, Buffer=$bufferSize")
            }
        }

        callJob = scope.launch(Dispatchers.IO) {
            try {
                // Initialize socket
                audioSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(AUDIO_PORT))
                    soTimeout = 100
                    receiveBufferSize = 1024 * 64
                    sendBufferSize = 1024 * 64
                    trafficClass = 0x10 // IPTOS_LOWDELAY for VoIP
                }
                Log.d("VoiceCallManager", "Socket bound to port $AUDIO_PORT")

                // Send appropriate signal
                if (isAnswering) {
                    sendCallAccept(targetIp)
                } else {
                    sendCallRequest(targetIp)
                }

                // Small delay to let signaling complete
                delay(100)

                // Initialize audio with validation
                initializeAudioRecorder()
                initializeAudioPlayer()

                // Start recording and playback
                recorder?.startRecording()
                if (recorder?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw IllegalStateException("Failed to start recording")
                }
                Log.d("VoiceCallManager", "Recording started")

                player?.play()
                if (player?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    throw IllegalStateException("Failed to start playback")
                }
                Log.d("VoiceCallManager", "Playback started")

                // Launch audio threads with proper priorities
                val sendJob = launch(Dispatchers.IO) {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                    try {
                        sendAudio(targetIp)
                    } catch (e: Exception) {
                        Log.e("VoiceCallManager", "Send thread error: ${e.message}", e)
                    }
                }

                val receiveJob = launch(Dispatchers.IO) {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                    try {
                        receiveAudio()
                    } catch (e: Exception) {
                        Log.e("VoiceCallManager", "Receive thread error: ${e.message}", e)
                    }
                }

                val playbackJob = launch(Dispatchers.IO) {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                    try {
                        playbackLoop()
                    } catch (e: Exception) {
                        Log.e("VoiceCallManager", "Playback thread error: ${e.message}", e)
                    }
                }

                // Timeout watchdog
                val watchdogJob = launch(Dispatchers.IO) {
                    while (isActive && isCalling) {
                        delay(TIMEOUT_CHECK_INTERVAL)

                        if (firstPacketReceived) {
                            val timeSinceLastPacket = System.currentTimeMillis() - lastPacketTime.get()
                            if (timeSinceLastPacket > 10000) { // 10 seconds silence
                                Log.e("VoiceCallManager", "No packets received for 10s, call appears dead")
                                this@launch.cancel()
                                stopCall()
                            }
                        }
                    }
                }

                joinAll(sendJob, receiveJob, playbackJob, watchdogJob)

            } catch (e: Exception) {
                if (isActive) {
                    Log.e("VoiceCallManager", "Call failed: ${e.message}", e)
                }
            } finally {
                Log.d("VoiceCallManager", "Call job ending, cleaning up")
                stopCallCleanup()
            }
        }
    }

    private fun initializeAudioRecorder() {
        try {
            recorder = AudioRecord(
                audioSource,
                sampleRate,
                channelConfigIn,
                audioFormat,
                bufferSize
            )

            if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
                recorder?.release()
                throw IllegalStateException("AudioRecord failed to initialize")
            }

            // Enable audio effects
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                try {
                    if (AcousticEchoCanceler.isAvailable()) {
                        val aec = AcousticEchoCanceler.create(recorder!!.audioSessionId)
                        aec?.enabled = true
                        Log.d("VoiceCallManager", "AEC enabled: ${aec?.enabled}")
                    }
                    if (NoiseSuppressor.isAvailable()) {
                        val ns = NoiseSuppressor.create(recorder!!.audioSessionId)
                        ns?.enabled = true
                        Log.d("VoiceCallManager", "NS enabled: ${ns?.enabled}")
                    }
                } catch (e: Exception) {
                    Log.w("VoiceCallManager", "Failed to enable audio effects: ${e.message}")
                }
            }

            Log.d("VoiceCallManager", "AudioRecord initialized: buffer=$bufferSize")
        } catch (e: SecurityException) {
            Log.e("VoiceCallManager", "Security exception: RECORD_AUDIO permission not granted")
            throw e
        }
    }

    private fun initializeAudioPlayer() {
        val playerBufferSize = bufferSize * 2

        player = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfigOut)
                        .setEncoding(audioFormat)
                        .build()
                )
                .setBufferSizeInBytes(playerBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                android.media.AudioManager.STREAM_VOICE_CALL,
                sampleRate,
                channelConfigOut,
                audioFormat,
                playerBufferSize,
                AudioTrack.MODE_STREAM
            )
        }

        if (player?.state != AudioTrack.STATE_INITIALIZED) {
            player?.release()
            throw IllegalStateException("AudioTrack failed to initialize")
        }

        Log.d("VoiceCallManager", "AudioTrack initialized: buffer=$playerBufferSize")
    }

    private suspend fun sendAudio(targetIp: InetAddress) {
        val audioData = ByteArray(FRAME_SIZE_BYTES)
        val packetData = ByteArray(FRAME_SIZE_BYTES + HEADER_SIZE)
        val packetBuffer = ByteBuffer.wrap(packetData)

        Log.d("VoiceCallManager", "Send thread started")

        while (isCalling && recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            val readBytes = recorder!!.read(audioData, 0, FRAME_SIZE_BYTES)

            if (readBytes == FRAME_SIZE_BYTES) {
                try {
                    packetBuffer.clear()
                    val sequence = sendSequenceNumber.getAndIncrement()
                    val timestamp = System.currentTimeMillis()

                    packetBuffer.putInt(sequence)
                    packetBuffer.putInt((timestamp and 0xFFFFFFFF).toInt())
                    packetBuffer.put(audioData, 0, readBytes)

                    val packet = DatagramPacket(
                        packetData,
                        packetData.size,
                        targetIp,
                        AUDIO_PORT
                    )
                    audioSocket?.send(packet)
                    packetsSent.incrementAndGet()

                    if (sequence < 3) {
                        Log.d("VoiceCallManager", "Sent packet #$sequence")
                    }

                } catch (e: Exception) {
                    if (isCalling) {
                        Log.e("VoiceCallManager", "Send error: ${e.message}")
                    }
                }
            } else if (readBytes < 0) {
                Log.e("VoiceCallManager", "AudioRecord read error: $readBytes")
                break
            }

            yield() // Allow other coroutines to run
        }
        Log.d("VoiceCallManager", "Send thread stopped")
    }

    private suspend fun receiveAudio() {
        val packetData = ByteArray(FRAME_SIZE_BYTES + HEADER_SIZE + 100) // Extra space for safety
        val packet = DatagramPacket(packetData, packetData.size)

        Log.d("VoiceCallManager", "Receive thread started")

        while (isCalling && audioSocket != null && !audioSocket!!.isClosed) {
            try {
                audioSocket!!.receive(packet)

                packetsReceived.incrementAndGet()
                lastPacketTime.set(System.currentTimeMillis())
                consecutiveTimeouts.set(0)
                firstPacketReceived = true

                if (packetsReceived.get() <= 3) {
                    Log.d("VoiceCallManager", "Received packet #${packetsReceived.get()}, size=${packet.length}")
                }

                if (packet.length == FRAME_SIZE_BYTES + HEADER_SIZE) {
                    val buffer = ByteBuffer.wrap(packet.data, 0, packet.length)
                    val sequence = buffer.int
                    val timestamp = buffer.int
                    val audioLength = packet.length - HEADER_SIZE

                    val audioData = ByteArray(audioLength)
                    buffer.get(audioData, 0, audioLength)

                    val audioPacket = AudioPacket(sequence, audioData, audioLength, timestamp.toLong())
                    processAudioPacket(audioPacket)

                } else {
                    // Ignore non-audio packets (signaling, etc.)
                    val data = String(packet.data, 0, min(packet.length, 20))
                    if (!data.startsWith(CALL_REQUEST_MAGIC) && !data.startsWith(CALL_ACCEPT_MAGIC)) {
                        Log.w("VoiceCallManager", "Unexpected packet size: ${packet.length}")
                    }
                }

            } catch (e: SocketTimeoutException) {
                consecutiveTimeouts.incrementAndGet()
                continue
            } catch (e: Exception) {
                if (isCalling) {
                    Log.e("VoiceCallManager", "Receive error: ${e.message}")
                }
            }

            yield()
        }
        Log.d("VoiceCallManager", "Receive thread stopped")
    }

    private fun processAudioPacket(packet: AudioPacket) {
        bufferLock.withLock {
            val expected = expectedReceiveSequence.get()
            val gap = packet.sequenceNumber - expected

            when {
                packet.sequenceNumber < expected -> {
                    // Old packet, discard
                    return
                }

                packet.sequenceNumber == expected -> {
                    // In-order packet, add to buffer
                    receiveBuffer.offer(packet)
                    expectedReceiveSequence.incrementAndGet()
                    lastGoodPacket = packet

                    // Check for buffered packets that are now in order
                    while (receiveBuffer.isNotEmpty() &&
                        receiveBuffer.peek()?.sequenceNumber == expectedReceiveSequence.get()) {
                        expectedReceiveSequence.incrementAndGet()
                    }
                }

                gap in 1..maxSequenceGap -> {
                    // Small gap, buffer the packet
                    if (receiveBuffer.size < maxJitterBufferSize) {
                        receiveBuffer.offer(packet)
                    } else {
                        // Buffer full, skip ahead
                        Log.w("VoiceCallManager", "Buffer full, skipping to ${packet.sequenceNumber}")
                        packetsLost.addAndGet(gap.toLong())
                        receiveBuffer.clear()
                        receiveBuffer.offer(packet)
                        expectedReceiveSequence.set(packet.sequenceNumber + 1)
                        isBufferPrimed = false
                    }
                }

                else -> {
                    // Large gap, likely packet loss burst
                    Log.w("VoiceCallManager", "Large gap ($gap), resynchronizing")
                    packetsLost.addAndGet(gap.toLong())
                    receiveBuffer.clear()
                    receiveBuffer.offer(packet)
                    expectedReceiveSequence.set(packet.sequenceNumber + 1)
                    isBufferPrimed = false
                }
            }
        }
    }

    private suspend fun playbackLoop() {
        Log.d("VoiceCallManager", "Playback thread started")
        val silencePacket = ByteArray(FRAME_SIZE_BYTES) // Zero-filled for silence

        while (isCalling && player?.playState == AudioTrack.PLAYSTATE_PLAYING) {
            var packetToPlay: AudioPacket? = null

            bufferLock.withLock {
                if (!isBufferPrimed && receiveBuffer.size >= minJitterBufferSize) {
                    isBufferPrimed = true
                    Log.d("VoiceCallManager", "Buffer primed with ${receiveBuffer.size} packets")
                }

                if (isBufferPrimed && receiveBuffer.isNotEmpty()) {
                    packetToPlay = receiveBuffer.poll()
                }
            }

            if (packetToPlay != null) {
                playAudioPacket(packetToPlay!!)
                lastGoodPacket = packetToPlay
            } else if (isBufferPrimed) {
                // Packet loss concealment
                if (lastGoodPacket != null) {
                    // Repeat last good packet with attenuation
                    val attenuatedPacket = attenuatePacket(lastGoodPacket!!)
                    playAudioPacket(attenuatedPacket)
                    concealedPackets.incrementAndGet()
                } else {
                    // Play silence
                    player?.write(silencePacket, 0, silencePacket.size, AudioTrack.WRITE_BLOCKING)
                }
            }

            // Adaptive delay based on buffer state
            val currentBufferSize = bufferLock.withLock { receiveBuffer.size }
            val delayMs = when {
                currentBufferSize > maxJitterBufferSize * 0.8 -> 15L // Buffer too full, play faster
                currentBufferSize < minJitterBufferSize -> 25L // Buffer low, play slower
                else -> 20L // Normal 20ms frame
            }
            delay(delayMs)
        }
        Log.d("VoiceCallManager", "Playback thread stopped")
    }

    private fun attenuatePacket(packet: AudioPacket): AudioPacket {
        val attenuated = ByteArray(packet.length)
        for (i in 0 until packet.length step 2) {
            val sample = ((packet.data[i].toInt() and 0xFF) or
                    (packet.data[i + 1].toInt() shl 8)).toShort()
            val attenuatedSample = (sample * 0.5).toInt().toShort()
            attenuated[i] = (attenuatedSample.toInt() and 0xFF).toByte()
            attenuated[i + 1] = (attenuatedSample.toInt() shr 8).toByte()
        }
        return AudioPacket(packet.sequenceNumber, attenuated, packet.length, packet.timestamp)
    }

    private fun playAudioPacket(packet: AudioPacket) {
        try {
            val written = player?.write(packet.data, 0, packet.length, AudioTrack.WRITE_BLOCKING)
            if (written != null && written < 0) {
                Log.e("VoiceCallManager", "AudioTrack write error: $written")
            }
        } catch (e: Exception) {
            Log.e("VoiceCallManager", "Error playing packet: ${e.message}")
        }
    }

    fun stopCall() {
        if (!isCalling) return

        Log.d("VoiceCallManager", "Stop call requested")
        isCalling = false

        durationJob?.cancel()
        durationJob = null

        metricsJob?.cancel()
        metricsJob = null

        _callDuration.value = 0L

        callJob?.cancel()
        callJob = null
    }

    private fun stopCallCleanup() {
        try {
            recorder?.let {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
                it.release()
            }
            recorder = null

            player?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.stop()
                }
                it.release()
            }
            player = null

            audioSocket?.close()
            audioSocket = null

            bufferLock.withLock { receiveBuffer.clear() }
            lastGoodPacket = null

            Log.d("VoiceCallManager", "Cleanup complete")
        } catch (e: Exception) {
            Log.e("VoiceCallManager", "Cleanup error: ${e.message}")
        }
    }
}



