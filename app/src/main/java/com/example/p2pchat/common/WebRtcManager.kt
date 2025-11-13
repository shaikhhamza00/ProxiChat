package com.example.p2pchat.common

import android.content.Context
import android.util.Log
import org.webrtc.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.webrtc.audio.JavaAudioDeviceModule


interface WebRtcSignalingListener {
    fun onSendOffer(sdp: String)
    fun onSendAnswer(sdp: String)
    fun onSendIceCandidate(candidate: IceCandidate)
    fun onCallHangup()
    fun onP2PConnectionFailed()
}

class WebRtcManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val listener: WebRtcSignalingListener
) {
    private val eglBase = EglBase.create()
    private val peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null

    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    companion object {
        private const val AUDIO_TRACK_ID = "audio0"
        private const val TAG = "WebRtcManager"
    }

    private val simpleSdpObserver = object : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetSuccess() {
            Log.d(TAG, "SDP set successfully")
        }
        override fun onSetFailure(error: String?) {
            Log.e(TAG, "SDP set failed: $error")
        }
    }

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        val audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()

        audioDeviceModule.release()
        createLocalAudioStream()
    }

    private fun createLocalAudioStream() {
        val constraints = MediaConstraints()
        audioSource = peerConnectionFactory.createAudioSource(constraints)
        localAudioTrack = peerConnectionFactory.createAudioTrack(AUDIO_TRACK_ID, audioSource)
        localAudioTrack?.setEnabled(true)
    }

    private val peerConnectionObserver = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate?.let {
                Log.d(TAG, "onIceCandidate: $it")
                scope.launch(Dispatchers.Main) {
                    listener.onSendIceCandidate(it)
                }
            }
        }

        override fun onAddStream(stream: MediaStream?) {
            Log.d(TAG, "onAddStream: Remote audio stream received")
        }

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
            Log.d(TAG, "onIceConnectionChange: $newState")

            // --- 2. THIS IS THE KEY UPDATE ---
            if (newState == PeerConnection.IceConnectionState.FAILED ||
                newState == PeerConnection.IceConnectionState.DISCONNECTED
            ) {
                // The P2P connection failed. Tell the ViewModel to handle it.
                scope.launch(Dispatchers.Main) {
                    listener.onP2PConnectionFailed()
                }
            }
        }

        // --- Other required overrides ---
        override fun onDataChannel(p0: DataChannel?) {}
        override fun onIceConnectionReceivingChange(p0: Boolean) {}
        override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {
            Log.d(TAG, "onIceGatheringChange: $p0")
        }
        override fun onSignalingChange(p0: PeerConnection.SignalingState?) {
            Log.d(TAG, "onSignalingChange: $p0")
        }
        override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
        override fun onRemoveStream(p0: MediaStream?) {
            Log.d(TAG, "onRemoveStream")
        }
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
    }

    private fun createPeerConnection(): PeerConnection? {
        // --- THIS REMAINS THE KEY FOR OFFLINE ---
        val iceServers = emptyList<PeerConnection.IceServer>()
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        return peerConnectionFactory.createPeerConnection(
            rtcConfig,
            peerConnectionObserver
        )
    }

    // --- Public Call-Flow Methods ---

    fun createOffer() {
        scope.launch(Dispatchers.IO) {
            peerConnection = createPeerConnection()
            peerConnection?.addTrack(localAudioTrack, listOf(AUDIO_TRACK_ID))

            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            }

            peerConnection?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    sdp?.let {
                        Log.d(TAG, "createOffer success")
                        peerConnection?.setLocalDescription(simpleSdpObserver, it)
                        scope.launch(Dispatchers.Main) {
                            listener.onSendOffer(it.description)
                        }
                    }
                }
                override fun onCreateFailure(error: String?) {
                    Log.e(TAG, "createOffer failed: $error")
                }
                override fun onSetSuccess() {}
                override fun onSetFailure(p0: String?) {}
            }, constraints)
        }
    }

    fun onOfferReceived(sdp: String) {
        scope.launch(Dispatchers.IO) {
            peerConnection = createPeerConnection()
            peerConnection?.addTrack(localAudioTrack, listOf(AUDIO_TRACK_ID))

            Log.d(TAG, "onOfferReceived: setting remote description")
            peerConnection?.setRemoteDescription(
                simpleSdpObserver,
                SessionDescription(SessionDescription.Type.OFFER, sdp)
            )
        }
    }

    fun createAnswer() {
        scope.launch(Dispatchers.IO) {
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            }

            peerConnection?.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    sdp?.let {
                        Log.d(TAG, "createAnswer success")
                        peerConnection?.setLocalDescription(simpleSdpObserver, it)
                        scope.launch(Dispatchers.Main) {
                            listener.onSendAnswer(it.description)
                        }
                    }
                }
                override fun onCreateFailure(error: String?) {
                    Log.e(TAG, "createAnswer failed: $error")
                }
                override fun onSetSuccess() {}
                override fun onSetFailure(p0: String?) {}
            }, constraints)
        }
    }

    fun onAnswerReceived(sdp: String) {
        scope.launch(Dispatchers.IO) {
            Log.d(TAG, "onAnswerReceived: setting remote description")
            peerConnection?.setRemoteDescription(
                simpleSdpObserver,
                SessionDescription(SessionDescription.Type.ANSWER, sdp)
            )
        }
    }

    fun onIceCandidateReceived(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        scope.launch(Dispatchers.IO) {
            val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
            Log.d(TAG, "onIceCandidateReceived: adding candidate")
            peerConnection?.addIceCandidate(iceCandidate)
        }
    }

    fun close() {
        Log.d(TAG, "close: releasing all WebRTC resources")
        peerConnection?.close()
        peerConnection = null
        audioSource?.dispose()
        peerConnectionFactory.dispose()
        eglBase.release()
    }
}