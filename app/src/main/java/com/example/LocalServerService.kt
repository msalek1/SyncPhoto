package com.example

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.NetworkInterface
import java.util.Collections

class LocalServerService : Service() {

    private var localServer: LocalPhotoServer? = null
    private var nsdHelper: NsdHelper? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    companion object {
        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning = _isServiceRunning.asStateFlow()

        private val _serverPort = MutableStateFlow(0)
        val serverPort = _serverPort.asStateFlow()

        private val _receiverProgressName = MutableStateFlow("")
        val receiverProgressName = _receiverProgressName.asStateFlow()

        private val _receiverProgressVal = MutableStateFlow(0f)
        val receiverProgressVal = _receiverProgressVal.asStateFlow()

        private val _receivedPhotos = MutableStateFlow<List<Pair<String, Uri>>>(emptyList())
        val receivedPhotos = _receivedPhotos.asStateFlow()

        private val _pairingPin = MutableStateFlow("")
        val pairingPin = _pairingPin.asStateFlow()

        fun clearReceivedPhotos() {
            _receivedPhotos.value = emptyList()
        }
    }

    override fun onCreate() {
        super.onCreate()
        nsdHelper = NsdHelper(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: "START"
        if (action == "STOP") {
            stopServiceAndServer()
        } else {
            val pin = intent?.getStringExtra("pin") ?: ""
            startServiceAndServer(pin)
        }
        return START_NOT_STICKY
    }

    private fun startServiceAndServer(requestedPin: String) {
        val activePin = if (requestedPin.isNotEmpty()) requestedPin else (1000..9999).random().toString()
        _pairingPin.value = activePin
        _isServiceRunning.value = true

        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "PhotoSync::ReceiverWakeLock")
        wakeLock?.acquire()

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        wifiLock = wifiManager.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PhotoSync::ReceiverWifiLock")
        wifiLock?.acquire()

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "photo_backup_server_channel")
            .setContentTitle("Photo Backup Service Active")
            .setContentText("Local server matching with PIN: $activePin")
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Ignore type to bypass Android 14 strict typing requirements if it throws
                startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(1001, notification)
            }
        } catch (e: Exception) {
            Log.e("LocalServerService", "FGS crash fallback", e)
            try {
                // In API 34+, you MUST provide a type if it's in the manifest, but if this fails, we just don't run as FGS
            } catch (e2: Exception) {
                Log.e("LocalServerService", "Failed to start FGS", e2)
            }
        }

        // Start local photo server
        localServer?.stop()
        localServer = LocalPhotoServer(
            context = this,
            port = 0, // dynamic port
            onProgress = { filename, progress ->
                _receiverProgressName.value = filename
                _receiverProgressVal.value = progress
            },
            onPhotoReceived = { filename, uri, success ->
                if (success && uri != null) {
                    val currentList = _receivedPhotos.value.toMutableList()
                    currentList.add(0, Pair(filename, uri))
                    _receivedPhotos.value = currentList
                }
                _receiverProgressName.value = ""
                _receiverProgressVal.value = 0f
            }
        ).apply {
            start()
        }

        val boundPort = localServer?.getPort() ?: 9090
        _serverPort.value = boundPort

        val deviceModelName = Build.MODEL.replace(" ", "_")
        val nsdName = "Backup_${deviceModelName}"

        nsdHelper?.unregisterService()
        nsdHelper?.registerService(boundPort, nsdName) { registeredName ->
            Log.d("LocalServerService", "Service registered in local network as $registeredName")
        }
    }

    private fun stopServiceAndServer() {
        nsdHelper?.unregisterService()
        localServer?.stop()
        localServer = null
        _isServiceRunning.value = false
        _serverPort.value = 0
        _receiverProgressName.value = ""
        _receiverProgressVal.value = 0f
        _pairingPin.value = ""
        try { wakeLock?.release() } catch(e: Exception){}
        try { wifiLock?.release() } catch(e: Exception){}
        stopForeground(true)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "photo_backup_server_channel",
                "Local Photo Backup Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground Service for incoming file transfers and sync"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopServiceAndServer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
