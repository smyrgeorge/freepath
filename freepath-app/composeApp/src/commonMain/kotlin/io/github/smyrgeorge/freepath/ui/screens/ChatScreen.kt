package io.github.smyrgeorge.freepath.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.smyrgeorge.freepath.AppResources
import io.github.smyrgeorge.freepath.AppState
import io.github.smyrgeorge.freepath.core.actor.AppProtocol
import io.github.smyrgeorge.freepath.database.ContactEntry
import io.github.smyrgeorge.freepath.database.MessageStatus
import io.github.smyrgeorge.freepath.ui.components.FreepathFingerprint
import io.github.smyrgeorge.freepath.ui.components.FreepathTopBar
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    contact: ContactEntry,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val onlinePeers by AppState.onlinePeers.collectAsState()
    val chats by AppState.chats.collectAsState()
    val messages = chats[contact.peerId] ?: emptyList()
    val listState = rememberLazyListState()
    var inputText by remember { mutableStateOf("") }

    val localName = contact.name?.takeIf { it.isNotBlank() }
    val cardName = contact.contact.name?.takeIf { it.isNotBlank() && !it.startsWith("#") }
    val displayName = localName ?: cardName ?: contact.peerId.take(12)

    val isOnline = contact.peerId in onlinePeers

    // Load chat history from the database when the screen opens
    LaunchedEffect(contact.peerId) {
        AppState.loadChat(contact.peerId)
    }

    // Scroll to bottom whenever messages change
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun sendMessage() {
        val text = inputText.trim()
        if (text.isBlank()) return
        inputText = ""
        scope.launch {
            AppResources.app.tell(AppProtocol.SendMessage(contact.peerId, text))
        }
    }

    // imePadding() shifts the Column above the keyboard; navigationBarsPadding() handles home bar
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        FreepathTopBar(
            title = displayName,
            leftAction = {
                IconButton(onClick = onBack) {
                    Text(
                        text = "←",
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
            rightAction = {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isOnline) Color(0xFF4CAF50) else Color(0xFFBDBDBD))
                )
            },
            content = {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    FreepathFingerprint(text = contact.peerId)
                }
            },
        )

        // Chat area — tap anywhere to dismiss keyboard
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures { focusManager.clearFocus() }
                }
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages) { msg ->
                    ChatBubble(
                        text = msg.message.body ?: "",
                        fromMe = msg.senderId == AppState.contact.peerId,
                        status = msg.status,
                    )
                }
            }
        }

        // Input row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier
                    .weight(1f)
                    // Desktop: Enter sends, Shift+Enter inserts newline
                    .onKeyEvent { event ->
                        if (event.key == Key.Enter &&
                            !event.isShiftPressed &&
                            event.type == KeyEventType.KeyDown
                        ) {
                            sendMessage()
                            true
                        } else false
                    },
                placeholder = {
                    Text(
                        text = "Message…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                textStyle = MaterialTheme.typography.bodySmall,
                shape = RoundedCornerShape(24.dp),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { sendMessage() }),
            )

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (inputText.isNotBlank()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(
                    onClick = { sendMessage() },
                    enabled = inputText.isNotBlank(),
                ) {
                    Text(
                        text = "↑",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (inputText.isNotBlank())
                            MaterialTheme.colorScheme.onPrimary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(text: String, fromMe: Boolean, status: MessageStatus) {
    val bubbleColor = if (fromMe)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.surfaceVariant

    val textColor = if (fromMe)
        MaterialTheme.colorScheme.onPrimary
    else
        MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromMe) Arrangement.End else Arrangement.Start,
    ) {
        if (fromMe) Spacer(modifier = Modifier.width(48.dp))
        Box(
            modifier = Modifier
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (fromMe) 16.dp else 4.dp,
                        bottomEnd = if (fromMe) 4.dp else 16.dp,
                    )
                )
                .background(bubbleColor)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor,
                )
                if (fromMe) {
                    MessageStatusIcon(
                        status = status,
                        tint = textColor.copy(alpha = 0.85f),
                    )
                }
            }
        }
        if (!fromMe) Spacer(modifier = Modifier.width(48.dp))
    }
}

@Composable
private fun MessageStatusIcon(status: MessageStatus, tint: Color) {
    if (status == MessageStatus.SENDING || status == MessageStatus.RECEIVED) return
    Canvas(modifier = Modifier.size(12.dp)) {
        val w = size.width
        val h = size.height
        when (status) {
            // (not drawn)
            MessageStatus.SENDING -> Unit

            // ⏱ clock — stored for relay, not yet handed to any peer
            MessageStatus.QUEUED -> {
                val stroke = 1.4.dp.toPx()
                val r = minOf(w, h) / 2f - stroke
                val cx = w / 2f
                val cy = h / 2f
                drawCircle(color = tint, radius = r, center = Offset(cx, cy), style = Stroke(width = stroke))
                drawLine(
                    color = tint,
                    start = Offset(cx, cy),
                    end = Offset(cx, cy - r * 0.55f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = tint,
                    start = Offset(cx, cy),
                    end = Offset(cx + r * 0.45f, cy),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }

            // ✓ single check — handed to the mesh (best-effort, no receipt)
            MessageStatus.RELAYED -> {
                val stroke = 1.6.dp.toPx()
                val path = Path().apply {
                    moveTo(w * 0.12f, h * 0.55f)
                    lineTo(w * 0.42f, h * 0.82f)
                    lineTo(w * 0.92f, h * 0.22f)
                }
                drawPath(
                    path = path,
                    color = tint,
                    style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }

            // ✓✓ double check — recipient's node acked (direct delivery)
            MessageStatus.SENT -> {
                val stroke = 1.6.dp.toPx()
                fun tick(dx: Float): Path = Path().apply {
                    moveTo(w * (0.04f + dx), h * 0.55f)
                    lineTo(w * (0.30f + dx), h * 0.82f)
                    lineTo(w * (0.74f + dx), h * 0.22f)
                }
                val style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
                drawPath(path = tick(0.18f), color = tint, style = style)
                drawPath(path = tick(0.00f), color = tint, style = style)
            }

            // ⓘ (red disc with white "!")
            MessageStatus.FAILED -> {
                val badge = Color(0xFFEF5350)
                val r = minOf(w, h) / 2f
                val cx = w / 2f
                val cy = h / 2f
                drawCircle(color = badge, radius = r, center = Offset(cx, cy))
                drawLine(
                    color = Color.White,
                    start = Offset(cx, cy - r * 0.45f),
                    end = Offset(cx, cy + r * 0.15f),
                    strokeWidth = 1.6.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawCircle(
                    color = Color.White,
                    radius = 1.dp.toPx(),
                    center = Offset(cx, cy + r * 0.55f),
                )
            }

            // (not drawn — inbound only)
            MessageStatus.RECEIVED -> Unit
        }
    }
}
