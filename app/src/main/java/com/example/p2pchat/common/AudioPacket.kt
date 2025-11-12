package com.example.p2pchat.common

// Data class for audio packets
// Enhanced data class with timestamp
data class AudioPacket(
    val sequenceNumber: Int,
    val data: ByteArray,
    val length: Int,
    val timestamp: Long = 0L
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AudioPacket
        return sequenceNumber == other.sequenceNumber
    }

    override fun hashCode(): Int {
        return sequenceNumber
    }
}