package com.example.p2pchat

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role.Companion.Image
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.p2pchat.common.ChatSummary
import com.example.p2pchat.common.PrefsManager
import com.example.p2pchat.common.UiMessage
import com.example.p2pchat.presentation.chat.ChatViewModel
import com.example.p2pchat.presentation.signup.SignupActivity
import com.example.p2pchat.ui.theme.P2PChatTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppRoutes {
    const val CHAT_LIST = "chat_list"
    const val CHAT_ROOM = "chat_room/{recipientId}" // Argument for phone number

    fun chatRoomRoute(recipientId: String) = "chat_room/$recipientId"
}

// --- MainActivity Class ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // --- "GATEKEEPER" LOGIC ---
        // Check if user is signed in BEFORE showing any UI
        if (PrefsManager.getPhoneNumber(this) == null) {
            // Not signed in. Redirect to SignupActivity.
            startActivity(Intent(this, SignupActivity::class.java))
            finish() // Close this activity
            return // Stop executing onCreate
        }
        // --- END LOGIC ---

        // User is signed in, proceed to show the main app UI
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Create the ViewModel here, it will be shared by all screens
                    // in the NavHost.
                    val viewModel: ChatViewModel = viewModel()
                    AppNavigation(viewModel)
                }
            }
        }
    }
}

/**
 * Main navigation host for the app.
 */
