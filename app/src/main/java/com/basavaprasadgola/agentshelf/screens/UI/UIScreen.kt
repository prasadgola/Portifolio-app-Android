package com.basavaprasadgola.agentshelf.screens.UI

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "UIScreen"
private const val BACKEND_HTTP = "https://user-interface-864112330557.us-central1.run.app"
private const val BACKEND_WS   = "wss://user-interface-864112330557.us-central1.run.app/video"

// ── HTTP chat ─────────────────────────────────────────────────────────────────

private fun extractHtml(raw: String): String {
    val doctypeIdx = raw.indexOf("<!DOCTYPE", ignoreCase = true).takeIf { it >= 0 }
    val htmlIdx    = raw.indexOf("<html",      ignoreCase = true).takeIf { it >= 0 }
    val start = when {
        doctypeIdx != null && htmlIdx != null -> minOf(doctypeIdx, htmlIdx)
        doctypeIdx != null -> doctypeIdx
        htmlIdx    != null -> htmlIdx
        else -> null
    }
    return if (start != null) raw.substring(start).trimEnd().removeSuffix("```").trimEnd()
    else raw.trim()
}

private fun postChat(message: String): String {
    val conn = URL("$BACKEND_HTTP/chat").openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.setRequestProperty("Content-Type", "application/json")
    conn.doOutput = true
    conn.connectTimeout = 30_000
    conn.readTimeout    = 60_000
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

// ── Screen state ──────────────────────────────────────────────────────────────

private enum class Screen { INPUT, WEBVIEW }

// ── Frame packing helpers ─────────────────────────────────────────────────────

/**
 * Wire format sent over WebSocket (binary frames):
 *   [4 bytes LE = type] [4 bytes LE = payload length] [payload bytes]
 *   type 1 = JPEG video frame
 *   type 2 = PCM-16 audio chunk (16 kHz mono)
 */
private const val TYPE_VIDEO = 1
private const val TYPE_AUDIO = 2

private fun packFrame(type: Int, payload: ByteArray): ByteArray {
    val buf = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
    buf.putInt(type)
    buf.putInt(payload.size)
    buf.put(payload)
    return buf.array()
}

// ── Camera + Voice Session ────────────────────────────────────────────────────

private class CameraVoiceSession(
    private val onStateChange: (String) -> Unit
) {
    val isRunning   = AtomicBoolean(false)
    private val isSendingAudio = AtomicBoolean(false)
    private val wsRef = AtomicReference<WebSocket?>(null)

    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var audioTrack:  AudioTrack?  = null
    private var audioRecord: AudioRecord? = null
    private var micThread:      Thread? = null
    private var playbackThread: Thread? = null

    // Called by CameraX analyser on its executor — no thread needed here
    fun sendVideoFrame(jpeg: ByteArray) {
        if (!isSendingAudio.get()) return   // only when session is live
        wsRef.get()?.send(packFrame(TYPE_VIDEO, jpeg).toByteString())
    }

    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)
        onStateChange("connecting")

        setupAudioTrack()
        startPlaybackThread()
        startMicThread()
        connectWebSocket()
    }

    private fun setupAudioTrack() {
        val sr = 24_000
        val minBuf = AudioTrack.getMinBufferSize(
            sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sr)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            minBuf * 4, AudioTrack.MODE_STREAM, 0
        )
        audioTrack!!.play()
    }

    private fun startPlaybackThread() {
        playbackThread = Thread {
            try {
                while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                    val data = audioQueue.poll(100, TimeUnit.MILLISECONDS)
                    if (data != null) {
                        audioTrack?.write(data, 0, data.size)
                        onStateChange("speaking")
                    } else {
                        if (isRunning.get() && !isSendingAudio.get()) {
                            isSendingAudio.set(true)
                            onStateChange("live")
                        }
                    }
                }
            } catch (_: InterruptedException) {}
        }.also { it.start() }
    }

    private fun connectWebSocket() {
        if (!isRunning.get()) return
        isSendingAudio.set(false)

        client.newWebSocket(Request.Builder().url(BACKEND_WS).build(),
            object : WebSocketListener() {

                override fun onOpen(ws: WebSocket, response: Response) {
                    Log.d(TAG, "Video WS open")
                    wsRef.set(ws)
                    isSendingAudio.set(true)
                    onStateChange("live")
                }

                override fun onMessage(ws: WebSocket, bytes: ByteString) {
                    isSendingAudio.set(false)
                    audioQueue.offer(bytes.toByteArray())
                    onStateChange("speaking")
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    try {
                        val msg = JSONObject(text)
                        when (msg.getString("type")) {
                            "turn_complete" -> {
                                isSendingAudio.set(false)
                                wsRef.set(null)
                                try { ws.close(1000, "turn done") } catch (_: Exception) {}
                                Thread {
                                    try {
                                        Thread.sleep(300)
                                        if (isRunning.get()) connectWebSocket()
                                    } catch (_: InterruptedException) {}
                                }.start()
                            }
                            "transcript" -> Log.d(TAG, "AI: ${msg.optString("text")}")
                            "error"      -> Log.e(TAG, "Server: ${msg.optString("message")}")
                        }
                    } catch (e: Exception) { Log.e(TAG, "Parse: ${e.message}") }
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WS failure: ${t.message}")
                    wsRef.set(null)
                    if (isRunning.get()) {
                        Thread {
                            try {
                                Thread.sleep(1000)
                                if (isRunning.get()) connectWebSocket()
                            } catch (_: InterruptedException) {}
                        }.start()
                    }
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    wsRef.set(null)
                }
            })
    }

    private fun startMicThread() {
        micThread = Thread {
            try {
                val bufSize = AudioRecord.getMinBufferSize(
                    16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                @Suppress("MissingPermission")
                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC, 16_000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize * 2)
                audioRecord = recorder
                recorder.startRecording()

                val buffer = ByteArray(3200)
                while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0 && isSendingAudio.get()) {
                        wsRef.get()?.send(packFrame(TYPE_AUDIO, buffer.copyOf(read)).toByteString())
                    }
                }
                recorder.stop()
                recorder.release()
            } catch (e: Exception) { Log.e(TAG, "Mic: ${e.message}") }
        }.also { it.start() }
    }

    fun stop() {
        isRunning.set(false)
        isSendingAudio.set(false)
        micThread?.interrupt();      micThread = null
        playbackThread?.interrupt(); playbackThread = null
        audioQueue.clear()
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
        try { wsRef.get()?.close(1000, "done") } catch (_: Exception) {}
        wsRef.set(null)
        onStateChange("idle")
    }
}

