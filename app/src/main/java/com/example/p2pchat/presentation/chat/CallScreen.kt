package com.example.p2pchat.presentation.chat


import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow

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


@Composable
fun CallScreen(
    callState: CallState,
    callDurationFlow: StateFlow<Long>,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onHangup: () -> Unit
) {
    val isVisible = callState !is CallState.Idle
    val callDuration by callDurationFlow.collectAsState()

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it })
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.95f)),
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
                    duration = callDuration,
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
    // --- START: Animation Code ---
    // This creates an infinite repeating animation
    val infiniteTransition = rememberInfiniteTransition(label = "ringing_animation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = -15f, // Start rotated 15 degrees left
        targetValue = 15f,  // Animate to 15 degrees right
        animationSpec = infiniteRepeatable(
            animation = tween(100, easing = LinearEasing), // Each "shake" takes 100ms
            repeatMode = RepeatMode.Reverse // Go back and forth
        ),
        label = "icon_rotation"
    )
    // --- END: Animation Code ---

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Pulsing indicator
        Box(
            modifier = Modifier
                .size(120.dp)
                .background(Color.White.copy(alpha = 0.1f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Phone,
                contentDescription = "Ringing phone icon",
                tint = Color.White,
                modifier = Modifier
                    .size(60.dp)
                    // Apply the rotation animation here
                    .graphicsLayer {
                        rotationZ = rotation
                    }
            )
        }

        Spacer(Modifier.height(32.dp))

        Text(
            text = callerId,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Incoming Call",
            fontSize = 20.sp,
            color = Color.Gray
        )

        Spacer(Modifier.height(120.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CallButton(
                    icon = Icons.Default.Close,
                    backgroundColor = Color.Red,
                    onClick = onReject
                )
                Spacer(Modifier.height(8.dp))
                Text("Decline", color = Color.White, fontSize = 14.sp)
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CallButton(
                    icon = Icons.Default.Call,
                    backgroundColor = Color.Green,
                    onClick = onAccept
                )
                Spacer(Modifier.height(8.dp))
                Text("Accept", color = Color.White, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun OutgoingCallUI(
    recipientId: String,
    onHangup: () -> Unit
) {
    var dotCount by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(500)
            dotCount = (dotCount + 1) % 4
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .background(Color.White.copy(alpha = 0.1f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(60.dp)
            )
        }

        Spacer(Modifier.height(32.dp))

        Text(
            text = recipientId,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Calling" + ".".repeat(dotCount),
            fontSize = 20.sp,
            color = Color.Gray
        )

        Spacer(Modifier.height(120.dp))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CallButton(
                icon = Icons.Default.CallEnd,
                backgroundColor = Color.Red,
                onClick = onHangup
            )
            Spacer(Modifier.height(8.dp))
            Text("End Call", color = Color.White, fontSize = 14.sp)
        }
    }
}

@Composable
private fun ActiveCallUI(
    recipientId: String,
    duration: Long,
    onHangup: () -> Unit
) {
    var isMuted by remember { mutableStateOf(false) }
    var isSpeakerOn by remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 80.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .background(Color.White.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(60.dp)
                )
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = recipientId,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = formatDuration(duration),
                fontSize = 18.sp,
                color = Color.Gray
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CallButton(
                        icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        backgroundColor = if (isMuted) Color.Red else Color.DarkGray,
                        onClick = { isMuted = !isMuted }
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (isMuted) "Unmute" else "Mute",
                        color = Color.White,
                        fontSize = 12.sp
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CallButton(
                        icon = Icons.Default.CallEnd,
                        backgroundColor = Color.Red,
                        onClick = onHangup
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("End", color = Color.White, fontSize = 12.sp)
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CallButton(
                        icon = if (isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeDown,
                        backgroundColor = if (isSpeakerOn) Color(0xFF2196F3) else Color.DarkGray,
                        onClick = { isSpeakerOn = !isSpeakerOn }
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Speaker",
                        color = Color.White,
                        fontSize = 12.sp
                    )
                }
            }
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
        colors = ButtonDefaults.buttonColors(containerColor = backgroundColor),
        contentPadding = PaddingValues(0.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(32.dp)
        )
    }
}

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60

    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format("%02d:%02d", minutes, secs)
    }
}