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

    @Serializable
    @SerialName("ping")
    data class Ping(val timestamp: Long = System.currentTimeMillis()) : MessagePayload()

    @Serializable
    @SerialName("call_request")
    data class CallRequest(val callId: String, val callerIp: String) : MessagePayload()


    @Serializable
    @SerialName("call_accept")
    data class CallAccept(val callId: String, val receiverIp: String) : MessagePayload()


    @Serializable
    @SerialName("call_reject")
    data class CallReject(val callId: String) : MessagePayload()

    @Serializable
    @SerialName("call_hangup")
    data class CallHangup(val callId: String) : MessagePayload()
}
