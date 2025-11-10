package com.example.p2pchat.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
@Serializable
sealed class MessagePayload {
    @Serializable
    @SerialName("handshake")
    data class Handshake(val version: Int = 1) : MessagePayload()

    @Serializable
    @SerialName("user_list")
    data class UserListUpdate(val users: List<String>) : MessagePayload()

    @Serializable
    @SerialName("text")
    data class Text(val content: String) : MessagePayload()

    @Serializable
    @SerialName("image")
    data class Image(val base64Content: String) : MessagePayload()

    @Serializable
    @SerialName("video")
    data class Video(val fileName: String, val base64Content: String) : MessagePayload()

    @Serializable
    @SerialName("file")
    data class File(val fileName: String, val base64Content: String) : MessagePayload()
}
