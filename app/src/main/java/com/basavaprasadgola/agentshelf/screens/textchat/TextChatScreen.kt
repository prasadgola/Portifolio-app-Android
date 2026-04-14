package com.basavaprasadgola.agentshelf.screens.textchat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ChatMessage(
    val id: Long = System.nanoTime(),
    val text: String,
    val isUser: Boolean,
    val replyTo: ChatMessage? = null
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TextChatScreen(modifier: Modifier = Modifier) {
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var inputText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var replyingTo by remember { mutableStateOf<ChatMessage?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .navigationBarsPadding()
    ) {
        // ── Fixed Header ──────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Chat with Basavaprasad",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Ask me anything!",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 13.sp
            )
        }

        // ── Chat Messages ─────────────────────────────────────────
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Spacer(Modifier.height(8.dp)) }

            if (messages.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 60.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Start the conversation 👋",
                            color = Color.White.copy(alpha = 0.35f),
                            fontSize = 14.sp
                        )
                    }
                }
            }

            items(messages, key = { it.id }) { message ->
                ChatBubble(
                    message = message,
                    onLongPress = { replyingTo = message }
                )
            }

            if (isLoading) {
                item {
                    Row(
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "  Thinking...",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 14.sp
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }

        // ── Reply Preview Bar ─────────────────────────────────────
        if (replyingTo != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.08f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(36.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.6f))
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (replyingTo!!.isUser) "You" else "Basavaprasad",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = replyingTo!!.text,
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = { replyingTo = null }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel reply",
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // ── Input Bar ─────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = {
                    Text(
                        "Type a message...",
                        color = Color.White.copy(alpha = 0.4f)
                    )
                },
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                    focusedBorderColor = Color.White,
                    cursorColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(24.dp),
                singleLine = true
            )

            IconButton(
                onClick = {
                    val text = inputText.trim()
                    if (text.isNotEmpty() && !isLoading) {
                        val quoted = replyingTo
                        inputText = ""
                        replyingTo = null
                        messages.add(ChatMessage(text = text, isUser = true, replyTo = quoted))
                        isLoading = true
                        scope.launch {
                            val reply = sendMessage(
                                message = text,
                                replyContext = quoted?.text
                            )
                            messages.add(ChatMessage(text = reply, isUser = false))
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = Color.Black
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatBubble(
    message: ChatMessage,
    onLongPress: () -> Unit
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = screenWidth * 0.75f)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (message.isUser) 16.dp else 4.dp,
                        bottomEnd = if (message.isUser) 4.dp else 16.dp
                    )
                )
                .background(
                    if (message.isUser) Color.White else Color.White.copy(alpha = 0.1f)
                )
                .combinedClickable(
                    onClick = {},
                    onLongClick = onLongPress
                )
                .padding(12.dp)
        ) {
            Column {
                // ── Quoted reply snippet ──────────────────────────
                if (message.replyTo != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (message.isUser)
                                    Color.Black.copy(alpha = 0.08f)
                                else
                                    Color.White.copy(alpha = 0.08f)
                            )
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(32.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (message.isUser)
                                        Color.Black.copy(alpha = 0.3f)
                                    else
                                        Color.White.copy(alpha = 0.4f)
                                )
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = if (message.replyTo.isUser) "You" else "Basavaprasad",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (message.isUser)
                                    Color.Black.copy(alpha = 0.6f)
                                else
                                    Color.White.copy(alpha = 0.7f)
                            )
                            Text(
                                text = message.replyTo.text,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (message.isUser)
                                    Color.Black.copy(alpha = 0.45f)
                                else
                                    Color.White.copy(alpha = 0.45f)
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }

                // ── Actual message text ───────────────────────────
                Text(
                    text = message.text,
                    color = if (message.isUser) Color.Black else Color.White,
                    fontSize = 15.sp,
                    lineHeight = 22.sp
                )
            }
        }
    }
}

private suspend fun sendMessage(message: String, replyContext: String? = null): String {
    return withContext(Dispatchers.IO) {
        try {
            val url = URL("https://basavaprasad-digital-twin-882178443942.us-central1.run.app/chat")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 30000
            connection.readTimeout = 30000

            val combinedMessage = if (replyContext != null) {
                "Replying to: \"$replyContext\" | My message: $message"
            } else {
                message
            }

            val jsonBody = JSONObject().apply {
                put("message", combinedMessage)
            }

            android.util.Log.d("TextChat", "Sending: $jsonBody")

            connection.outputStream.use { os ->
                os.write(jsonBody.toString().toByteArray())
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().readText()
                JSONObject(response).optString("response", "No response")
            } else {
                "Error: ${connection.responseCode}"
            }
        } catch (e: Exception) {
            "Error: ${e.message ?: "Something went wrong"}"
        }
    }
}