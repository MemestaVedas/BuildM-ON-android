package com.buildmon.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class BuildMonViewModel : ViewModel() {

    private var client: BuildMonClient? = null

    val builds = MutableStateFlow<List<BuildJob>>(emptyList())
    val cpu    = MutableStateFlow(0f)
    val status = MutableStateFlow("Not connected")
    val serverIp = MutableStateFlow("192.168.1.33") // Default/hint
    val isScanning = MutableStateFlow(false)

    private val PREFS_NAME = "buildmon_prefs"
    private val KEY_IP = "last_ip"

    fun loadSettings(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedIp = prefs.getString(KEY_IP, null)
        if (savedIp != null) {
            serverIp.value = savedIp
            connect(context) // Auto-connect if we have a saved IP
        }
        startDiscovery(context)
    }

    private fun saveIp(context: Context, ip: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_IP, ip).apply()
    }

    private fun startDiscovery(context: Context) {
        if (isScanning.value) return
        viewModelScope.launch(Dispatchers.IO) {
            isScanning.value = true
            
            // Acquire MulticastLock for some devices to receive UDP broadcasts
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val lock = wifiManager.createMulticastLock("buildmon_discovery_lock").apply {
                setReferenceCounted(true)
                acquire()
            }

            try {
                val socket = DatagramSocket(7713)
                socket.soTimeout = 5000
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)

                while (isScanning.value) {
                    try {
                        socket.receive(packet)
                        val message = String(packet.data, 0, packet.length)
                        if (message.startsWith("BUILDM-ON_DISCOVERY")) {
                            val ip = if (message.contains(":")) {
                                message.substringAfter(":")
                            } else {
                                packet.address.hostAddress
                            }
                            
                            withContext(Dispatchers.Main) {
                                if (status.value != "Connected") {
                                    serverIp.value = ip
                                    status.value = "Discovery: Found PC at $ip"
                                    // Proactively connect to the discovered terminal
                                    connect(context)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Timeout or other error, just continue
                    }
                }
                socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                if (lock.isHeld) lock.release()
                isScanning.value = false
            }
        }
    }
    fun connect(context: Context? = null) {
        client?.stop()
        val currentIp = serverIp.value.trim()
        if (currentIp.isEmpty()) {
            status.value = "Enter IP first"
            return
        }
        
        context?.let { saveIp(it, currentIp) }
        
        val newClient = BuildMonClient(currentIp, viewModelScope)
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

        newClient.start()
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
        client?.stop()
        status.value = "Disconnected"
    }

    override fun onCleared() {
        super.onCleared()
        client?.stop()
    }
}
