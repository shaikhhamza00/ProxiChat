package com.example.p2pchat.common

import android.graphics.Bitmap
import android.net.Uri
import java.util.UUID

data class UiMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String? = null,
    val bitmap: Bitmap? = null,
    val isFromMe: Boolean,
    val senderIdentifier: String,
    val fileName: String? = null,
    val localFileUri: Uri? = null,
    val timestamp: Long = System.currentTimeMillis() // NEW: For sorting
)

data class ChatSummary(
    val recipientId: String, // The phone number of the *other* person
    val lastMessage: String,
    val timestamp: Long,
    val isFromMe: Boolean,
    val unreadCount: Int = 0 // NEW: Add this field
)