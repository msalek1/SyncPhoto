package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.util.concurrent.TimeUnit
import android.provider.MediaStore
import android.os.Build
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.content.IntentFilter

class ScheduledBackupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("PhotoSyncPrefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("schedule_enabled", false)
        val ip = prefs.getString("last_target_ip", null)
        val port = prefs.getInt("last_target_port", -1)
        val pin = prefs.getString("last_target_pin", null)
        val autoPairName = prefs.getString("auto_pair_name", "") ?: ""

        if (!enabled) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            // 1. Wi-Fi Check
            if (!isWifiConnected(context)) {
                updateLastStatus(context, "Skipped: Wi-Fi disconnected")
                return@launch
            }

            // 2. Battery Check
            if (!isBatterySufficient(context)) {
                updateLastStatus(context, "Skipped: Low battery (< 15%)")
                return@launch
            }

            // 3. Dynamic IP/Port Resolution under DHCP
            var targetIp = ip
            var targetPort = port

            if (autoPairName.isNotEmpty()) {
                val nsdHelper = NsdHelper(context)
                val resolved = nsdHelper.resolveDeviceByName(autoPairName)
                if (resolved != null) {
                    targetIp = resolved.first
                    targetPort = resolved.second
                    prefs.edit()
                        .putString("last_target_ip", targetIp)
                        .putInt("last_target_port", targetPort)
                        .apply()
                }
            }

            if (targetIp == null || targetPort == -1 || pin == null) {
                updateLastStatus(context, "Failed: Backup receiver device not paired")
                return@launch
            }

            val lastSync = prefs.getLong("last_scheduled_sync", 0L)
            
            // Get new photos since last sync
            val urisToUpload = mutableListOf<Uri>()
            val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED)
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} ASC"
            val selection = "${MediaStore.Images.Media.DATE_ADDED} > ?"
            
            // DATE_ADDED is in seconds!
            val selectionArgs = arrayOf((lastSync / 1000).toString())

            try {
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val contentUri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                        urisToUpload.add(contentUri)
                    }
                }
            } catch (e: Exception) {
                Log.e("ScheduledBackup", "Failed to query media", e)
            }

            if (urisToUpload.isEmpty()) {
                updateLastStatus(context, "Up to date: No new photos found")
                return@launch
            }

            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(0, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .build()

            // Ping target to check if it's reachable and PIN is accepted
            val pingRequest = Request.Builder()
                .url("http://$targetIp:$targetPort/api/v1/ping")
                .addHeader("X-Pairing-PIN", pin)
                .get()
                .build()

            var online = false
            var pingErrorCode = -1
            try {
                client.newCall(pingRequest).execute().use { resp ->
                    online = (resp.code == 200)
                    pingErrorCode = resp.code
                }
            } catch (e: Exception) {
                Log.e("ScheduledBackup", "Ping failed", e)
            }

            if (!online) {
                val errorMsg = if (pingErrorCode == 401) "Failed: Incorrect pairing PIN" else "Failed: Receiver device offline"
                updateLastStatus(context, errorMsg)
                return@launch
            }

            var successCount = 0
            for (uri in urisToUpload) {
                try {
                    var filename = uri.lastPathSegment?.plus(".jpg") ?: "scheduled_${System.currentTimeMillis()}.jpg"
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0 && cursor.moveToFirst()) {
                            filename = cursor.getString(nameIdx)
                        }
                    }

                    val requestBody = object : RequestBody() {
                        override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
                        override fun contentLength() = -1L
                        override fun writeTo(sink: BufferedSink) {
                            context.contentResolver.openInputStream(uri)?.use { stream ->
                                val buffer = ByteArray(65536)
                                var bytesRead: Int
                                while (stream.read(buffer).also { bytesRead = it } != -1) {
                                    sink.write(buffer, 0, bytesRead)
                                }
                            }
                        }
                    }

                    val uploadRequest = Request.Builder()
                        .url("http://$targetIp:$targetPort/api/v1/upload")
                        .addHeader("X-Pairing-PIN", pin)
                        .addHeader("X-File-Name", Uri.encode(filename))
                        .post(requestBody)
                        .build()

                    client.newCall(uploadRequest).execute().use { resp ->
                        if (resp.code == 200 || resp.code == 409) {
                            successCount++
                            // Save success to history
                            val db = SyncDatabase.getDatabase(context)
                            db.syncHistoryDao().insertRecord(
                                SyncHistoryRecord(
                                    filename = filename,
                                    fileHash = "scheduled_${System.currentTimeMillis()}", // dummy hash to avoid reading twice
                                    sizeBytes = 0L,
                                    direction = "SENDER",
                                    targetDeviceName = autoPairName.ifEmpty { "Preferred Device" },
                                    status = "SUCCESS",
                                    bytesTransferred = 0L,
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ScheduledBackup", "Upload failed", e)
                }
            }

            if (successCount > 0) {
                prefs.edit().putLong("last_scheduled_sync", System.currentTimeMillis()).apply()
                updateLastStatus(context, "Success: Sent $successCount photos")
                
                val manager = context.getSystemService(NotificationManager::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel = NotificationChannel("scheduled_sync_channel", "Scheduled Sync", NotificationManager.IMPORTANCE_DEFAULT)
                    manager.createNotificationChannel(channel)
                }
                
                val builder = NotificationCompat.Builder(context, "scheduled_sync_channel")
                    .setSmallIcon(android.R.drawable.ic_menu_upload)
                    .setContentTitle("Scheduled Photo Backup Complete")
                    .setContentText("Successfully backed up $successCount photos.")
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    
                manager.notify(2001, builder.build())
            } else {
                updateLastStatus(context, "Failed: Photos failed to transfer")
            }
        }
    }

    private fun isWifiConnected(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork ?: return false
                val capabilities = cm.getNetworkCapabilities(network) ?: return false
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            } else {
                @Suppress("DEPRECATION")
                val info = cm.activeNetworkInfo
                info != null && info.isConnected && info.type == ConnectivityManager.TYPE_WIFI
            }
        } catch (e: Exception) {
            true // default to true if error
        }
    }

    private fun isBatterySufficient(context: Context): Boolean {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, filter) ?: return true
            val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val percent = level * 100 / scale.toFloat()
            val isCharging = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) != 0
            percent >= 15f || isCharging
        } catch (e: Exception) {
            true // default to true if error
        }
    }

    private fun updateLastStatus(context: Context, status: String) {
        val prefs = context.getSharedPreferences("PhotoSyncPrefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("last_scheduled_sync_time", System.currentTimeMillis())
            .putString("last_scheduled_sync_status", status)
            .apply()
    }
}