@Composable
fun AppNavigation(viewModel: ChatViewModel) {
    val navController = rememberNavController()
    val context = LocalContext.current

    NavHost(navController = navController, startDestination = AppRoutes.CHAT_LIST) {

        // --- Screen 1: The List of Chat Heads ---
        composable(AppRoutes.CHAT_LIST) {
            ChatListScreen(
                viewModel = viewModel,
                onUserClick = { identifier ->
                    // Navigate to the chat room for this user
                    navController.navigate(AppRoutes.chatRoomRoute(identifier))
                },
                // Handle logout
                onLogout = {
                    // 1. Stop all network connections
                    viewModel.cleanUp()
                    // 2. Clear saved user data
                    PrefsManager.clear(context)
                    // 3. Go back to Signup screen, clearing all other activities
                    val intent = Intent(context, SignupActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    context.startActivity(intent)
                    // 4. Finish the current MainActivity
                    (context as? Activity)?.finish()
                }
            )
        }

        // --- Screen 2: The Actual Chat Room ---
        composable(AppRoutes.CHAT_ROOM) { backStackEntry ->
            // Get the phone number from the navigation route
            val recipientId = backStackEntry.arguments?.getString("recipientId") ?: "Unknown"

            ChatRoomScreen(
                viewModel = viewModel,
                recipientId = recipientId, // Pass the recipient's ID to the chat room
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}

/**
 * This is the "WhatsApp" style main screen.
 * It shows connection buttons and a list of connected users.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    viewModel: ChatViewModel,
    onUserClick: (String) -> Unit,
    onLogout: () -> Unit // Logout callback
) {
    val status by viewModel.status.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()

    // --- Get BOTH lists ---
    val chatSummaries by viewModel.chatSummaries.collectAsState()
    val allConnectedUsers by viewModel.connectedUsers.collectAsState()
    // ---

    val myIdentifier = PrefsManager.getPhoneNumber(LocalContext.current) ?: "Me"
    val lifecycleOwner = LocalLifecycleOwner.current

    // --- Logic to find users you *haven't* chatted with ---
    val activeChatRecipients = chatSummaries.map { it.recipientId }.toSet()
    val newContacts = allConnectedUsers
        .filter { it != myIdentifier && it !in activeChatRecipients }
        .sorted() // Sort them alphabetically
    // ---

    // Clean up network connections when app is destroyed
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                viewModel.cleanUp()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Offline Chat") },
                actions = {
                    // Status Text
                    Text(
                        text = status,
                        modifier = Modifier.padding(horizontal = 12.dp),
                        fontSize = 12.sp,
                        fontStyle = FontStyle.Italic,
                        style = MaterialTheme.typography.bodySmall
                    )
                    // Logout Button
                    IconButton(onClick = onLogout) {
                        Icon(
                            imageVector = Icons.Default.Logout,
                            contentDescription = "Logout"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!isConnected) {
                ConnectionButtons(
                    onHost = { viewModel.startHost() },
                    onClient = { viewModel.startClient() }
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {

                    // --- LIST 1: Active, sorted chats ---
                    items(chatSummaries) { summary ->
                        // Don't show a chat head for myself
                        if (summary.recipientId != myIdentifier) {
                            ChatHeadItem(
                                identifier = summary.recipientId,
                                lastMessage = if (summary.isFromMe) "You: ${summary.lastMessage}" else summary.lastMessage,
                                timestamp = formatTimestamp(summary.timestamp),
                                unreadCount = summary.unreadCount, // Pass count
                                onClick = {
                                    onUserClick(summary.recipientId)
                                }
                            )
                        }
                    }

                    // --- LIST 2: Other connected users (new contacts) ---
                    if (newContacts.isNotEmpty()) {
                        item {
                            Text(
                                text = "Other Connected Users",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }

                        items(newContacts) { identifier ->
                            ChatHeadItem(
                                identifier = identifier,
                                lastMessage = "Tap to start a chat",
                                timestamp = "", // No timestamp for new contacts
                                unreadCount = 0, // Pass 0
                                onClick = {
                                    onUserClick(identifier)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A single "Chat Head" row, now driven by ChatSummary
 */
@OptIn(ExperimentalMaterial3Api::class) // Added for Badge
@Composable
fun ChatHeadItem(
    identifier: String,
    lastMessage: String,
    timestamp: String,
    unreadCount: Int, // NEW
    onClick: () -> Unit
) {
    // --- NEW: Determine "special effect" styles ---
    val messageWeight = if (unreadCount > 0) FontWeight.Bold else FontWeight.Normal
    val messageColor = if (unreadCount > 0) MaterialTheme.colorScheme.primary else Color.Gray
    val timestampColor = if (unreadCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    // ---

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.AccountCircle,
            contentDescription = "User Avatar",
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = identifier, // This is the phone number
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = lastMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = messageColor, // MODIFIED
                fontWeight = messageWeight, // MODIFIED
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))

        // --- NEW: Timestamp and Badge Column ---
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.width(IntrinsicSize.Min) // Prevent this column from growing
        ) {
            Text(
                text = timestamp,
                style = MaterialTheme.typography.bodySmall,
                color = timestampColor, // MODIFIED
                fontWeight = messageWeight // MODIFIED
            )
            // Show badge only if count > 0
            if (unreadCount > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Badge(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Text(
                        text = if (unreadCount > 9) "9+" else "$unreadCount", // Show 9+ if too many
                        modifier = Modifier.padding(horizontal = 4.dp),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

/**
 * This is the 1-to-1 chat room UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatRoomScreen(
    viewModel: ChatViewModel,
    recipientId: String, // We now know who we are talking to
    onNavigateBack: () -> Unit
) {
    val status by viewModel.status.collectAsState()

    // Observe the map of messages, but filter for *this* conversation
    val allMessages by viewModel.messages.collectAsState()
    val messages = allMessages[recipientId] ?: emptyList() // Get only messages for this user

    val context = LocalContext.current

    // --- Launchers for picking files ---
    val mediaPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let {
            val mimeType = context.contentResolver.getType(it)
            if (mimeType?.startsWith("image") == true) {
                viewModel.sendImage(context, it, recipientId)
            } else if (mimeType?.startsWith("video") == true) {
                viewModel.sendVideo(context, it, recipientId)
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.sendFile(context, it, recipientId) }
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.any { !it }) {
            viewModel.viewModelScope.launch { viewModel._status.value = "Permissions denied." }
        }
    }

    fun checkAndRequestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionsLauncher.launch(permissions)
    }

    // --- NEW: Mark chat as read when entering ---
    LaunchedEffect(key1 = recipientId) {
        viewModel.markChatAsRead(recipientId)
    }

    // --- NEW: Clear "currently viewing" when leaving ---
    DisposableEffect(key1 = recipientId) {
        onDispose {
            viewModel.clearCurrentlyViewedChat()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(recipientId) }, // Show who we're talking to
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Text(
                        text = status,
                        modifier = Modifier.padding(end = 16.dp),
                        fontSize = 12.sp,
                        fontStyle = FontStyle.Italic
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        bottomBar = {
            MessageInputBar(
                onSendText = { viewModel.sendMessage(it, recipientId) },
                onSendMedia = {
                    checkAndRequestPermissions()
                    mediaPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                    )
                },
                onSendFile = {
                    filePickerLauncher.launch(arrayOf("application/pdf", "text/plain", "application/zip", "*/*"))
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 8.dp),
            reverseLayout = true
        ) {
            // Use the filtered message list
            items(messages.reversed()) { msg ->
                MessageBubble(message = msg)
            }
        }
    }
}

// --- SHARED COMPOSABLES ---

@Composable
fun ConnectionButtons(onHost: () -> Unit, onClient: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "No connection. Start as a host or join a host.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = onHost) { Text("Start Host") }
            Button(onClick = onClient) { Text("Join") }
        }
    }
}

@Composable
fun MessageInputBar(
    onSendText: (String) -> Unit,
    onSendMedia: () -> Unit,
    onSendFile: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    Card(elevation = CardDefaults.cardElevation(8.dp)) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onSendMedia) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = "Send Image or Video")
            }
            IconButton(onClick = onSendFile) {
                Icon(Icons.Default.AttachFile, contentDescription = "Send File")
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message...") },
                maxLines = 3
            )
            IconButton(onClick = {
                if(text.isNotBlank()) {
                    onSendText(text)
                    text = ""
                }
            }) {
                Icon(Icons.Default.Send, contentDescription = "Send Message")
            }
        }
    }
}

