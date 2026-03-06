package com.buildmon.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class BuildMonViewModel : ViewModel() {

    private var client: BuildMonClient? = null

    val builds = MutableStateFlow<List<BuildJob>>(emptyList())
    val cpu    = MutableStateFlow(0f)
    val status = MutableStateFlow("Not connected")
    val serverIp = MutableStateFlow("192.168.1.42")

    fun connect() {
        client?.disconnect()
        val currentIp = serverIp.value.trim()
        if (currentIp.isEmpty()) {
            status.value = "Enter IP first"
            return
        }
        
        val newClient = BuildMonClient(currentIp)
        client = newClient

        viewModelScope.launch {
            newClient.builds.collect { builds.value = it }
        }
        viewModelScope.launch {
            newClient.cpu.collect { cpu.value = it }
        }
        viewModelScope.launch {
            newClient.status.collect { status.value = it }
        }

        newClient.connect()
    }

    fun setupNotifications(context: Context) {
        val channelId = "buildmon_events"
        val channel = NotificationChannel(
            channelId,
            "Build Events",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications for finished or failed builds"
        }
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)

        viewModelScope.launch {
            // Wait for client to be initialized
            while (client == null) {
                kotlinx.coroutines.delay(500)
            }
            
            client?.events?.collect { update ->
                val title = if (update.type == "finished") "✅ Build Finished" else "❌ Build Failed"
                val body = if (update.type == "finished") {
                    "${update.project} completed in ${update.duration_seconds}s"
                } else {
                    "${update.project} failed: ${update.error_line}"
                }
                
                showNotification(context, channelId, title, body)
            }
        }
    }

    private fun showNotification(context: Context, channelId: String, title: String, body: String) {
        val notification = NotificationCompat.Builder(context, channelId)
            // Use a system default icon since we don't have custom ones
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    fun disconnect() {
        client?.disconnect()
        status.value = "Disconnected"
    }

    override fun onCleared() {
        super.onCleared()
        client?.disconnect()
    }
}
