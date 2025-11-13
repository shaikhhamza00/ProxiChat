package com.example.p2pchat.presentation.chat

import android.app.Application
import android.content.Context
import kotlinx.coroutines.Dispatchers.Main as DispatchMain
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.p2pchat.common.ChatSummary
import com.example.p2pchat.common.MessagePayload
import com.example.p2pchat.common.NetworkMessage
import com.example.p2pchat.common.PrefsManager
import com.example.p2pchat.common.RelayCallManager
import com.example.p2pchat.common.UiMessage
import com.example.p2pchat.common.WebRtcManager
import com.example.p2pchat.common.WebRtcSignalingListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.webrtc.IceCandidate
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

class ChatViewModel(application: Application) : AndroidViewModel(application),
    WebRtcSignalingListener {

    // --- State Flows ---
    private val _messages = MutableStateFlow<Map<String, List<UiMessage>>>(emptyMap())
    val messages = _messages.asStateFlow()

    private val _chatSummaries = MutableStateFlow<List<ChatSummary>>(emptyList())
    val chatSummaries = _chatSummaries.asStateFlow()

    val _status = MutableStateFlow("Idle")
    val status = _status.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _connectedUsers = MutableStateFlow<Set<String>>(emptySet())
    val connectedUsers = _connectedUsers.asStateFlow()

    private val _currentlyViewedChatId = MutableStateFlow<String?>(null)

    // --- Call State ---
    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState = _callState.asStateFlow()

    // --- User Identifier ---
    private var myIdentifier: String = PrefsManager.getPhoneNumber(application) ?: "Unknown"
    private var myIpAddress: String? = null // Still useful for handshake/discovery

    // --- Network Components ---
    private val nsdManager = application.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var serverSocket: ServerSocket? = null
    private val clientSockets = ConcurrentHashMap<String, Socket>()
    private var clientSocket: Socket? = null
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null
    private var nsdDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var nsdResolveListener: NsdManager.ResolveListener? = null

    // --- NEW: WebRTC Manager ---
    private var webRtcManager: WebRtcManager? = null
    private var relayCallManager: RelayCallManager? = null

    // --- NEW: Call Duration Logic (moved from VoiceCallManager) ---
    private var callStartTime = 0L
    private var durationJob: Job? = null
    private val _callDuration = MutableStateFlow(0L)
    val callDuration: StateFlow<Long> = _callDuration.asStateFlow()


    // --- Constants ---
    private val SERVICE_NAME = "LocalChatApp"
    private val SERVICE_TYPE = "_mychat._tcp."
    private val MAX_FILE_SIZE = 15 * 1024 * 1024
    private val FILE_SUBDIR = "chat_files"

    init {
        File(application.filesDir, FILE_SUBDIR).mkdirs()
        if (myIdentifier == "Unknown") {
            _status.value = "Error: User not signed in."
        }
    }

    // --- Public API ---

    fun startHost() {
        if (myIdentifier == "Unknown") return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                myIpAddress = getLocalIpAddress()
                if (myIpAddress == null) {
                    _status.value = "Error: No Wi-Fi IP."
                    return@launch
                }
                Log.d("ChatViewModel", "Host IP set to: $myIpAddress")

                serverSocket = ServerSocket(0)
                val localPort = serverSocket!!.localPort
                _status.value = "Hosting on port $localPort..."
                registerService(localPort)
                _isConnected.value = true
                _connectedUsers.update { setOf(myIdentifier) }

                while (true) {
                    val client = serverSocket!!.accept()
                    viewModelScope.launch(Dispatchers.IO) {
                        listenToSocket(client)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _status.value = "Host error: ${e.message}"
                cleanUp()
            }
        }
    }

    fun startClient() {
        if (myIdentifier == "Unknown") return
        _status.value = "Searching for host..."
        try {
            discoverServices()
        } catch (e: Exception) {
            _status.value = "Discovery error: ${e.message}"
        }
    }

    // --- Send Functions (Unchanged) ---

    fun sendMessage(text: String, recipientId: String) {
        if (text.isBlank()) return
        val payload = MessagePayload.Text(text)
        val networkMessage = NetworkMessage(
            senderIdentifier = myIdentifier,
            recipientIdentifier = recipientId,
            payload = payload
        )
        addMessageToUi(recipientId, text, null, myIdentifier, null, null)
        viewModelScope.launch(Dispatchers.IO) {
            sendNetworkMessage(networkMessage)
        }
    }

    fun sendImage(context: Context, uri: Uri, recipientId: String) = sendFileWithPayload(context, uri, recipientId) { base64, _ ->
        MessagePayload.Image(base64)
    }
    fun sendVideo(context: Context, uri: Uri, recipientId: String) = sendFileWithPayload(context, uri, recipientId) { base64, fileName ->
        MessagePayload.Video(fileName, base64)
    }
    fun sendFile(context: Context, uri: Uri, recipientId: String) = sendFileWithPayload(context, uri, recipientId) { base64, fileName ->
        MessagePayload.File(fileName, base64)
    }

    private fun sendFileWithPayload(
        context: Context,
        uri: Uri,
        recipientId: String,
        payloadCreator: (base64: String, fileName: String) -> MessagePayload
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (fileName, fileSize) = uriToMetadata(context, uri)
                if (fileSize > MAX_FILE_SIZE) {
                    _status.value = "Error: File > 15MB"
                    addMessageToUi(recipientId, "Error: File '$fileName' is too large (> 15MB).", null, "System", null, null)
                    return@launch
                }
                _status.value = "Preparing file..."

                val (bitmap, base64) = if (context.contentResolver.getType(uri)?.startsWith("image") == true) {
                    uriToScaledBase64(context, uri)
                } else {
                    val fileBytes = uriToByteArray(context, uri)
                    null to Base64.encodeToString(fileBytes, Base64.DEFAULT)
                }

                val payload = payloadCreator(base64, fileName)
                val networkMessage = NetworkMessage(
                    senderIdentifier = myIdentifier,
                    recipientIdentifier = recipientId,
                    payload = payload
                )

                if (payload is MessagePayload.Image) {
                    addMessageToUi(recipientId, null, bitmap, myIdentifier, null, null)
                } else {
                    addMessageToUi(recipientId, "Sent file: $fileName", null, myIdentifier, null, null)
                }

                _status.value = "Sending file..."
                sendNetworkMessage(networkMessage)
                _status.value = "Connected"
            } catch (e: Exception) {
                addMessageToUi(recipientId, "Error sending file", null, "System", null, null)
            }
        }
    }

    // --- NEW: WebRTC Signaling Listener Implementation ---

    override fun onSendOffer(sdp: String) {
        val currentState = _callState.value
        if (currentState is CallState.Outgoing) {
            val payload = MessagePayload.WrtcOffer(sdp)
            val networkMessage = NetworkMessage(myIdentifier, currentState.recipientId, payload)
            viewModelScope.launch(Dispatchers.IO) { sendNetworkMessage(networkMessage) }
        }
    }

    override fun onSendAnswer(sdp: String) {
        val currentState = _callState.value
        if (currentState is CallState.Active && !currentState.isRelay) {
            val payload = MessagePayload.WrtcAnswer(sdp)
            val networkMessage = NetworkMessage(myIdentifier, currentState.recipientId, payload)
            viewModelScope.launch(Dispatchers.IO) { sendNetworkMessage(networkMessage) }
        }
    }

    override fun onSendIceCandidate(candidate: IceCandidate) {
        val currentState = _callState.value
        val recipientId = when (currentState) {
            is CallState.Active -> currentState.recipientId
            is CallState.Outgoing -> currentState.recipientId
            is CallState.Incoming -> currentState.callerId
            else -> null
        }
        recipientId?.let {
            val payload = MessagePayload.WrtcIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
            val networkMessage = NetworkMessage(myIdentifier, it, payload)
            viewModelScope.launch(Dispatchers.IO) { sendNetworkMessage(networkMessage) }
        }
    }

    override fun onP2PConnectionFailed() {
        Log.w("ChatViewModel", "WebRTC P2P connection failed!")

        val currentState = _callState.value
        if (currentState is CallState.Active && currentState.isRelay) {
            // We are ALREADY in relay mode, and it still failed. Something is wrong. Hang up.
            Log.e("ChatViewModel", "Relay mode connection also failed. Hanging up.")
            hangUp()
            return
        }

        val (callId, recipientId) = when (currentState) {
            is CallState.Active -> currentState.callId to currentState.recipientId
            is CallState.Outgoing -> currentState.callId to currentState.recipientId
            else -> return // Not in a state where we can fallback.
        }

        Log.i("ChatViewModel", "Attempting to switch to Relay Mode for call $callId with $recipientId")

        // 1. Send a message to the other user telling them to switch.
        val payload = MessagePayload.SwitchToRelay(callId)
        val networkMessage = NetworkMessage(myIdentifier, recipientId, payload)
        viewModelScope.launch(Dispatchers.IO) {
            sendNetworkMessage(networkMessage)
        }

        // 2. Perform the switch locally.
        switchToRelayMode(callId, recipientId)
    }

    override fun onCallHangup() {
        // This is called by WebRTCManager if the connection drops
        if (_callState.value is CallState.Active || _callState.value is CallState.Outgoing) {
            Log.d("ChatViewModel", "WebRTC connection failed/disconnected, hanging up.")
            hangUp()
        }
    }

    // --- NEW: Call Timer Functions ---

    private fun startCallTimer() {
        if (durationJob != null) return // Already running
        callStartTime = System.currentTimeMillis()
        durationJob = viewModelScope.launch {
            while (true) {
                val elapsed = (System.currentTimeMillis() - callStartTime) / 1000
                _callDuration.value = elapsed
                delay(1000)
            }
        }
    }

    private fun stopCallTimer() {
        durationJob?.cancel()
        durationJob = null
        callStartTime = 0L
        _callDuration.value = 0L
    }

    // --- NEW: WebRTC Manager Initializer ---

    private fun initWebRtcManager() {
        webRtcManager = WebRtcManager(getApplication(), viewModelScope, this)
    }

    private fun cleanUpCallManagers() {
        webRtcManager?.close()
        webRtcManager = null

        relayCallManager?.stopCall()
        relayCallManager = null
    }

    private fun switchToRelayMode(callId: String, recipientId: String) {
        // Ensure this logic only runs once
        if (relayCallManager != null) return

        Log.i("ChatViewModel", "Switching to Relay Mode...")

        // 1. Stop and clean up WebRTC
        webRtcManager?.close()
        webRtcManager = null

        // 2. Initialize and start the RelayCallManager
        relayCallManager = RelayCallManager(
            getApplication(),
            onSendAudio = { audioData ->
                // This lambda is called by RelayCallManager to send audio
                sendRelayAudio(audioData, recipientId)
            }
        )
        relayCallManager?.startCall(viewModelScope)

        // 3. Update the call state
        _callState.value = CallState.Active(callId, recipientId, isRelay = true)

        // 4. Ensure the timer is running
        if (durationJob == null) {
            startCallTimer()
        }
    }

    private fun sendRelayAudio(data: ByteArray, recipientId: String) {
        if (_callState.value !is CallState.Active) return // Don't send if call ended

        val payload = MessagePayload.AudioData(data)
        val networkMessage = NetworkMessage(myIdentifier, recipientId, payload)

        // Launch in a separate job to avoid blocking the audio thread
        viewModelScope.launch(Dispatchers.IO) {
            sendNetworkMessage(networkMessage)
        }
    }

    // --- UPDATED: Call Signaling Functions ---

    fun sendCallRequest(recipientId: String) {
        if (_callState.value !is CallState.Idle) {
            _status.value = "Already in a call"
            return
        }

        // 1. Clean up any old managers
        cleanUpCallManagers()

        // 2. Start WebRTC by default
        initWebRtcManager()

        val callId = UUID.randomUUID().toString()
        _callState.value = CallState.Outgoing(callId, recipientId)
        Log.d("ChatViewModel", "Initiating call (WebRTC) to $recipientId")

        // 3. Create the WebRTC offer
        webRtcManager?.createOffer()
    }

    fun acceptCall() {
        val currentState = _callState.value
        if (currentState !is CallState.Incoming) {
            Log.w("ChatViewModel", "acceptCall called but not in Incoming state")
            return
        }

        Log.d("ChatViewModel", "Accepting call from ${currentState.callerId}")

        // 1. Update state to Active (WebRTC P2P by default)
        _callState.value = CallState.Active(
            currentState.callId,
            currentState.callerId,
            isRelay = false // <-- Start in P2P mode
        )

        // 2. Start the call timer
        startCallTimer()

        // 3. Create the WebRTC answer
        // (webRtcManager was already initialized in handleUiPayload)
        webRtcManager?.createAnswer()
    }

    fun rejectCall() {
        val currentState = _callState.value
        if (currentState !is CallState.Incoming) return

        Log.d("ChatViewModel", "Rejecting call from ${currentState.callerId}")

        cleanUpCallManagers() // Clean up WebRTC
        _callState.value = CallState.Idle

        // Send rejection signal
        val payload = MessagePayload.CallReject(currentState.callId)
        val networkMessage = NetworkMessage(myIdentifier, currentState.callerId, payload)
        viewModelScope.launch(Dispatchers.IO) {
            sendNetworkMessage(networkMessage)
        }
    }

    fun hangUp() {
        val currentState = _callState.value
        val (callId, recipientId) = when (currentState) {
            is CallState.Active -> currentState.callId to currentState.recipientId
            is CallState.Outgoing -> currentState.callId to currentState.recipientId
            is CallState.Incoming -> currentState.callId to currentState.callerId
            else -> return
        }

        Log.d("ChatViewModel", "Hanging up call with $recipientId")

        // 1. Stop all call-related things
        cleanUpCallManagers()
        stopCallTimer()
        _callState.value = CallState.Idle

        // 2. Send hangup signal
        val payload = MessagePayload.CallHangup(callId)
        val networkMessage = NetworkMessage(myIdentifier, recipientId, payload)
        viewModelScope.launch(Dispatchers.IO) {
            sendNetworkMessage(networkMessage)
        }
    }
    // --- Private Network Logic (Unchanged) ---

    private fun registerService(port: Int) {
        nsdRegistrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.d("ChatViewModel", "NSD Service Registered: $serviceInfo")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                if (errorCode == 4) {
                    _status.value = "Error: A host already exists."
                    viewModelScope.launch(Dispatchers.Main) {
                        cleanUp()
                    }
                } else {
                    _status.value = "Host failed (Code $errorCode)"
                }
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, nsdRegistrationListener)
    }

    private fun discoverServices() {
        nsdDiscoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdManager.stopServiceDiscovery(this)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdManager.stopServiceDiscovery(this)
            }
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType == SERVICE_TYPE) {
                    resolveService(serviceInfo)
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
        }
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, nsdDiscoveryListener)
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        nsdResolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                _status.value = "Failed to connect"
            }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                nsdManager.stopServiceDiscovery(nsdDiscoveryListener)
                viewModelScope.launch(Dispatchers.IO) {
                    connectToHost(serviceInfo.host, serviceInfo.port)
                }
            }
        }
        nsdManager.resolveService(serviceInfo, nsdResolveListener)
    }

    private suspend fun connectToHost(hostAddress: InetAddress, port: Int) {
        try {
            _status.value = "Connecting to $hostAddress..."
            clientSocket = Socket(hostAddress, port)

            myIpAddress = clientSocket!!.localAddress.hostAddress
            Log.d("ChatViewModel", "Client IP set to: $myIpAddress")

            _status.value = "Connected!"
            _isConnected.value = true

            sendHandshake()

            // Start Heartbeat Ping
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    while (isConnected.value && clientSocket != null && !clientSocket!!.isClosed) {
                        delay(15_000)
                        val pingMsg = NetworkMessage(myIdentifier, "System", MessagePayload.Ping())
                        sendNetworkMessage(pingMsg)
                    }
                } catch (e: Exception) {
                    Log.d("ChatViewModel", "Ping loop stopped: ${e.message}")
                }
            }

            listenToSocket(clientSocket!!)
        } catch (e: Exception) {
            _status.value = "Connection failed: ${e.message}"
            cleanUp()
        }
    }

    private fun sendHandshake() {
        val handshake = NetworkMessage(
            senderIdentifier = myIdentifier,
            recipientIdentifier = "System",
            payload = MessagePayload.Handshake()
        )
        viewModelScope.launch(Dispatchers.IO) {
            sendNetworkMessage(handshake)
        }
    }

    private fun broadcastUserList() {
        val userList = _connectedUsers.value.toList()
        val payload = MessagePayload.UserListUpdate(userList)
        val message = NetworkMessage(
            senderIdentifier = "System",
            recipientIdentifier = null,
            payload = payload
        )
        viewModelScope.launch(Dispatchers.IO) {
            sendNetworkMessage(message)
        }
    }

    private suspend fun listenToSocket(socket: Socket) {
        var clientIdentifier: String? = null

        try {
            val reader = BufferedReader(InputStreamReader(socket.inputStream))
            while (true) {
                val jsonMessage = reader.readLine() ?: break
                val networkMessage = Json.decodeFromString<NetworkMessage>(jsonMessage)
                val payload = networkMessage.payload

                if (payload is MessagePayload.Ping) continue

                // --- HOST LOGIC ---
                if (serverSocket != null) {
                    if (payload is MessagePayload.Handshake) {
                        clientIdentifier = networkMessage.senderIdentifier
                        clientSockets[clientIdentifier] = socket
                        _connectedUsers.update { it + clientIdentifier }
                        addMessageToUi(clientIdentifier, "'$clientIdentifier' joined.", null, "System", null, null)
                        broadcastUserList()
                    } else {
                        val recipientId = networkMessage.recipientIdentifier
                        if (recipientId != null && recipientId != myIdentifier) {
                            // --- THIS IS THE RELAY ---
                            // It finds the recipient's socket and forwards the message.
                            // This works for text, images, AND our new AudioData.
                            clientSockets[recipientId]?.let { recipientSocket ->
                                sendMessageToSocket(recipientSocket, jsonMessage)
                            }
                        } else {
                            // Message is for me (the Host)
                            handleUiPayload(networkMessage.senderIdentifier, networkMessage)
                        }
                    }
                }
                // --- CLIENT LOGIC ---
                else {
                    when (payload) {
                        is MessagePayload.UserListUpdate -> {
                            _connectedUsers.value = payload.users.toSet()
                        }
                        is MessagePayload.Handshake -> { /* Client ignores handshakes */ }
                        else -> {
                            // All other messages are for me (the Client)
                            handleUiPayload(networkMessage.senderIdentifier, networkMessage)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Error listening: ${e.message}", e)
        } finally {
            // ... (Your existing finally block for cleanup)
        }
    }

    /**
     * UPDATED to handle new Relay payloads.
     */
    private suspend fun handleUiPayload(conversationId: String, networkMessage: NetworkMessage) {
        val context = getApplication<Application>().applicationContext
        val senderId = networkMessage.senderIdentifier
        if (senderId == myIdentifier) return

        when (val payload = networkMessage.payload) {
            // ... (Text, Image, Video, File handlers - Unchanged)

            // --- UPDATED: Call Signaling Handlers ---
            is MessagePayload.WrtcOffer -> {
                if (_callState.value is CallState.Idle) {
                    val callId = UUID.randomUUID().toString()
                    cleanUpCallManagers()
                    initWebRtcManager()
                    webRtcManager?.onOfferReceived(payload.sdp)
                    _callState.value = CallState.Incoming(callId, senderId)
                    Log.d("ChatViewModel", "Incoming WebRTC call from $senderId")
                } else {
                    val rejectPayload = MessagePayload.CallReject(UUID.randomUUID().toString())
                    sendNetworkMessage(NetworkMessage(myIdentifier, senderId, rejectPayload))
                }
            }
            is MessagePayload.WrtcAnswer -> {
                val currentState = _callState.value
                if (currentState is CallState.Outgoing && currentState.recipientId == senderId) {
                    _callState.value = CallState.Active(currentState.callId, senderId, isRelay = false)
                    Log.d("ChatViewModel", "Call accepted (WebRTC) by $senderId, starting timer.")
                    startCallTimer()
                    webRtcManager?.onAnswerReceived(payload.sdp)
                }
            }
            is MessagePayload.WrtcIceCandidate -> {
                webRtcManager?.onIceCandidateReceived(payload.sdpMid, payload.sdpMLineIndex, payload.candidate)
            }

            // --- NEW: Relay Handlers ---
            is MessagePayload.SwitchToRelay -> {
                Log.i("ChatViewModel", "Received SwitchToRelay from $senderId")
                val currentState = _callState.value
                if (currentState is CallState.Active && !currentState.isRelay) {
                    // The other person's P2P failed. Switch our side.
                    switchToRelayMode(payload.callId, senderId)
                }
            }
            is MessagePayload.AudioData -> {
                // This is a relay audio packet. Give it to the player.
                relayCallManager?.onAudioReceived(payload.data)
            }

            // --- Call Control Handlers (UPDATED) ---
            is MessagePayload.CallReject -> {
                val currentState = _callState.value
                if (currentState is CallState.Outgoing && currentState.recipientId == senderId) {
                    cleanUpCallManagers()
                    stopCallTimer()
                    _callState.value = CallState.Idle
                    _status.value = "$senderId rejected the call."
                }
            }
            is MessagePayload.CallHangup -> {
                val currentState = _callState.value
                val isCallActive = (currentState is CallState.Active && currentState.recipientId == senderId)
                val isCallIncoming = (currentState is CallState.Incoming && currentState.callerId == senderId)
                val isCallOutgoing = (currentState is CallState.Outgoing && currentState.recipientId == senderId)

                if (isCallActive || isCallIncoming || isCallOutgoing) {
                    cleanUpCallManagers()
                    stopCallTimer()
                    _callState.value = CallState.Idle
                    _status.value = "Call ended."
                }
            }
            else -> { /* Ignore */ }
        }
    }

    private suspend fun sendNetworkMessage(message: NetworkMessage) {
        val jsonMessage = Json.encodeToString(message)
        withContext(Dispatchers.IO) {
            if (serverSocket != null) {
                val recipientId = message.recipientIdentifier
                if (recipientId == null) {
                    clientSockets.values.forEach { client -> sendMessageToSocket(client, jsonMessage) }
                } else if (recipientId == myIdentifier) {
                    // Host sent to self
                } else {
                    clientSockets[recipientId]?.let { recipientSocket ->
                        sendMessageToSocket(recipientSocket, jsonMessage)
                    }
                }
            } else if (clientSocket != null) {
                sendMessageToSocket(clientSocket!!, jsonMessage)
            }
        }
    }

    private fun sendMessageToSocket(socket: Socket, jsonMessage: String) {
        try {
            val writer = PrintWriter(socket.outputStream, true)
            writer.println(jsonMessage)
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Error sending: ${e.message}", e)
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address &&
                        (intf.name.contains("wlan") || intf.name.contains("eth"))) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Can't get local IP", e)
        }
        return null
    }

    // --- Utility Functions (Unchanged) ---

    private fun createMessagePreview(message: UiMessage): String {
        return when {
            message.text != null -> message.text
            message.bitmap != null -> "📷 Image"
            message.fileName?.endsWith("mp4", true) == true -> "📹 Video"
            message.fileName != null -> "📄 ${message.fileName}"
            else -> "..."
        }
    }

    private fun updateChatSummary(conversationId: String, lastMessage: UiMessage) {
        _chatSummaries.update { currentSummaries ->
            val existingSummary = currentSummaries.find { it.recipientId == conversationId }
            val currentCount = existingSummary?.unreadCount ?: 0
            val isViewingThisChat = (_currentlyViewedChatId.value == conversationId)

            val newUnreadCount = if (!lastMessage.isFromMe && !isViewingThisChat) {
                currentCount + 1
            } else {
                existingSummary?.unreadCount ?: 0
            }

            val summary = ChatSummary(
                recipientId = conversationId,
                lastMessage = createMessagePreview(lastMessage),
                timestamp = lastMessage.timestamp,
                isFromMe = lastMessage.isFromMe,
                unreadCount = newUnreadCount
            )

            val otherSummaries = currentSummaries.filterNot { it.recipientId == conversationId }
            listOf(summary) + otherSummaries
        }
    }

    private fun addMessageToUi(
        conversationId: String,
        text: String? = null,
        bitmap: Bitmap? = null,
        senderIdentifier: String,
        fileName: String? = null,
        localFileUri: Uri? = null
    ) {
        val isFromMe = (senderIdentifier == myIdentifier)
        val displayName = if (isFromMe) "Me" else senderIdentifier

        val uiMessage = UiMessage(
            text = text,
            bitmap = bitmap,
            isFromMe = isFromMe,
            senderIdentifier = displayName,
            fileName = fileName,
            localFileUri = localFileUri
        )

        _messages.update { currentMap ->
            val currentHistory = currentMap[conversationId] ?: emptyList()
            currentMap + (conversationId to (currentHistory + uiMessage))
        }

        if (senderIdentifier != "System") {
            updateChatSummary(conversationId, uiMessage)
        }
    }

    fun markChatAsRead(conversationId: String) {
        _currentlyViewedChatId.value = conversationId
        _chatSummaries.update { currentSummaries ->
            currentSummaries.map { summary ->
                if (summary.recipientId == conversationId) {
                    summary.copy(unreadCount = 0)
                } else {
                    summary
                }
            }
        }
    }

    fun clearCurrentlyViewedChat() {
        _currentlyViewedChatId.value = null
    }

    // --- File/Bitmap Utilities (Unchanged) ---

    @Throws(Exception::class)
    private fun uriToMetadata(context: Context, uri: Uri): Pair<String, Long> {
        val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
        cursor.use {
            if (it != null && it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex == -1 || sizeIndex == -1) throw Exception("Could not get metadata")
                val name = it.getString(nameIndex)
                val size = it.getLong(sizeIndex)
                return Pair(name, size)
            }
        }
        throw Exception("Could not get metadata")
    }

    @Throws(Exception::class)
    private suspend fun uriToByteArray(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
    }

    @Throws(Exception::class)
    private suspend fun saveByteArrayToFile(context: Context, fileName: String, data: ByteArray): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, FILE_SUBDIR)
        if (!dir.exists()) dir.mkdirs()
        val safeFileName = fileName.replace(File.separator, "_")
        val file = File(dir, safeFileName)
        FileOutputStream(file).use { it.write(data) }
        file
    }

    private suspend fun base64ToByteArray(base64: String): ByteArray = withContext(Dispatchers.IO) {
        Base64.decode(base64, Base64.DEFAULT)
    }

    private suspend fun uriToScaledBase64(context: Context, uri: Uri): Pair<Bitmap, String> = withContext(Dispatchers.IO) {
        val inputStream = context.contentResolver.openInputStream(uri)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(inputStream, null, options)
        inputStream?.close()

        val reqWidth = 800
        val reqHeight = 800
        var inSampleSize = 1
        if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
            val halfHeight: Int = options.outHeight / 2
            val halfWidth: Int = options.outWidth / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }

        val scaledOptions = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
        val scaledInputStream = context.contentResolver.openInputStream(uri)
        val bitmap = BitmapFactory.decodeStream(scaledInputStream, null, scaledOptions)
        scaledInputStream?.close()

        val outputStream = ByteArrayOutputStream()
        bitmap!!.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
        val byteArray = outputStream.toByteArray()
        val base64 = Base64.encodeToString(byteArray, Base64.DEFAULT)
        Pair(bitmap, base64)
    }

    private suspend fun base64ToBitmap(base64: String): Bitmap = withContext(Dispatchers.IO) {
        val decodedBytes = Base64.decode(base64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
    }

    // --- UPDATED: Cleanup ---

    fun cleanUp() {
        _status.value = "Cleaning up..."
        _isConnected.value = false

        // Stop any active call
        cleanUpCallManagers()
        stopCallTimer()
        _callState.value = CallState.Idle

        try {
            nsdRegistrationListener?.let { nsdManager.unregisterService(it) }
            nsdDiscoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
            serverSocket?.close()
            clientSockets.values.forEach { it.close() }
            clientSocket?.close()
            clientSockets.clear()
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Cleanup error: ${e.message}", e)
        }

        _status.value = "Idle"
        _messages.value = emptyMap()
        _chatSummaries.value = emptyList()
        _connectedUsers.value = emptySet()
    }

    override fun onCleared() {
        cleanUp()
        super.onCleared()
    }
}
