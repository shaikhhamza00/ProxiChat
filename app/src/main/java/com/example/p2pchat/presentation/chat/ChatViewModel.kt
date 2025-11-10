package com.example.p2pchat.presentation.chat

import android.app.Application
import android.content.Context
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
import com.example.p2pchat.common.UiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException


class ChatViewModel(application: Application) : AndroidViewModel(application) {

    // --- State Flows ---

    // NEW: This now holds ALL conversations, mapped by recipient phone number
    private val _messages = MutableStateFlow<Map<String, List<UiMessage>>>(emptyMap())
    val messages = _messages.asStateFlow()

    // NEW: This holds the "Chat Head" list
    private val _chatSummaries = MutableStateFlow<List<ChatSummary>>(emptyList())
    val chatSummaries = _chatSummaries.asStateFlow()

    val _status = MutableStateFlow("Idle")
    val status = _status.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _connectedUsers = MutableStateFlow<Set<String>>(emptySet())
    val connectedUsers = _connectedUsers.asStateFlow()

    // --- User Identifier ---
    private var myIdentifier: String = PrefsManager.getPhoneNumber(application) ?: "Unknown"
    private val _currentlyViewedChatId = MutableStateFlow<String?>(null)

    // --- Network Components ---
    private val nsdManager = application.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var serverSocket: ServerSocket? = null
    private val clientSockets = ConcurrentHashMap<String, Socket>() // Map<PhoneNumber, Socket>
    private var clientSocket: Socket? = null // Client's socket to the host

    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null
    private var nsdDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var nsdResolveListener: NsdManager.ResolveListener? = null

    // --- Constants ---
    private val SERVICE_NAME = "LocalChatApp"
    private val SERVICE_TYPE = "_mychat._tcp."
    private val MAX_FILE_SIZE = 15 * 1024 * 1024 // 15 MB limit
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
                serverSocket = ServerSocket(0)
                val localPort = serverSocket!!.localPort
                _status.value = "Hosting on port $localPort..."
                registerService(localPort)
                _isConnected.value = true
                _connectedUsers.update { setOf(myIdentifier) } // Add self

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

    // --- Send Functions ---

    // MODIFIED: Now requires a recipientId for 1-to-1 chat
    fun sendMessage(text: String, recipientId: String) {
        if (text.isBlank()) return
        val payload = MessagePayload.Text(text)
        val networkMessage = NetworkMessage(
            senderIdentifier = myIdentifier,
            recipientIdentifier = recipientId,
            payload = payload
        )
        // Add to our own UI
        addMessageToUi(recipientId, text, null, myIdentifier, null, null)
        viewModelScope.launch(Dispatchers.IO) {
            sendNetworkMessage(networkMessage)
        }
    }

    fun markChatAsRead(conversationId: String) {
        _currentlyViewedChatId.value = conversationId

        // Update the summary list to set the unread count to 0
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

                // Add to our UI
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

    // --- Private Network Logic ---

    private fun registerService(port: Int) {
        nsdRegistrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { _status.value = "Host failed" }
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
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { nsdManager.stopServiceDiscovery(this) }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { nsdManager.stopServiceDiscovery(this) }
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
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { _status.value = "Failed to connect" }
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
            _status.value = "Connected!"
            _isConnected.value = true

            sendHandshake()
            listenToSocket(clientSocket!!)
        } catch (e: Exception) {
            _status.value = "Connection failed: ${e.message}"
            cleanUp()
        }
    }

    private fun sendHandshake() {
        val handshake = NetworkMessage(
            senderIdentifier = myIdentifier,
            recipientIdentifier = "System", // Special recipient for system messages
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
            recipientIdentifier = null, // null = broadcast
            payload = payload
        )
        viewModelScope.launch(Dispatchers.IO) {
            sendNetworkMessage(message)
        }
    }

