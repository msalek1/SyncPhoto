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
import okhttp3.MultipartBody
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

class ScheduledBackupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("PhotoSyncPrefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("schedule_enabled", false)
        val ip = prefs.getString("last_target_ip", null)
        val port = prefs.getInt("last_target_port", -1)
        val pin = prefs.getString("last_target_pin", null)

        if (!enabled || ip == null || port == -1 || pin == null) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
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

            if (urisToUpload.isEmpty()) return@launch

            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(0, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .build()

            // Ping
            val pingRequest = Request.Builder()
                .url("http://$ip:$port/api/v1/ping")
                .addHeader("X-Pairing-PIN", pin)
                .get()
                .build()

            var online = false
            try {
                client.newCall(pingRequest).execute().use { resp ->
                    online = (resp.code == 200)
                }
            } catch (e: Exception) {}

            if (!online) return@launch

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
                        .url("http://$ip:$port/api/v1/upload")
                        .addHeader("X-Pairing-PIN", pin)
                        .addHeader("X-File-Name", Uri.encode(filename))
                        .post(requestBody)
                        .build()

                    client.newCall(uploadRequest).execute().use { resp ->
                        if (resp.code == 200) {
                            successCount++
                            // Save success to history
                            val db = SyncDatabase.getDatabase(context)
                            db.syncHistoryDao().insertRecord(
                                SyncHistoryRecord(
                                    filename = filename,
                                    fileHash = "scheduled_${System.currentTimeMillis()}", // dummy hash to avoid reading file twice
                                    sizeBytes = 0L,
                                    direction = "SENDER",
                                    targetDeviceName = "Scheduled Target",
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
            }
        }
    }
}
