package com.buildmon.android

import okhttp3.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow

class BuildMonClient(private val serverIp: String, private val scope: CoroutineScope) {

    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    val builds = MutableStateFlow<List<BuildJob>>(emptyList())
    val cpu    = MutableStateFlow(0f)
    val status = MutableStateFlow("Connecting...")
    
    // SharedFlow for one-shot events like "finished" or "failed"
    val events = MutableSharedFlow<BuildUpdate>(extraBufferCapacity = 10)

    private var socket: WebSocket? = null
    private var connectionJob: Job? = null
    private var isActive = true

    fun start() {
        isActive = true
        connectionJob = scope.launch {
            var delayMs = 1000L
            while (isActive) {
                try {
                    status.value = "Connecting..."
                    connectOnce()
                    // If we get here, it means we're in a listener, 
                    // we need to wait until the socket closes to retry.
                    while (socket != null && isActive) {
                        delay(1000)
                    }
                } catch (e: Exception) {
                    status.value = "Error: ${e.message}"
                }
                if (isActive) {
                    delay(delayMs)
                    delayMs = (delayMs * 2).coerceAtMost(10000L) // Exponential backoff
                }
            }
        }
    }

    private fun connectOnce() {
        val request = Request.Builder()
            .url("ws://$serverIp:7712/ws")
            .build()

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                status.value = "Connected"
                // Reset builds on fresh connection
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val update = json.decodeFromString<BuildUpdate>(text)
                    when (update.type) {
                        "hello", "update" -> {
                            builds.value = update.builds
                            cpu.value    = update.cpu
                        }
                        "stats" -> {
                            cpu.value = update.cpu
                        }
                        "finished", "failed" -> {
                            events.tryEmit(update)
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
                socket = null
                status.value = "Retrying..."
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                socket = null
                status.value = "Connection Closed"
            }
        })
    }

    fun stop() {
        isActive = false
        socket?.close(1000, "App closed")
        socket = null
        connectionJob?.cancel()
    }

    fun disconnect() {
        socket?.close(1000, "App closed")
        socket = null
    }
}
