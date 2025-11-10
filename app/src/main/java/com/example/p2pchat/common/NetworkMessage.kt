package com.example.p2pchat.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class NetworkMessage(
    val senderIdentifier: String,
    val recipientIdentifier: String?, // null = broadcast, "System" = handshake/list, "12345" = 1-to-1
    val payload: MessagePayload
)
