package com.buildmon.android

import okhttp3.*
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow

class BuildMonClient(private val serverIp: String) {

    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    val builds = MutableStateFlow<List<BuildJob>>(emptyList())
    val cpu    = MutableStateFlow(0f)
    val status = MutableStateFlow("Connecting...")
    
    // SharedFlow for one-shot events like "finished" or "failed"
    val events = MutableSharedFlow<BuildUpdate>(extraBufferCapacity = 10)

    private var socket: WebSocket? = null

    fun connect() {
        val request = Request.Builder()
            .url("ws://$serverIp:8765/ws")
            .build()

        socket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                status.value = "Connected to $serverIp"
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val update = json.decodeFromString<BuildUpdate>(text)
                    when (update.type) {
                        "update" -> {
                            builds.value = update.builds
                            cpu.value    = update.cpu
                        }
                        "finished", "failed" -> {
                            events.tryEmit(update)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
                status.value = "Disconnected — check IP"
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                status.value = "Disconnected"
            }
        })
    }

    fun disconnect() {
        socket?.close(1000, "App closed")
        socket = null
    }
}