    /**
     * Main "brain" for handling all incoming data.
     */
    private suspend fun listenToSocket(socket: Socket) {
        val context = getApplication<Application>().applicationContext
        var clientIdentifier: String? = null // Phone number of this specific socket

        try {
            val reader = BufferedReader(InputStreamReader(socket.inputStream))
            while (true) {
                val jsonMessage = reader.readLine() ?: break
                val networkMessage = Json.decodeFromString<NetworkMessage>(jsonMessage)
                val payload = networkMessage.payload

                // --- HOST LOGIC ---
                if (serverSocket != null) {
                    if (payload is MessagePayload.Handshake) {
                        clientIdentifier = networkMessage.senderIdentifier
                        clientSockets[clientIdentifier] = socket // Add to map
                        _connectedUsers.update { it + clientIdentifier }
                        addMessageToUi(clientIdentifier, "'$clientIdentifier' joined.", null, "System", null, null)
                        broadcastUserList()
                    } else {
                        // This is a data message (text, image, etc.)
                        val recipientId = networkMessage.recipientIdentifier
                        if (recipientId != null && recipientId != myIdentifier) {
                            // 1-to-1 message: Forward to the specific recipient
                            clientSockets[recipientId]?.let { recipientSocket ->
                                sendMessageToSocket(recipientSocket, jsonMessage)
                            }
                        } else {
                            // This message is for the HOST

                            // ======================================================
                            // !!! CRITICAL BUG FIX HERE !!!
                            // ======================================================
                            // The conversationId must be the SENDER, not ourself.
                            // We want to add the message to the chat
                            // with the person who sent it.
                            //
                            // OLD (Buggy): handleUiPayload(myIdentifier, networkMessage)
                            //
                            // NEW (Fixed):
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
                            // This is a data message from the host
                            handleUiPayload(networkMessage.senderIdentifier, networkMessage)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Error listening: ${e.message}", e)
        } finally {
            // --- CLEANUP for this socket ---
            val disconnectedUser = clientIdentifier ?: socket.inetAddress.hostAddress

            if (serverSocket != null && clientIdentifier != null) {
                clientSockets.remove(clientIdentifier)
                _connectedUsers.update { it - clientIdentifier }
                addMessageToUi(clientIdentifier, "'$disconnectedUser' left.", null, "System", null, null)
                broadcastUserList()
            } else if (clientSocket != null) {
                _status.value = "Disconnected from host."
                cleanUp()
            }
            socket.close()
        }
    }

    // NEW: Helper to process a message payload and add it to the UI
    private suspend fun handleUiPayload(conversationId: String, networkMessage: NetworkMessage) {
        val context = getApplication<Application>().applicationContext
        val senderId = networkMessage.senderIdentifier

        // Don't re-add our own messages
        if (senderId == myIdentifier) return

        when (val payload = networkMessage.payload) {
            is MessagePayload.Text -> {
                addMessageToUi(conversationId, payload.content, null, senderId, null, null)
            }
            is MessagePayload.Image -> {
                val bitmap = base64ToBitmap(payload.base64Content)
                addMessageToUi(conversationId, null, bitmap, senderId, null, null)
            }
            is MessagePayload.Video, is MessagePayload.File -> {
                val (fileName, base64) = when (payload) {
                    is MessagePayload.Video -> payload.fileName to payload.base64Content
                    is MessagePayload.File -> payload.fileName to payload.base64Content
                    else -> "" to ""
                }
                _status.value = "Receiving file..."
                val data = base64ToByteArray(base64)
                val file = saveByteArrayToFile(context, fileName, data)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                addMessageToUi(conversationId, null, null, senderId, fileName, uri)
                _status.value = "Connected"
            }
            else -> { /* Ignore Handshake/UserList */ }
        }
    }

    private suspend fun sendNetworkMessage(message: NetworkMessage) {
        val jsonMessage = Json.encodeToString(message)
        withContext(Dispatchers.IO) {
            if (serverSocket != null) {
                // HOST: Route the message
                val recipientId = message.recipientIdentifier
                if (recipientId == null) {
                    // Broadcast (only for UserList)
                    clientSockets.values.forEach { client -> sendMessageToSocket(client, jsonMessage) }
                } else if (recipientId == myIdentifier) {
                    // Host sent to self, just update UI (already done)
                } else {
                    // 1-to-1 message: Send to specific client
                    clientSockets[recipientId]?.let { recipientSocket ->
                        sendMessageToSocket(recipientSocket, jsonMessage)
                    }
                }
            } else if (clientSocket != null) {
                // CLIENT: Send everything to host
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

    // --- Utility Functions ---

    // NEW: Helper to format timestamp
    private fun formatTimestamp(timestamp: Long): String {
        return try {
            val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
            sdf.format(Date(timestamp))
        } catch (e: Exception) {
            "..."
        }
    }

    // NEW: Helper to create the last message preview
    private fun createMessagePreview(message: UiMessage): String {
        return when {
            message.text != null -> message.text
            message.bitmap != null -> "📷 Image"
            message.fileName?.endsWith("mp4", true) == true -> "📹 Video"
            message.fileName != null -> "📄 ${message.fileName}"
            else -> "..."
        }
    }

    // NEW: Updates the chat summary list, bringing the active chat to the top
    private fun updateChatSummary(conversationId: String, lastMessage: UiMessage) {
        _chatSummaries.update { currentSummaries ->
            val existingSummary = currentSummaries.find { it.recipientId == conversationId }
            val currentCount = existingSummary?.unreadCount ?: 0
            val isViewingThisChat = (_currentlyViewedChatId.value == conversationId)

            // Increment count only if it's a new message from someone else
            // AND we are not currently viewing that chat.
            val newUnreadCount = if (!lastMessage.isFromMe && !isViewingThisChat) {
                currentCount + 1
            } else {
                // If we are viewing, or if we sent the message, the count stays the same
                // (it will be 0 if markChatAsRead was called)
                existingSummary?.unreadCount ?: 0
            }

            val summary = ChatSummary(
                recipientId = conversationId,
                lastMessage = createMessagePreview(lastMessage),
                timestamp = lastMessage.timestamp,
                isFromMe = lastMessage.isFromMe,
                unreadCount = newUnreadCount // APPLY NEW COUNT
            )

            val otherSummaries = currentSummaries.filterNot { it.recipientId == conversationId }
            listOf(summary) + otherSummaries // Prepend the new summary to the top
        }
    }

    // --- MODIFIED: addMessageToUi ---
    private fun addMessageToUi(
        conversationId: String, // The *other* person's ID
        text: String? = null,
        bitmap: Bitmap? = null,
        senderIdentifier: String,
        fileName: String? = null,
        localFileUri: Uri? = null
    ) {
        val isFromMe = (senderIdentifier == myIdentifier)
        // Check if we are currently viewing this chat
        val isViewingThisChat = (_currentlyViewedChatId.value == conversationId)

        // Don't add to UI if it's a message from us AND we're in the chat
        // (it's already added when we send it)
        // ... (This logic is complex, let's simplify)

        // The senderIdentifier is the "display name"
        val displayName = if (isFromMe) "Me" else senderIdentifier

        val uiMessage = UiMessage(
            text = text,
            bitmap = bitmap,
            isFromMe = isFromMe,
            senderIdentifier = displayName,
            fileName = fileName,
            localFileUri = localFileUri
        )

        // Update the map of messages
        _messages.update { currentMap ->
            val currentHistory = currentMap[conversationId] ?: emptyList()
            currentMap + (conversationId to (currentHistory + uiMessage))
        }

        // Update the chat summary "chat head" list
        if (senderIdentifier != "System") {
            // Check if we should update the summary
            // (We always update, `updateChatSummary` handles the logic)
            updateChatSummary(conversationId, uiMessage)
        }
    }

    // --- All other utilities (uriToMetadata, uriToByteArray, etc.) are unchanged ---

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

    fun cleanUp() {
        _status.value = "Cleaning up..."
        _isConnected.value = false
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
        _messages.value = emptyMap() // MODIFIED
        _chatSummaries.value = emptyList() // MODIFIED
        _connectedUsers.value = emptySet()
    }

    override fun onCleared() {
        cleanUp()
        super.onCleared()
    }
}
