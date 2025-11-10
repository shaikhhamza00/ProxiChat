//package com.example.p2pchat.presentation.chat
//
//import android.content.Intent
//import android.net.Uri
//import android.os.Build
//import android.os.Bundle
//import android.util.Log
//import androidx.activity.ComponentActivity
//import androidx.activity.compose.rememberLauncherForActivityResult
//import androidx.activity.compose.setContent
//import androidx.activity.enableEdgeToEdge
//import androidx.activity.result.PickVisualMediaRequest
//import androidx.activity.result.contract.ActivityResultContracts
//import androidx.compose.foundation.Image
//import androidx.compose.foundation.clickable
//import androidx.compose.foundation.layout.Arrangement
//import androidx.compose.foundation.layout.Column
//import androidx.compose.foundation.layout.Row
//import androidx.compose.foundation.layout.Spacer
//import androidx.compose.foundation.layout.fillMaxSize
//import androidx.compose.foundation.layout.fillMaxWidth
//import androidx.compose.foundation.layout.heightIn
//import androidx.compose.foundation.layout.padding
//import androidx.compose.foundation.layout.size
//import androidx.compose.foundation.layout.width
//import androidx.compose.foundation.layout.widthIn
//import androidx.compose.foundation.lazy.LazyColumn
//import androidx.compose.foundation.lazy.items
//import androidx.compose.foundation.shape.RoundedCornerShape
//import androidx.compose.material.icons.Icons
//import androidx.compose.material.icons.automirrored.filled.Send
//import androidx.compose.material.icons.filled.AttachFile
//import androidx.compose.material.icons.filled.Description
//import androidx.compose.material.icons.filled.PhotoLibrary
//import androidx.compose.material.icons.filled.Send
//import androidx.compose.material.icons.filled.Videocam
//import androidx.compose.material3.Button
//import androidx.compose.material3.Card
//import androidx.compose.material3.CardDefaults
//import androidx.compose.material3.ExperimentalMaterial3Api
//import androidx.compose.material3.Icon
//import androidx.compose.material3.IconButton
//import androidx.compose.material3.MaterialTheme
//import androidx.compose.material3.OutlinedTextField
//import androidx.compose.material3.Scaffold
//import androidx.compose.material3.Surface
//import androidx.compose.material3.Text
//import androidx.compose.material3.TopAppBar
//import androidx.compose.material3.TopAppBarDefaults
//import androidx.compose.runtime.Composable
//import androidx.compose.runtime.DisposableEffect
//import androidx.compose.runtime.collectAsState
//import androidx.compose.runtime.getValue
//import androidx.compose.runtime.mutableStateOf
//import androidx.compose.runtime.remember
//import androidx.compose.runtime.setValue
//import androidx.compose.ui.Alignment
//import androidx.compose.ui.Modifier
//import androidx.compose.ui.draw.clip
//import androidx.compose.ui.graphics.asImageBitmap
//import androidx.compose.ui.layout.ContentScale
//import androidx.compose.ui.platform.LocalContext
//import androidx.compose.ui.text.font.FontStyle
//import androidx.compose.ui.text.font.FontWeight
//import androidx.compose.ui.tooling.preview.Preview
//import androidx.compose.ui.unit.dp
//import androidx.compose.ui.unit.sp
//import androidx.lifecycle.Lifecycle
//import androidx.lifecycle.LifecycleEventObserver
//import androidx.lifecycle.compose.LocalLifecycleOwner
//import androidx.lifecycle.viewModelScope
//import androidx.lifecycle.viewmodel.compose.viewModel
//import com.example.p2pchat.common.UiMessage
//import com.example.p2pchat.presentation.chat.ui.theme.P2PChatTheme
//import kotlinx.coroutines.launch
//
//class ChatActivity : ComponentActivity() {
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        setContent {
//            MaterialTheme {
//                Surface(
//                    modifier = Modifier.fillMaxSize(),
//                    color = MaterialTheme.colorScheme.background
//                ) {
//                    ChatScreen()
//                }
//            }
//        }
//    }
//}
//
//@OptIn(ExperimentalMaterial3Api::class)
//@Composable
//fun ChatScreen(viewModel: ChatViewModel = viewModel()) {
//    val status by viewModel.status.collectAsState()
//    val messages by viewModel.messages.collectAsState()
//    val isConnected by viewModel.isConnected.collectAsState()
//    val context = LocalContext.current
//    val lifecycleOwner = LocalLifecycleOwner.current
//
//    // 1. Launcher for Images and Videos
//    val mediaPickerLauncher = rememberLauncherForActivityResult(
//        ActivityResultContracts.PickVisualMedia()
//    ) { uri: Uri? ->
//        uri?.let {
//            val mimeType = context.contentResolver.getType(it)
//            if (mimeType?.startsWith("image") == true) {
//                viewModel.sendImage(context, it)
//            } else if (mimeType?.startsWith("video") == true) {
//                viewModel.sendVideo(context, it)
//            }
//        }
//    }
//
//    // 2. Launcher for generic files (PDFs, etc.)
//    val filePickerLauncher = rememberLauncherForActivityResult(
//        ActivityResultContracts.OpenDocument()
//    ) { uri: Uri? ->
//        uri?.let {
//            viewModel.sendFile(context, it)
//        }
//    }
//
//    // 3. Permission launchers
//    val permissionsLauncher = rememberLauncherForActivityResult(
//        ActivityResultContracts.RequestMultiplePermissions()
//    ) { permissions ->
//        if (permissions.values.any { !it }) {
//            viewModel.viewModelScope.launch {
//                viewModel._status.value = "Permissions denied."
//            }
//        }
//    }
//
//    fun checkAndRequestPermissions() {
//        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
//            arrayOf(
//                android.Manifest.permission.READ_MEDIA_IMAGES,
//                android.Manifest.permission.READ_MEDIA_VIDEO
//            )
//        } else {
//            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
//        }
//        permissionsLauncher.launch(permissions)
//    }
//
//    // Clean up network connections when app is destroyed
//    DisposableEffect(lifecycleOwner) {
//        val observer = LifecycleEventObserver { _, event ->
//            if (event == Lifecycle.Event.ON_DESTROY) {
//                viewModel.cleanUp()
//            }
//        }
//        lifecycleOwner.lifecycle.addObserver(observer)
//        onDispose {
//            lifecycleOwner.lifecycle.removeObserver(observer)
//        }
//    }
//
//    Scaffold(
//        topBar = {
//            TopAppBar(
//                title = { Text("Offline Chat") },
//                actions = {
//                    Text(
//                        text = status,
//                        modifier = Modifier.padding(end = 16.dp),
//                        fontSize = 12.sp,
//                        fontStyle = FontStyle.Italic
//                    )
//                },
//                colors = TopAppBarDefaults.topAppBarColors(
//                    containerColor = MaterialTheme.colorScheme.primaryContainer,
//                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
//                )
//            )
//        },
//        bottomBar = {
//            if (isConnected) {
//                MessageInputBar(
//                    onSendText = { viewModel.sendMessage(it) },
//                    onSendMedia = {
//                        checkAndRequestPermissions() // Check permissions first
//                        mediaPickerLauncher.launch(
//                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
//                        )
//                    },
//                    onSendFile = {
//                        filePickerLauncher.launch(arrayOf("application/pdf", "text/plain", "application/zip", "*/*"))
//                    }
//                )
//            }
//        }
//    ) { padding ->
//        Column(
//            modifier = Modifier
//                .fillMaxSize()
//                .padding(padding),
//            horizontalAlignment = Alignment.CenterHorizontally
//        ) {
//            if (!isConnected) {
//                ConnectionButtons(
//                    onHost = { viewModel.startHost() },
//                    onClient = { viewModel.startClient() }
//                )
//            }
//
//            LazyColumn(
//                modifier = Modifier
//                    .fillMaxWidth()
//                    .weight(1f)
//                    .padding(horizontal = 8.dp),
//                reverseLayout = true
//            ) {
//                items(messages.reversed()) { msg ->
//                    MessageBubble(message = msg)
//                }
//            }
//        }
//    }
//}
//
//@Composable
//fun ConnectionButtons(onHost: () -> Unit, onClient: () -> Unit) {
//    Column(
//        modifier = Modifier
//            .fillMaxWidth()
//            .padding(16.dp),
//        horizontalAlignment = Alignment.CenterHorizontally,
//        verticalArrangement = Arrangement.Center
//    ) {
//        Text(
//            "No connection. Start as a host or join a host.",
//            style = MaterialTheme.typography.bodyLarge,
//            modifier = Modifier.padding(bottom = 16.dp)
//        )
//        Row(
//            modifier = Modifier.fillMaxWidth(),
//            horizontalArrangement = Arrangement.SpaceEvenly
//        ) {
//            Button(onClick = onHost) {
//                Text("Start Host")
//            }
//            Button(onClick = onClient) {
//                Text("Join")
//            }
//        }
//    }
//}
//
//@Composable
//fun MessageInputBar(
//    onSendText: (String) -> Unit,
//    onSendMedia: () -> Unit,
//    onSendFile: () -> Unit
//) {
//    var text by remember { mutableStateOf("") }
//
//    Card(
//        modifier = Modifier.fillMaxWidth(),
//        elevation = CardDefaults.cardElevation(8.dp)
//    ) {
//        Row(
//            modifier = Modifier
//                .fillMaxWidth()
//                .padding(8.dp),
//            verticalAlignment = Alignment.CenterVertically
//        ) {
//            IconButton(onClick = onSendMedia) {
//                Icon(Icons.Default.PhotoLibrary, contentDescription = "Send Image or Video")
//            }
//            IconButton(onClick = onSendFile) {
//                Icon(Icons.Default.AttachFile, contentDescription = "Send File")
//            }
//
//            OutlinedTextField(
//                value = text,
//                onValueChange = { text = it },
//                modifier = Modifier.weight(1f),
//                placeholder = { Text("Type a message...") },
//                maxLines = 3
//            )
//            IconButton(onClick = {
//                onSendText(text)
//                text = ""
//            }) {
//                Icon(Icons.Default.Send, contentDescription = "Send Message")
//            }
//        }
//    }
//}
//
//@Composable
//fun MessageBubble(message: UiMessage) {
//    val alignment = if (message.isFromMe) Alignment.End else Alignment.Start
//    val color = if (message.isFromMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
//    val horizontalArrangement = if (message.isFromMe) Arrangement.End else Arrangement.Start
//
//    Row(
//        modifier = Modifier
//            .fillMaxWidth()
//            .padding(vertical = 4.dp),
//        horizontalArrangement = horizontalArrangement
//    ) {
//        Column(horizontalAlignment = alignment) {
//            Text(
//                text = message.senderIdentifier,
//                fontSize = 12.sp,
//                fontWeight = FontWeight.Light,
//                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
//            )
//            Surface(
//                shape = RoundedCornerShape(12.dp),
//                color = color,
//                modifier = Modifier.widthIn(max = 300.dp)
//            ) {
//                Column(modifier = Modifier.padding(10.dp)) {
//                    message.text?.let {
//                        Text(it)
//                    }
//                    message.bitmap?.let {
//                        Image(
//                            bitmap = it.asImageBitmap(),
//                            contentDescription = "Sent Image",
//                            modifier = Modifier
//                                .fillMaxWidth()
//                                .heightIn(max = 250.dp)
//                                .clip(RoundedCornerShape(8.dp)),
//                            contentScale = ContentScale.Fit
//                        )
//                    }
//                    if (message.fileName != null && message.localFileUri != null) {
//                        FileDisplay(
//                            fileName = message.fileName,
//                            fileUri = message.localFileUri,
//                            isFromMe = message.isFromMe
//                        )
//                    }
//                }
//            }
//        }
//    }
//}
//
//@Composable
//fun FileDisplay(fileName: String, fileUri: Uri, isFromMe: Boolean) {
//    val context = LocalContext.current
//    val (icon, description) = when (fileName.substringAfterLast('.').lowercase()) {
//        "pdf" -> Icons.Default.Description to "PDF Document"
//        "mp4", "mov", "mkv" -> Icons.Default.Videocam to "Video File"
//        else -> Icons.Default.AttachFile to "File"
//    }
//
//    val textColor = if (isFromMe) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
//
//    Row(
//        modifier = Modifier
//            .clickable {
//                try {
//                    val intent = Intent(Intent.ACTION_VIEW).apply {
//                        setDataAndType(fileUri, context.contentResolver.getType(fileUri))
//                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
//                    }
//                    context.startActivity(intent)
//                } catch (e: Exception) {
//                    Log.e("FileDisplay", "No app to open file: ${e.message}")
//                }
//            }
//            .padding(vertical = 4.dp),
//        verticalAlignment = Alignment.CenterVertically
//    ) {
//        Icon(
//            imageVector = icon,
//            contentDescription = description,
//            modifier = Modifier.size(32.dp),
//            tint = textColor
//        )
//        Spacer(modifier = Modifier.width(8.dp))
//        Text(
//            text = fileName,
//            color = textColor,
//            fontWeight = FontWeight.Medium,
//            modifier = Modifier.weight(1f, fill = false)
//        )
//    }
//}