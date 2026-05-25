package com.example

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.InputStream
import java.util.concurrent.TimeUnit

class AutoSyncJobService : JobService() {
    companion object {
        fun scheduleAutoSync(context: Context) {
            val componentName = android.content.ComponentName(context, AutoSyncJobService::class.java)
            val builder = android.app.job.JobInfo.Builder(1002, componentName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                builder.addTriggerContentUri(android.app.job.JobInfo.TriggerContentUri(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    android.app.job.JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
                ))
                builder.setTriggerContentUpdateDelay(2000)
                builder.setTriggerContentMaxDelay(10000)
            }
            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as android.app.job.JobScheduler
            jobScheduler.schedule(builder.build())
        }
        
        fun cancelAutoSync(context: Context) {
            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as android.app.job.JobScheduler
            jobScheduler.cancel(1002)
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        val uris = params?.triggeredContentUris
        if (uris == null || uris.isEmpty()) {
            jobFinished(params, false)
            return false
        }
        
        val prefs = getSharedPreferences("PhotoSyncPrefs", Context.MODE_PRIVATE)
        val ip = prefs.getString("last_target_ip", null)
        val port = prefs.getInt("last_target_port", -1)
        val pin = prefs.getString("last_target_pin", null)
        val autoSyncEnabled = prefs.getBoolean("auto_sync_enabled", false)
        
        if (!autoSyncEnabled || ip == null || port == -1 || pin == null) {
            jobFinished(params, false)
            return false
        }
        
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(0, TimeUnit.SECONDS) // Infinite write for large files
            .readTimeout(0, TimeUnit.SECONDS) // Infinite read for large files
            .build()
            
        CoroutineScope(Dispatchers.IO).launch {
            for (uri in uris) {
                try {
                    var filename = uri.lastPathSegment?.plus(".jpg") ?: "auto_uploaded_${System.currentTimeMillis()}.jpg"
                    try {
                        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (nameIdx >= 0 && cursor.moveToFirst()) {
                                filename = cursor.getString(nameIdx)
                            }
                        }
                    } catch (e: Exception) {}
                    
                    // Simple ping to check if online
                    val pingRequest = Request.Builder()
                        .url("http://$ip:$port/api/v1/ping")
                        .addHeader("X-Pairing-PIN", pin)
                        .get()
                        .build()
                        
                    var canUpload = false
                    try {
                        client.newCall(pingRequest).execute().use { resp ->
                            canUpload = (resp.code == 200)
                        }
                    } catch (e: Exception) {
                        Log.e("AutoSync", "Ping failed", e)
                    }
                    
                    if (canUpload) {
                        val requestBody = object : RequestBody() {
                            override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
                            
                            override fun contentLength(): Long {
                                try {
                                    var size = -1L
                                    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                                        val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                                        if (sizeIdx >= 0 && cursor.moveToFirst()) {
                                            size = cursor.getLong(sizeIdx)
                                        }
                                    }
                                    return if (size > 0) size else -1L
                                } catch (e: Exception) { return -1L }
                            }
                            
                            override fun writeTo(sink: BufferedSink) {
                                contentResolver.openInputStream(uri)?.use { stream ->
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
                                sendNotification(filename)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("AutoSync", "Auto upload failed", e)
                }
            }
            jobFinished(params, false)
            
            // Reschedule the job so it keeps monitoring for next photos
            scheduleAutoSync(applicationContext)
        }
        
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        return true
    }
    
    private fun sendNotification(filename: String) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("auto_sync_channel", "Auto Sync", NotificationManager.IMPORTANCE_DEFAULT)
            manager.createNotificationChannel(channel)
        }
        
        val builder = NotificationCompat.Builder(this, "auto_sync_channel")
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle("Photo Automatically Backed Up")
            .setContentText("Image $filename has been backed up to reserve phone.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            
        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), builder.build())
    }
}
