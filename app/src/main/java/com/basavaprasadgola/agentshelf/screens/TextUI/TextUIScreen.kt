package com.basavaprasadgola.agentshelf.screens.TextUI

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

private const val BACKEND_URL = "https://user-interface-864112330557.us-central1.run.app/chat"

private fun extractHtml(raw: String): String {
    val regex = Regex("```html\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
    return regex.find(raw)?.groupValues?.get(1)?.trim() ?: raw.trim()
}

private fun postChat(message: String): String {
    val conn = URL(BACKEND_URL).openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.setRequestProperty("Content-Type", "application/json")
    conn.doOutput = true
    conn.connectTimeout = 30_000
    conn.readTimeout = 60_000
    val body = JSONObject().apply {
        put("message", message)
        put("history", org.json.JSONArray())
    }.toString()
    OutputStreamWriter(conn.outputStream).use { it.write(body) }
    val code = conn.responseCode
    val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
        .bufferedReader().use { it.readText() }
    if (code !in 200..299) throw Exception("Server error $code: $text")
    return JSONObject(text).getString("response")
}

@Composable
fun TextUIScreen(modifier: Modifier = Modifier) {
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    var inputText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var generatedHtml by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun onSend() {
        val text = inputText.trim()
        if (text.isEmpty() || isLoading) return
        keyboard?.hide()
        inputText = ""
        errorMessage = null
        scope.launch {
            isLoading = true
            try {
                val raw = withContext(Dispatchers.IO) { postChat(text) }
                generatedHtml = extractHtml(raw)
            } catch (e: Exception) {
                errorMessage = e.message ?: "Something went wrong"
            } finally {
                isLoading = false
            }
        }
    }

    AnimatedContent(
        targetState = generatedHtml,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "screen"
    ) { html ->
        if (html != null) {
            // ── WebView screen ────────────────────────────────────────────
            Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewClient = WebViewClient()
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                        }
                    },
                    update = { webView ->
                        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Back button overlay
                IconButton(
                    onClick = { generatedHtml = null },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                ) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        } else {
            // ── Input screen ──────────────────────────────────────────────
            Box(
                modifier = modifier.fillMaxSize().background(Color.Black)
            ) {
                // Error snackbar
                if (errorMessage != null) {
                    Text(
                        text = "⚠ $errorMessage",
                        color = Color(0xFFFF6080),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(16.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF2A1520))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(Color.Black)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it; errorMessage = null },
                        placeholder = {
                            Text("Describe a UI...", color = Color.White.copy(alpha = 0.4f))
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
                        singleLine = true,
                        enabled = !isLoading
                    )

                    IconButton(
                        onClick = { onSend() },
                        enabled = inputText.isNotBlank() && !isLoading,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                if (inputText.isNotBlank() && !isLoading) Color.White
                                else Color.White.copy(alpha = 0.2f)
                            )
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.Black,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = Color.Black,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}