@Composable
fun MessageBubble(message: UiMessage) {
    val alignment = if (message.isFromMe) Alignment.End else Alignment.Start
    val color = if (message.isFromMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val horizontalArrangement = if (message.isFromMe) Arrangement.End else Arrangement.Start

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = horizontalArrangement
    ) {
        Column(horizontalAlignment = alignment) {
            Text(
                text = message.senderIdentifier,
                fontSize = 12.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = color,
                modifier = Modifier.widthIn(max = 300.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    message.text?.let { Text(it) }
                    message.bitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "Sent Image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 250.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Fit
                        )
                    }
                    if (message.fileName != null && message.localFileUri != null) {
                        FileDisplay(
                            fileName = message.fileName,
                            fileUri = message.localFileUri,
                            isFromMe = message.isFromMe
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FileDisplay(fileName: String, fileUri: Uri, isFromMe: Boolean) {
    val context = LocalContext.current
    val (icon, description) = when (fileName.substringAfterLast('.').lowercase()) {
        "pdf" -> Icons.Default.Description to "PDF Document"
        "mp4", "mov", "mkv" -> Icons.Default.Videocam to "Video File"
        else -> Icons.Default.AttachFile to "File"
    }
    val textColor = if (isFromMe) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer

    Row(
        modifier = Modifier
            .clickable {
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(fileUri, context.contentResolver.getType(fileUri))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Log.e("FileDisplay", "No app to open file: ${e.message}")
                }
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = description, modifier = Modifier.size(32.dp), tint = textColor)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = fileName, color = textColor, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f, fill = false))
    }
}

/**
 * Helper to format timestamp for the chat list
 */
@Composable
private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    val oneDay = 1000 * 60 * 60 * 24

    return try {
        when {
            // Today
            diff < oneDay -> SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp))
            // Yesterday
            diff < oneTwoDays -> "Yesterday"
            // This week (e.g., "Mon")
            diff < oneWeek -> SimpleDateFormat("E", Locale.getDefault()).format(Date(timestamp))
            // Older
            else -> SimpleDateFormat("MM/dd/yy", Locale.getDefault()).format(Date(timestamp))
        }
    } catch (e: Exception) {
        ""
    }
}

private const val oneTwoDays = 2 * 24 * 60 * 60 * 1000
private const val oneWeek = 7 * 24 * 60 * 60 * 1000