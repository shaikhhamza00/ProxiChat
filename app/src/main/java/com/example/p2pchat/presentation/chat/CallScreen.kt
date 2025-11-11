package com.example.p2pchat.presentation.chat


import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// --- NEW: Defines the state of any potential call ---
sealed interface CallState {
    object Idle : CallState
    data class Outgoing(
        val callId: String,
        val recipientId: String
    ) : CallState
    data class Incoming(
        val callId: String,
        val callerId: String,
        val callerIp: String
    ) : CallState
    data class Active(
        val callId: String,
        val recipientId: String,
        val targetIp: String
    ) : CallState
}

/**
 * A full-screen overlay that appears when a call is outgoing, incoming, or active.
 */
@Composable
fun CallScreen(
    callState: CallState,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onHangup: () -> Unit
) {
    val isVisible = callState !is CallState.Idle

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut() + slideOutVertically()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.9f)),
            contentAlignment = Alignment.Center
        ) {
            when (callState) {
                is CallState.Incoming -> IncomingCallUI(
                    callerId = callState.callerId,
                    onAccept = onAccept,
                    onReject = onReject
                )
                is CallState.Outgoing -> OutgoingCallUI(
                    recipientId = callState.recipientId,
                    onHangup = onHangup
                )
                is CallState.Active -> ActiveCallUI(
                    recipientId = callState.recipientId,
                    onHangup = onHangup
                )
                is CallState.Idle -> { /* Handled by AnimatedVisibility */ }
            }
        }
    }
}

@Composable
private fun IncomingCallUI(
    callerId: String,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(callerId, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text("Incoming Call", fontSize = 20.sp, color = Color.Gray)
        Spacer(Modifier.height(120.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            CallButton(
                icon = Icons.Default.Call,
                backgroundColor = Color.Green,
                onClick = onAccept
            )
            CallButton(
                icon = Icons.Default.CallEnd,
                backgroundColor = Color.Red,
                onClick = onReject
            )
        }
    }
}

@Composable
private fun OutgoingCallUI(
    recipientId: String,
    onHangup: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(recipientId, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text("Ringing...", fontSize = 20.sp, color = Color.Gray)
        Spacer(Modifier.height(120.dp))
        CallButton(
            icon = Icons.Default.CallEnd,
            backgroundColor = Color.Red,
            onClick = onHangup
        )
    }
}

@Composable
private fun ActiveCallUI(
    recipientId: String,
    onHangup: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 80.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(recipientId, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Text("00:00", fontSize = 20.sp, color = Color.Gray) // Timer not implemented
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            CallButton(
                icon = Icons.Default.MicOff,
                backgroundColor = Color.DarkGray,
                onClick = { /* Mute not implemented */ }
            )
            CallButton(
                icon = Icons.Default.CallEnd,
                backgroundColor = Color.Red,
                onClick = onHangup
            )
        }
    }
}


@Composable
private fun CallButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    backgroundColor: Color,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        shape = CircleShape,
        modifier = Modifier.size(72.dp),
        colors = ButtonDefaults.buttonColors(containerColor = backgroundColor)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(36.dp)
        )
    }
}