// ── Camera overlay composable ─────────────────────────────────────────────────
// ── Camera overlay composable ─────────────────────────────────────────────────

@Composable
private fun CameraOverlay(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context        = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var cameraState  by remember { mutableStateOf("idle") }
    var session      by remember { mutableStateOf<CameraVoiceSession?>(null) }
    var cameraFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_FRONT) }

    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val cameraProviderRef = remember { mutableStateOf<ProcessCameraProvider?>(null) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) {
            val s = CameraVoiceSession { cameraState = it }
            session = s
            s.start()
        }
    }

    fun startSession() {
        val permissions = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        val allGranted  = permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            val s = CameraVoiceSession { cameraState = it }
            session = s
            s.start()
        } else {
            permLauncher.launch(permissions)
        }
    }

    // Rebind camera whenever facing changes
    fun rebindCamera(
        provider: ProcessCameraProvider,
        previewView: PreviewView,
        facing: Int,
        currentSession: CameraVoiceSession
    ) {
        provider.unbindAll()

        val selector = CameraSelector.Builder()
            .requireLensFacing(facing)
            .build()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val analyser = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    if (currentSession.isRunning.get()) {
                        val jpeg = imageProxyToJpeg(imageProxy, quality = 60)
                        currentSession.sendVideoFrame(jpeg)
                    }
                    imageProxy.close()
                }
            }

        try {
            provider.bindToLifecycle(lifecycleOwner, selector, preview, analyser)
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed: ${e.message}")
        }
    }

    LaunchedEffect(Unit) { startSession() }

    DisposableEffect(Unit) {
        onDispose {
            session?.stop()
            cameraExecutor.shutdown()
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {

        // ── Camera preview ────────────────────────────────────────────────
        val currentSession = session
        if (currentSession != null) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val providerFuture = ProcessCameraProvider.getInstance(ctx)
                    providerFuture.addListener({
                        val provider = providerFuture.get()
                        cameraProviderRef.value = provider
                        rebindCamera(provider, previewView, cameraFacing, currentSession)
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
                update = { previewView ->
                    // Triggered when cameraFacing changes
                    cameraProviderRef.value?.let { provider ->
                        rebindCamera(provider, previewView, cameraFacing, currentSession)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // ── Status label ──────────────────────────────────────────────────
        Text(
            text = when (cameraState) {
                "connecting" -> "Connecting..."
                "live"       -> "● Live"
                "speaking"   -> "Speaking..."
                else         -> ""
            },
            color = if (cameraState == "live") Color(0xFF00E676) else Color.White,
            fontSize = 14.sp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 14.dp, vertical = 6.dp)
        )

        // ── Close button (top-left) ───────────────────────────────────────
        IconButton(
            onClick = {
                session?.stop()
                session = null
                onDismiss()
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.6f))
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
        }

        // ── Flip button (top-right) ───────────────────────────────────────
        IconButton(
            onClick = {
                cameraFacing = if (cameraFacing == CameraSelector.LENS_FACING_FRONT)
                    CameraSelector.LENS_FACING_BACK
                else
                    CameraSelector.LENS_FACING_FRONT
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.6f))
        ) {
            Icon(
                painter = painterResource(android.R.drawable.ic_menu_rotate),
                contentDescription = "Flip camera",
                tint = Color.White
            )
        }
    }
}

// ── JPEG conversion ───────────────────────────────────────────────────────────

private fun imageProxyToJpeg(proxy: ImageProxy, quality: Int = 60): ByteArray {
    val yBuffer = proxy.planes[0].buffer
    val uBuffer = proxy.planes[1].buffer
    val vBuffer = proxy.planes[2].buffer
    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()
    val nv21 = ByteArray(ySize + uSize + vSize)
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)
    val yuvImage = android.graphics.YuvImage(
        nv21, android.graphics.ImageFormat.NV21, proxy.width, proxy.height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(android.graphics.Rect(0, 0, proxy.width, proxy.height), quality, out)
    return out.toByteArray()
}

// ── Main composable ───────────────────────────────────────────────────────────

@Composable
fun UIScreen(modifier: Modifier = Modifier) {
    val keyboard = LocalSoftwareKeyboardController.current
    val scope    = rememberCoroutineScope()

    var screen        by remember { mutableStateOf(Screen.INPUT) }
    var inputText     by remember { mutableStateOf("") }
    var isLoading     by remember { mutableStateOf(false) }
    var generatedHtml by remember { mutableStateOf<String?>(null) }
    var errorMessage  by remember { mutableStateOf<String?>(null) }
    var showCamera    by remember { mutableStateOf(false) }

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
                screen = Screen.WEBVIEW
            } catch (e: Exception) {
                errorMessage = e.message ?: "Something went wrong"
            } finally {
                isLoading = false
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {

        AnimatedContent(
            targetState = screen,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "screen"
        ) { currentScreen ->
            when (currentScreen) {

                Screen.WEBVIEW -> {
                    val html = generatedHtml ?: ""
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    webViewClient = WebViewClient()
                                    settings.javaScriptEnabled    = true
                                    settings.domStorageEnabled    = true
                                    settings.useWideViewPort      = true
                                    settings.loadWithOverviewMode = true
                                    settings.setSupportZoom(true)
                                    settings.builtInZoomControls  = true
                                    settings.displayZoomControls  = false
                                    isVerticalScrollBarEnabled    = true
                                    loadDataWithBaseURL(BACKEND_HTTP, html, "text/html", "UTF-8", null)
                                }
                            },
                            update = { it.loadDataWithBaseURL(BACKEND_HTTP, html, "text/html", "UTF-8", null) },
                            modifier = Modifier.fillMaxSize()
                        )
                        IconButton(
                            onClick = { screen = Screen.INPUT; generatedHtml = null },
                            modifier = Modifier
                                .align(Alignment.TopStart).padding(12.dp)
                                .size(40.dp).clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.6f))
                        ) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                    }
                }

                Screen.INPUT -> {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                        if (errorMessage != null) {
                            Text(
                                text = "⚠ $errorMessage",
                                color = Color(0xFFFF6080),
                                modifier = Modifier
                                    .align(Alignment.TopCenter).padding(16.dp)
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
                            // ── Camera button ─────────────────────────────
                            IconButton(
                                onClick = { showCamera = true },
                                modifier = Modifier
                                    .size(44.dp).clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.1f))
                            ) {
                                Icon(
                                    painter = painterResource(android.R.drawable.ic_menu_camera),
                                    contentDescription = "Camera",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(Modifier.width(8.dp))

                            OutlinedTextField(
                                value = inputText,
                                onValueChange = { inputText = it; errorMessage = null },
                                placeholder = { Text("Describe a UI...", color = Color.White.copy(alpha = 0.4f)) },
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                    focusedBorderColor   = Color.White,
                                    cursorColor          = Color.White,
                                    unfocusedTextColor   = Color.White,
                                    focusedTextColor     = Color.White
                                ),
                                shape = RoundedCornerShape(24.dp),
                                singleLine = true,
                                enabled = !isLoading
                            )
                            Spacer(Modifier.width(8.dp))
                            IconButton(
                                onClick = { onSend() },
                                enabled = inputText.isNotBlank() && !isLoading,
                                modifier = Modifier
                                    .size(44.dp).clip(CircleShape)
                                    .background(if (inputText.isNotBlank() && !isLoading) Color.White else Color.White.copy(alpha = 0.2f))
                            ) {
                                if (isLoading) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.Black, strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.Black, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Camera overlay (full-screen, on top) ──────────────────────────
        AnimatedVisibility(
            visible = showCamera,
            enter = fadeIn(),
            exit  = fadeOut()
        ) {
            CameraOverlay(
                onDismiss = { showCamera = false },
                modifier  = Modifier.fillMaxSize()
            )
        }
    }
}