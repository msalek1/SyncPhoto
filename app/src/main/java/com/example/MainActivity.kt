package com.example

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import android.content.ContentUris
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import android.os.StatFs
import android.os.Environment
import android.content.Intent

// ==========================================
// DATA MODELS & STATE
// ==========================================

data class PhotoPayload(
    val uri: Uri,
    val filename: String,
    val size: Long,
    val hash: String
)

data class MediaFolder(
    val id: String,
    val name: String,
    val count: Int,
    val firstPhotoUri: Uri? = null
)

data class DiscoveredDevice(
    val name: String,
    val ip: String,
    val port: Int
)

enum class DeletionOption {
    KEEP, DELETE
}

sealed class SyncState {
    object Idle : SyncState()
    object Preparation : SyncState()
    data class Transferring(
        val currentIndex: Int,
        val totalCount: Int,
        val currentFileName: String,
        val progress: Float
    ) : SyncState()
    data class Deleting(val uris: List<Uri>) : SyncState()
    data class Success(
        val transferredCount: Int,
        val transferredUris: List<Uri> = emptyList(),
        val partialError: String? = null
    ) : SyncState()
    data class Failed(val error: String) : SyncState()
}

// ==========================================
// BESPOKE MULTIPART PARSER
// ==========================================

class MultipartParser(private val bodyBytes: ByteArray, private val boundary: ByteArray) {
    
    private fun findSubarray(array: ByteArray, sub: ByteArray, start: Int): Int {
        if (sub.isEmpty() || array.size < sub.size) return -1
        for (i in start..array.size - sub.size) {
            var found = true
            for (j in sub.indices) {
                if (array[i + j] != sub[j]) {
                    found = false
                    break
                }
            }
            if (found) return i
        }
        return -1
    }

    fun parseParts(): List<Part> {
        val parts = mutableListOf<Part>()
        val boundaryWithDashes = "--".toByteArray() + boundary
        var index = findSubarray(bodyBytes, boundaryWithDashes, 0)
        
        while (index != -1) {
            val nextIndex = findSubarray(bodyBytes, boundaryWithDashes, index + boundaryWithDashes.size)
            if (nextIndex == -1) break
            
            val startPart = index + boundaryWithDashes.size
            val endPart = nextIndex
            
            if (startPart < endPart) {
                val crlfCrlf = "\r\n\r\n".toByteArray()
                val doubleCrlfIndex = findSubarray(bodyBytes, crlfCrlf, startPart)
                if (doubleCrlfIndex != -1 && doubleCrlfIndex < endPart) {
                    val headerStart = if (bodyBytes[startPart] == '\r'.toByte() && bodyBytes[startPart + 1] == '\n'.toByte()) {
                        startPart + 2
                    } else {
                        startPart
                    }
                    val headersStr = String(bodyBytes, headerStart, doubleCrlfIndex - headerStart, Charsets.UTF_8)
                    
                    var bodyStart = doubleCrlfIndex + crlfCrlf.size
                    var bodyEnd = endPart
                    if (bodyEnd - 2 >= bodyStart && bodyBytes[bodyEnd - 2] == '\r'.toByte() && bodyBytes[bodyEnd - 1] == '\n'.toByte()) {
                        bodyEnd -= 2
                    }
                    
                    if (bodyStart <= bodyEnd) {
                        val body = bodyBytes.copyOfRange(bodyStart, bodyEnd)
                        parts.add(Part(headersStr, body))
                    }
                }
            }
            index = nextIndex
        }
        return parts
    }
}

data class Part(val headers: String, val body: ByteArray) {
    fun getName(): String? {
        val regex = """name="([^"]+)"""".toRegex()
        return regex.find(headers)?.groupValues?.getOrNull(1)
    }
    
    fun getFilename(): String? {
        val regex = """filename="([^"]+)"""".toRegex()
        return regex.find(headers)?.groupValues?.getOrNull(1)
    }
}

// ==========================================
// NETWORK SERVICE DISCOVERY HELPER
// ==========================================

class NsdHelper(private val context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null
    
    init {
        multicastLock = wifiManager.createMulticastLock("photo_sync_multicast_lock")
        multicastLock?.setReferenceCounted(true)
    }

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    
    private val resolveLock = ReentrantLock()
    private var isResolving = false
    private val pendingResolveQueue = mutableListOf<NsdServiceInfo>()
    
    companion object {
        const val SERVICE_TYPE = "_photosync._tcp."
    }

    fun registerService(port: Int, deviceName: String, onRegistered: (String) -> Unit) {
        try {
            if (multicastLock?.isHeld == false) multicastLock?.acquire()
        } catch (e: Exception) {
            Log.e("NsdHelper", "Failed to acquire multicast lock", e)
        }
        
        try {
            val serviceInfo = NsdServiceInfo().apply {
                serviceType = SERVICE_TYPE
                serviceName = deviceName
                setPort(if (port > 0) port else 9090)
            }
            
            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    onRegistered(info.serviceName)
                }
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.e("NsdHelper", "Registration failed: code $errorCode")
                }
                override fun onServiceUnregistered(info: NsdServiceInfo) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            }
            
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun unregisterService() {
        try {
            registrationListener?.let {
                nsdManager.unregisterService(it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        registrationListener = null
        try {
            if (discoveryListener == null && multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {}
    }

    fun discoverServices(
        onDeviceFound: (DiscoveredDevice) -> Unit,
        onDeviceLost: (String) -> Unit
    ) {
        stopDiscovery()
        try {
            if (multicastLock?.isHeld == false) multicastLock?.acquire()
        } catch (e: Exception) {
            Log.e("NsdHelper", "Failed to acquire multicast lock", e)
        }
        
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                Log.e("NsdHelper", "Discovery start failed code $errorCode")
                try {
                    nsdManager.stopServiceDiscovery(this)
                } catch (e: Exception) {}
            }
            
            override fun onStopDiscoveryFailed(type: String, errorCode: Int) {}
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType.contains("_photosync._tcp")) {
                    queueResolve(serviceInfo, onDeviceFound)
                }
            }
            
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                onDeviceLost(serviceInfo.serviceName)
            }
        }
        
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopDiscovery() {
        try {
            discoveryListener?.let {
                nsdManager.stopServiceDiscovery(it)
            }
        } catch (e: Exception) {}
        discoveryListener = null
        try {
            if (registrationListener == null && multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {}
        resolveLock.withLock {
            pendingResolveQueue.clear()
            isResolving = false
        }
    }
    
    private fun queueResolve(serviceInfo: NsdServiceInfo, onDeviceFound: (DiscoveredDevice) -> Unit) {
        resolveLock.withLock {
            if (isResolving) {
                pendingResolveQueue.add(serviceInfo)
            } else {
                isResolving = true
                resolveNext(serviceInfo, onDeviceFound)
            }
        }
    }
    
    private fun resolveNext(serviceInfo: NsdServiceInfo, onDeviceFound: (DiscoveredDevice) -> Unit) {
        try {
            nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.e("NsdHelper", "Resolve failed $errorCode for ${info.serviceName}")
                    processNextResolve(onDeviceFound)
                }
                
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val hostAddress = info.host?.hostAddress
                    if (hostAddress != null) {
                        onDeviceFound(DiscoveredDevice(info.serviceName, hostAddress, info.port))
                    }
                    processNextResolve(onDeviceFound)
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
            processNextResolve(onDeviceFound)
        }
    }
    
    private fun processNextResolve(onDeviceFound: (DiscoveredDevice) -> Unit) {
        resolveLock.withLock {
            if (pendingResolveQueue.isNotEmpty()) {
                val next = pendingResolveQueue.removeAt(0)
                resolveNext(next, onDeviceFound)
            } else {
                isResolving = false
            }
        }
    }
}

// ==========================================
// EMBEDDED LIGHTWEIGHT HTTPSERVER
// ==========================================

class LocalPhotoServer(
    private val context: Context,
    private val port: Int,
    private val onProgress: (filename: String, progress: Float) -> Unit,
    private val onPhotoReceived: (filename: String, uri: Uri?, success: Boolean) -> Unit
) {
    private var serverSocket: java.net.ServerSocket? = null
    private var isRunning = false
    private val serverExecutor = Executors.newSingleThreadExecutor()
    private val clientExecutor = Executors.newCachedThreadPool()
    private val failedAttempts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val blockedIps = java.util.concurrent.ConcurrentHashMap<String, Long>()
    
    init {
        try {
            serverSocket = java.net.ServerSocket(port)
            serverSocket?.soTimeout = 0 // Infinite accept block
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun start() {
        isRunning = true
        serverExecutor.execute {
            try {
                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    client.soTimeout = 30000 // 30s for client operations
                    clientExecutor.execute {
                        handleClient(client)
                    }
                }
            } catch (e: Exception) {
               // Normal on close
            }
        }
    }
    
    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (e: Exception) {}
        serverSocket = null
        serverExecutor.shutdown()
        clientExecutor.shutdown()
    }
    
    fun getPort(): Int = serverSocket?.localPort ?: port
    
    private fun handleClient(client: java.net.Socket) {
        val clientIp = client.inetAddress?.hostAddress ?: "unknown"
        val now = System.currentTimeMillis()
        val blockedUntil = blockedIps[clientIp] ?: 0L
        
        fun sendResponse(code: Int, message: String) {
            try {
                val out = client.getOutputStream()
                val responseBytes = message.toByteArray(Charsets.UTF_8)
                val statusText = when (code) {
                    200 -> "200 OK"
                    206 -> "206 Partial Content"
                    400 -> "400 Bad Request"
                    401 -> "401 Unauthorized"
                    403 -> "403 Forbidden"
                    405 -> "405 Method Not Allowed"
                    409 -> "409 Conflict"
                    422 -> "422 Unprocessable Entity"
                    507 -> "507 Insufficient Storage"
                    else -> "$code Internal Server Error"
                }
                out.write("HTTP/1.1 $statusText\r\nConnection: close\r\nContent-Length: ${responseBytes.size}\r\n\r\n".toByteArray(Charsets.UTF_8))
                out.write(responseBytes)
                out.flush()
            } catch (e: Exception) { }
        }

        if (blockedUntil > now) {
            sendResponse(403, "Too many failed attempts. Temporary IP block active.")
            return
        }

        client.use { s ->
            try {
                val inputStream = s.getInputStream()
                val headersBuffer = java.io.ByteArrayOutputStream()
                var contentLength = -1
                var contentType = ""
                var method = ""
                var path = ""
                
                val crlfcrlf = byteArrayOf(13, 10, 13, 10)
                var matchCount = 0
                
                while (true) {
                    val b = inputStream.read()
                    if (b == -1) break
                    headersBuffer.write(b)
                    if (b.toByte() == crlfcrlf[matchCount]) {
                        matchCount++
                        if (matchCount == 4) break
                    } else {
                        matchCount = if (b.toByte() == crlfcrlf[0]) 1 else 0
                    }
                }
                
                val headersText = String(headersBuffer.toByteArray(), Charsets.UTF_8)
                val headerLines = headersText.split("\r\n")
                if (headerLines.isEmpty()) {
                    sendResponse(400, "Bad Request")
                    return
                }
                val firstLine = headerLines[0].split(" ")
                if (firstLine.size >= 2) {
                    method = firstLine[0]
                    path = firstLine[1]
                }
                
                var senderPin = ""
                var filename = "photo_${System.currentTimeMillis()}.jpg"
                var hash = ""
                var fileOffset = 0L
                var fileTotalSize = 0L
                var isGzip = false
                var senderDeviceName = "Sender_Device"

                for (line in headerLines) {
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: -1
                    }
                    if (line.startsWith("Content-Type:", ignoreCase = true)) {
                        contentType = line.substringAfter(":").trim()
                    }
                    if (line.startsWith("X-Pairing-PIN:", ignoreCase = true)) {
                        senderPin = line.substringAfter(":").trim()
                    }
                    if (line.startsWith("X-File-Name:", ignoreCase = true)) {
                        filename = Uri.decode(line.substringAfter(":").trim())
                    }
                    if (line.startsWith("X-File-Hash:", ignoreCase = true)) {
                        hash = line.substringAfter(":").trim()
                    }
                    if (line.startsWith("X-File-Offset:", ignoreCase = true)) {
                        fileOffset = line.substringAfter(":").trim().toLongOrNull() ?: 0L
                    }
                    if (line.startsWith("X-File-Total-Size:", ignoreCase = true)) {
                        fileTotalSize = line.substringAfter(":").trim().toLongOrNull() ?: 0L
                    }
                    if (line.startsWith("Content-Encoding:", ignoreCase = true) || line.startsWith("X-Content-Encoding:", ignoreCase = true)) {
                        isGzip = line.substringAfter(":").trim().contains("gzip", ignoreCase = true)
                    }
                    if (line.startsWith("X-Device-Name:", ignoreCase = true)) {
                        senderDeviceName = line.substringAfter(":").trim()
                    }
                }

                // Check Pairing PIN
                val expectedPin = LocalServerService.pairingPin.value
                if (senderPin != expectedPin) {
                    val currentAttempts = (failedAttempts[clientIp] ?: 0) + 1
                    failedAttempts[clientIp] = currentAttempts
                    if (currentAttempts >= 3) {
                        blockedIps[clientIp] = System.currentTimeMillis() + 300000L // 5 minutes block
                        failedAttempts[clientIp] = 0 // reset
                    }
                    sendResponse(401, "Pairing authentication failed. Incorrect PIN.")
                    return
                } else {
                    failedAttempts.remove(clientIp)
                }

                // Register paired sender device
                try {
                    val db = SyncDatabase.getDatabase(context)
                    val pDao = db.pairedDeviceDao()
                    kotlinx.coroutines.runBlocking {
                        pDao.insertDevice(
                            PairedDevice(
                                deviceId = clientIp + "_" + senderDeviceName,
                                deviceName = senderDeviceName,
                                lastKnownIp = clientIp,
                                lastKnownPort = s.port,
                                pinToken = expectedPin
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e("LocalPhotoServer", "Device registry failure", e)
                }

                // Handle Ping handshakes
                if (method == "GET" && path.contains("/api/v1/ping")) {
                    sendResponse(200, "Pairing valid.")
                    return
                }

                val partialDir = java.io.File(context.cacheDir, "partial_uploads")
                if (!partialDir.exists()) partialDir.mkdirs()

                // GET upload status check
                if (method == "GET" && path.contains("/api/v1/upload-status")) {
                    if (hash.isEmpty()) {
                        sendResponse(400, "Missing X-File-Hash header")
                        return
                    }
                    val partialFile = java.io.File(partialDir, hash)
                    val existingSize = if (partialFile.exists()) partialFile.length() else 0L
                    
                    try {
                        val out = s.getOutputStream()
                        val responseStr = existingSize.toString()
                        val responseBytes = responseStr.toByteArray(Charsets.UTF_8)
                        out.write("HTTP/1.1 200 OK\r\nConnection: close\r\nX-Bytes-Received: $existingSize\r\nContent-Length: ${responseBytes.size}\r\n\r\n".toByteArray(Charsets.UTF_8))
                        out.write(responseBytes)
                        out.flush()
                    } catch (e: Exception) {}
                    return
                }

                if (method != "POST" || !path.contains("/api/v1/upload")) {
                     sendResponse(405, "Method Not Allowed")
                     return
                }
                
                onProgress("Incoming Upload...", 0.1f)
                
                if (contentLength < 0) {
                     sendResponse(400, "Invalid Content-Length")
                     return
                }

                if (fileTotalSize <= 0L) {
                    fileTotalSize = contentLength.toLong()
                }

                // Insufficient Storage Check
                try {
                    val stat = StatFs(Environment.getExternalStorageDirectory().path)
                    val bytesAvailable = stat.availableBytes
                    if (fileTotalSize > bytesAvailable) {
                        sendResponse(507, "Insufficient storage space.")
                        return
                    }
                } catch (e: Exception) {
                    Log.e("LocalPhotoServer", "Failed storage check", e)
                }
                
                // Duplicate Check & Filename Collision Resolution
                onProgress("Resolving Collision...", 0.3f)
                val exists = isFileExisting(context, filename)
                if (exists) {
                    val isDuplicate = isExactDuplicateExisting(context, filename, fileTotalSize)
                    if (isDuplicate) {
                        // Drain the input stream
                        val buffer = ByteArray(65536)
                        var readTotal = 0L
                        while (readTotal < contentLength) {
                            val toRead = minOf(65536L, contentLength.toLong() - readTotal).toInt()
                            val readCount = inputStream.read(buffer, 0, toRead)
                            if (readCount == -1) break
                            readTotal += readCount
                        }
                        sendResponse(409, "File already backed up.")
                        onPhotoReceived(filename, null, true)
                        return
                    } else {
                        filename = resolveUniqueFilename(context, filename)
                    }
                }
                
                onProgress("Saving Content...", 0.4f)
                val partialFile = java.io.File(partialDir, hash.ifEmpty { "temp_" + System.currentTimeMillis() })
                
                if (fileOffset == 0L && partialFile.exists()) {
                    partialFile.delete()
                }

                val fos = java.io.FileOutputStream(partialFile, fileOffset > 0L)
                fos.use { output ->
                    val streamSource = if (isGzip) java.util.zip.GZIPInputStream(inputStream) else inputStream
                    val buffer = ByteArray(65536)
                    var readTotal = 0L
                    var lastReportProgress = -1f
                    while (readTotal < contentLength) {
                        val toRead = minOf(65536L, contentLength.toLong() - readTotal).toInt()
                        val readCount = streamSource.read(buffer, 0, toRead)
                        if (readCount == -1) break
                        output.write(buffer, 0, readCount)
                        readTotal += readCount
                        
                        val partialSizeCombined = partialFile.length()
                        val progressVal = 0.4f + 0.6f * (partialSizeCombined.toFloat() / fileTotalSize.toFloat()).coerceIn(0f, 1f)
                        if (progressVal - lastReportProgress >= 0.05f || progressVal >= 1f) {
                            lastReportProgress = progressVal
                            onProgress("Receiving: $filename", progressVal)
                        }
                    }
                }

                val currentSize = partialFile.length()
                if (currentSize >= fileTotalSize) {
                    // Fully received! Copy to Scoped Storage
                    val isVideo = filename.lowercase().endsWith(".mp4") ||
                                  filename.lowercase().endsWith(".mkv") ||
                                  filename.lowercase().endsWith(".3gp") ||
                                  filename.lowercase().endsWith(".avi") ||
                                  filename.lowercase().endsWith(".mov")
                    
                    val mimeType = if (isVideo) "video/mp4" else "image/jpeg"
                    val parentCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    } else {
                        if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    }

                    val resolver = context.contentResolver
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, if (isVideo) "Movies/BackupSync" else "DCIM/BackupSync")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                    }

                    val itemUri = resolver.insert(parentCollection, contentValues)
                    if (itemUri == null) {
                        sendResponse(500, "Failed to register item in MediaStore.")
                        onPhotoReceived(filename, null, false)
                        return
                    }

                    try {
                        resolver.openOutputStream(itemUri)?.use { out ->
                            java.io.FileInputStream(partialFile).use { ins ->
                                val copyBuf = ByteArray(65536)
                                var cRead: Int
                                while (ins.read(copyBuf).also { cRead = it } != -1) {
                                    out.write(copyBuf, 0, cRead)
                                }
                            }
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            contentValues.clear()
                            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                            resolver.update(itemUri, contentValues, null, null)
                        }
                        
                        partialFile.delete()

                        sendResponse(200, "Stored OK.")
                        onPhotoReceived(filename, itemUri, true)

                        // Save SUCCESS transfer state log
                        try {
                            val db = SyncDatabase.getDatabase(context)
                            kotlinx.coroutines.runBlocking {
                                db.syncHistoryDao().insertRecord(
                                    SyncHistoryRecord(
                                        filename = filename,
                                        fileHash = hash,
                                        sizeBytes = fileTotalSize,
                                        direction = "RECEIVER",
                                        targetDeviceName = senderDeviceName,
                                        status = "SUCCESS",
                                        bytesTransferred = fileTotalSize
                                    )
                                )
                            }
                        } catch (e: Exception) {}

                    } catch (e: Exception) {
                        resolver.delete(itemUri, null, null)
                        sendResponse(500, "Storage stream error: ${e.message}")
                        onPhotoReceived(filename, null, false)

                        // Log failure in background db
                        try {
                            val db = SyncDatabase.getDatabase(context)
                            kotlinx.coroutines.runBlocking {
                                db.syncHistoryDao().insertRecord(
                                    SyncHistoryRecord(
                                        filename = filename,
                                        fileHash = hash,
                                        sizeBytes = fileTotalSize,
                                        direction = "RECEIVER",
                                        targetDeviceName = senderDeviceName,
                                        status = "FAILED",
                                        bytesTransferred = partialFile.length()
                                    )
                                )
                            }
                        } catch (ex: Exception) {}
                    }
                } else {
                    sendResponse(206, "Chunk Stored Partial")
                    onPhotoReceived(filename, null, false)

                    // Log partial state
                    try {
                        val db = SyncDatabase.getDatabase(context)
                        kotlinx.coroutines.runBlocking {
                            db.syncHistoryDao().insertRecord(
                                SyncHistoryRecord(
                                    filename = filename,
                                    fileHash = hash,
                                    sizeBytes = fileTotalSize,
                                    direction = "RECEIVER",
                                    targetDeviceName = senderDeviceName,
                                    status = "PARTIAL",
                                    bytesTransferred = currentSize
                                )
                            )
                        }
                    } catch (ex: Exception) {}
                }
                
            } catch (e: Exception) {
                e.printStackTrace()
                sendResponse(500, "Client handling exception: ${e.localizedMessage}")
                onPhotoReceived("transfer_error", null, false)
            }
        }
    }
    
    private fun getBytesHash(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun isFileExisting(context: Context, filename: String): Boolean {
        val resolver = context.contentResolver
        val queryUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf(filename, "DCIM/BackupSync%")
        try {
            resolver.query(queryUri, projection, selection, selectionArgs, null)?.use { cursor ->
                return cursor.count > 0
            }
        } catch (e: Exception) {
            Log.e("LocalPhotoServer", "isFileExisting fail", e)
        }
        return false
    }

    private fun isExactDuplicateExisting(context: Context, filename: String, length: Long): Boolean {
        val resolver = context.contentResolver
        val queryUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.MediaColumns.SIZE)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf(filename, "DCIM/BackupSync%")
        try {
            resolver.query(queryUri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    if (sizeCol != -1) {
                        return cursor.getLong(sizeCol) == length
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("LocalPhotoServer", "isExactDuplicateExisting fail", e)
        }
        return false
    }

    private fun resolveUniqueFilename(context: Context, originalFilename: String): String {
        var newFilename = originalFilename
        val dotIndex = originalFilename.lastIndexOf('.')
        val baseName = if (dotIndex != -1) originalFilename.substring(0, dotIndex) else originalFilename
        val extension = if (dotIndex != -1) originalFilename.substring(dotIndex) else ""
        
        var counter = 1
        while (isFileExisting(context, newFilename)) {
            newFilename = "${baseName}_$counter$extension"
            counter++
        }
        return newFilename
    }

    private fun saveImageToMediaStore(context: Context, filename: String, bytes: ByteArray, mimeType: String): Uri? {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/BackupSync")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        
        val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        
        val itemUri = resolver.insert(collectionUri, contentValues) ?: return null
        
        return try {
            resolver.openOutputStream(itemUri)?.use { output ->
                output.write(bytes)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(itemUri, contentValues, null, null)
            }
            itemUri
        } catch (e: Exception) {
            resolver.delete(itemUri, null, null)
            null
        }
    }
}

// ==========================================
// VIEWMODEL IMPLEMENTATION (SURVIVES CONFIG CHANGES)
// ==========================================

class PhotoSyncViewModel(context: Context) : ViewModel() {
    private val appContext = context.applicationContext
    private val nsdHelper = NsdHelper(appContext)
    private var localServer: LocalPhotoServer? = null
    
    // UI Flows
    private val _currentRole = MutableStateFlow<String>("NONE") // NONE, SENDER, RECEIVER
    val currentRole: StateFlow<String> = _currentRole.asStateFlow()
    
    private val _discoveredDevices = MutableStateFlow<Map<String, DiscoveredDevice>>(emptyMap())
    val discoveredDevices: StateFlow<Map<String, DiscoveredDevice>> = _discoveredDevices.asStateFlow()
    
    private val _selectedDevice = MutableStateFlow<DiscoveredDevice?>(null)
    val selectedDevice: StateFlow<DiscoveredDevice?> = _selectedDevice.asStateFlow()
    
    private val _selectedPhotos = MutableStateFlow<List<PhotoPayload>>(emptyList())
    val selectedPhotos: StateFlow<List<PhotoPayload>> = _selectedPhotos.asStateFlow()
    
    private val _deletionOption = MutableStateFlow(DeletionOption.KEEP)
    val deletionOption: StateFlow<DeletionOption> = _deletionOption.asStateFlow()
    
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()
    
    // Receiver Specific State
    val isServerRunning: StateFlow<Boolean> = LocalServerService.isServiceRunning
    
    private val _serverIp = MutableStateFlow<String>("Unknown Check Wi-Fi")
    val serverIp: StateFlow<String> = _serverIp.asStateFlow()
    
    val serverPort: StateFlow<Int> = LocalServerService.serverPort
    
    val receivedPhotos: StateFlow<List<Pair<String, Uri>>> = LocalServerService.receivedPhotos
    
    val receiverProgressName: StateFlow<String> = LocalServerService.receiverProgressName
    val receiverProgressVal: StateFlow<Float> = LocalServerService.receiverProgressVal

    // Sender Specific Pairing state
    private val _senderPairPin = MutableStateFlow("")
    val senderPairPin: StateFlow<String> = _senderPairPin.asStateFlow()
    
    private val _rememberAutoPair = MutableStateFlow(false)
    val rememberAutoPair: StateFlow<Boolean> = _rememberAutoPair.asStateFlow()
    
    private val _autoSyncEnabled = MutableStateFlow(false)
    val autoSyncEnabled: StateFlow<Boolean> = _autoSyncEnabled.asStateFlow()

    fun setSenderPairPin(pin: String) {
        _senderPairPin.value = pin
    }
    
    fun setRememberAutoPair(enabled: Boolean) {
        _rememberAutoPair.value = enabled
        val prefs = appContext.getSharedPreferences("PhotoSyncPrefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("remember_auto_pair", enabled).apply()
    }
    
    fun setAutoSyncEnabled(enabled: Boolean) {
        _autoSyncEnabled.value = enabled
        val prefs = appContext.getSharedPreferences("PhotoSyncPrefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("auto_sync_enabled", enabled).apply()
        
        if (enabled) {
            AutoSyncJobService.scheduleAutoSync(appContext)
        } else {
            AutoSyncJobService.cancelAutoSync(appContext)
        }
    }

    // Folder Backup Specific State
    private val _hasStoragePermission = MutableStateFlow<Boolean?>(null)
    val hasStoragePermission: StateFlow<Boolean?> = _hasStoragePermission.asStateFlow()

    private val _globalError = MutableStateFlow<String?>(null)
    val globalError: StateFlow<String?> = _globalError.asStateFlow()
    
    fun dismissGlobalError() {
        _globalError.value = null
    }

    private val _availableFolders = MutableStateFlow<List<MediaFolder>>(emptyList())
    val availableFolders: StateFlow<List<MediaFolder>> = _availableFolders.asStateFlow()

    private val _selectedFolderIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedFolderIds: StateFlow<Set<String>> = _selectedFolderIds.asStateFlow()
    
    private val _isScanningFolders = MutableStateFlow(false)
    val isScanningFolders: StateFlow<Boolean> = _isScanningFolders.asStateFlow()

    fun checkAndLoadFolders() {
        val hasPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        _hasStoragePermission.value = hasPerm
        if (hasPerm) {
            loadLocalFolders()
        }
    }

    fun loadLocalFolders() {
        if (_isScanningFolders.value) return
        _isScanningFolders.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val foldersMap = mutableMapOf<String, Pair<String, Int>>()
            val folderFirstUriMap = mutableMapOf<String, Uri>()
            
            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media._ID
            )
            
            try {
                appContext.contentResolver.query(
                    uri,
                    projection,
                    null,
                    null,
                    "${MediaStore.Images.Media.DATE_TAKEN} DESC"
                )?.use { cursor ->
                    val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
                    val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    
                    while (cursor.moveToNext()) {
                        val bucketId = cursor.getString(bucketIdCol) ?: "unknown"
                        val bucketName = cursor.getString(bucketNameCol) ?: "Unknown Folder"
                        val id = cursor.getLong(idCol)
                        val photoUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                        
                        val current = foldersMap[bucketId]
                        if (current == null) {
                            foldersMap[bucketId] = Pair(bucketName, 1)
                            folderFirstUriMap[bucketId] = photoUri
                        } else {
                            foldersMap[bucketId] = Pair(current.first, current.second + 1)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ViewModel", "Failed to query folders", e)
                _globalError.value = "Failed to query device folders: ${e.localizedMessage}"
            }
            
            val list = foldersMap.map { (bucketId, pair) ->
                MediaFolder(
                    id = bucketId,
                    name = pair.first,
                    count = pair.second,
                    firstPhotoUri = folderFirstUriMap[bucketId]
                )
            }.sortedByDescending { it.count }
            
            _availableFolders.value = list
            _isScanningFolders.value = false
        }
    }

    fun toggleFolderSelection(folderId: String) {
        val currentSelected = _selectedFolderIds.value.toMutableSet()
        if (currentSelected.contains(folderId)) {
            currentSelected.remove(folderId)
        } else {
            currentSelected.add(folderId)
        }
        _selectedFolderIds.value = currentSelected
        
        importPhotosFromFolders(currentSelected)
    }

    fun importPhotosFromFolders(folderIds: Set<String>) {
        if (folderIds.isEmpty()) {
            _selectedPhotos.value = emptyList()
            return
        }
        
        _syncState.value = SyncState.Preparation
        viewModelScope.launch(Dispatchers.IO) {
            val payloads = mutableListOf<PhotoPayload>()
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            
            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE
            )
            
            val selection = folderIds.joinToString(separator = " OR ") { "${MediaStore.Images.Media.BUCKET_ID} = ?" }
            val selectionArgs = folderIds.toTypedArray()
            
            try {
                appContext.contentResolver.query(
                    uri,
                    projection,
                    selection,
                    selectionArgs,
                    "${MediaStore.Images.Media.DATE_TAKEN} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                    
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val filename = cursor.getString(nameCol) ?: "IMG_${System.currentTimeMillis()}.jpg"
                        val size = cursor.getLong(sizeCol)
                        val photoUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                        
                        digest.reset()
                        try {
                            appContext.contentResolver.openInputStream(photoUri)?.use { stream ->
                                var read = stream.read(buffer)
                                while (read != -1) {
                                    digest.update(buffer, 0, read)
                                    read = stream.read(buffer)
                                }
                            }
                            val hash = digest.digest().joinToString("") { "%02x".format(it) }
                            payloads.add(PhotoPayload(photoUri, filename, size, hash))
                        } catch (e: Exception) {
                            Log.e("ViewModel", "Hashing failed for $filename: ${e.localizedMessage}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ViewModel", "Failed to query files in folders", e)
                _globalError.value = "Failed to load photos: ${e.localizedMessage}"
            }
            
            _selectedPhotos.value = payloads
            _syncState.value = SyncState.Idle
        }
    }

    private val _isWifiConnected = MutableStateFlow(true)
    val isWifiConnected: StateFlow<Boolean> = _isWifiConnected.asStateFlow()

    init {
        updateLocalIp()
        registerNetworkCallback()
        checkForLastCrash()
        
        val prefs = appContext.getSharedPreferences("PhotoSyncPrefs", Context.MODE_PRIVATE)
        _autoSyncEnabled.value = prefs.getBoolean("auto_sync_enabled", false)
        _rememberAutoPair.value = prefs.getBoolean("remember_auto_pair", false)
    }

    private fun getWifiSsid(context: Context): String {
        try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                val info = wifiManager?.connectionInfo
                return info?.ssid?.removePrefix("\"")?.removeSuffix("\"") ?: "<unknown>"
            }
        } catch (e: Exception) {
            android.util.Log.e("PhotoSync", "Failed to retrieve SSID", e)
        }
        return "<unknown>"
    }

    fun setRole(role: String) {
        _currentRole.value = role
        // Cleanup based on roles switching
        if (role == "SENDER") {
            stopReceiver()
            startNsdDiscovery()
        } else if (role == "RECEIVER") {
            stopNsdDiscovery()
            startReceiver()
        } else {
            stopReceiver()
            stopNsdDiscovery()
        }
    }

    private fun registerNetworkCallback() {
        try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val request = android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    _isWifiConnected.value = true
                    updateLocalIp()
                }
                override fun onLost(network: android.net.Network) {
                    _isWifiConnected.value = false
                    _serverIp.value = "No Active Wi-Fi"
                }
            })
        } catch (e: Exception) {
            Log.e("ViewModel", "Failed to register network callback", e)
        }
    }

    private fun checkForLastCrash() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = java.io.File(appContext.cacheDir, "crash_log.txt")
                if (file.exists()) {
                    val lines = file.readLines()
                    if (lines.size >= 2) {
                        val timestamp = lines.getOrNull(0)?.toLongOrNull() ?: 0L
                        val errorMsg = lines.getOrNull(1) ?: "Unknown crash context"
                        val stack = lines.drop(2).joinToString("\n")
                        val formattedTime = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(timestamp))
                        _globalError.value = "Previous System Exception Detected ($formattedTime):\n$errorMsg\n\n$stack"
                    }
                    file.delete()
                }
            } catch (e: Exception) {
                Log.e("ViewModel", "Failed to check crash file", e)
            }
        }
    }

    fun updateLocalIp() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val interfacesOpt = NetworkInterface.getNetworkInterfaces()
                val interfaces = if (interfacesOpt != null) Collections.list(interfacesOpt) else emptyList()
                var foundIp = "No Active Wi-Fi"
                for (netInterface in interfaces) {
                    val addresses = Collections.list(netInterface.inetAddresses)
                    for (address in addresses) {
                        if (!address.isLoopbackAddress) {
                            val ip = address.hostAddress ?: ""
                            val isIPv4 = ip.indexOf(':') < 0
                            if (isIPv4 && (netInterface.name.startsWith("wlan") || netInterface.name.startsWith("ap"))) {
                                foundIp = ip
                                break
                            }
                        }
                    }
                }
                _serverIp.value = foundIp
            } catch (e: Exception) {
                _serverIp.value = "127.0.0.1"
            }
        }
    }

    // --- SENDER ACTIONS ---

    private fun startNsdDiscovery() {
        _discoveredDevices.value = emptyMap()
        nsdHelper.discoverServices(
            onDeviceFound = { device ->
                val current = _discoveredDevices.value.toMutableMap()
                current[device.name] = device
                _discoveredDevices.value = current
                
                val prefs = appContext.getSharedPreferences("PhotoSyncPrefs", Context.MODE_PRIVATE)
                if (prefs.getBoolean("auto_pair_enabled", false)) {
                    val prefName = prefs.getString("auto_pair_name", "")
                    val prefSsid = prefs.getString("auto_pair_ssid", "")
                    val prefPin = prefs.getString("auto_pair_pin", "")
                    
                    if (prefName == device.name) {
                        val currentSsid = getWifiSsid(appContext)
                        if (currentSsid == prefSsid) {
                            _selectedDevice.value = device
                            _senderPairPin.value = prefPin ?: ""
                        }
                    }
                }
            },
            onDeviceLost = { name ->
                val current = _discoveredDevices.value.toMutableMap()
                current.remove(name)
                _discoveredDevices.value = current
                if (_selectedDevice.value?.name == name) {
                    _selectedDevice.value = null
                }
            }
        )
    }

    private fun stopNsdDiscovery() {
        nsdHelper.stopDiscovery()
        _discoveredDevices.value = emptyMap()
    }

    fun selectDevice(device: DiscoveredDevice) {
        _selectedDevice.value = device
    }

    fun setDeletionOption(option: DeletionOption) {
        _deletionOption.value = option
    }

    fun clearSelections() {
        _selectedPhotos.value = emptyList()
        _selectedDevice.value = null
        _syncState.value = SyncState.Idle
        _selectedFolderIds.value = emptySet()
    }

    fun importSelectedUris(uris: List<Uri>) {
        _syncState.value = SyncState.Preparation
        viewModelScope.launch(Dispatchers.IO) {
            val payloads = mutableListOf<PhotoPayload>()
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            
            for (uri in uris) {
                var filename = "IMG_${System.currentTimeMillis()}.jpg"
                var size = 0L
                appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            filename = cursor.getString(nameIndex) ?: filename
                        }
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex != -1) {
                            size = cursor.getLong(sizeIndex)
                        }
                    }
                }
                
                // File Hashing Integrity Check
                digest.reset()
                try {
                    appContext.contentResolver.openInputStream(uri)?.use { stream ->
                        var read = stream.read(buffer)
                        while (read != -1) {
                            digest.update(buffer, 0, read)
                            read = stream.read(buffer)
                        }
                    }
                    val hash = digest.digest().joinToString("") { "%02x".format(it) }
                    payloads.add(PhotoPayload(uri, filename, size, hash))
                } catch (e: Exception) {
                    Log.e("ViewModel", "Hashing failed: ${e.localizedMessage}")
                }
            }
            _selectedPhotos.value = payloads
            _syncState.value = SyncState.Idle
        }
    }

    private var transferWakeLock: android.os.PowerManager.WakeLock? = null
    private var transferWifiLock: android.net.wifi.WifiManager.WifiLock? = null

    private fun releaseTransferLocks() {
        try { transferWakeLock?.release() } catch(e: Exception){}
        try { transferWifiLock?.release() } catch(e: Exception){}
    }

    fun executeTransfer(onRequireDeletionRequest: (List<Uri>) -> Unit) {
        val target = _selectedDevice.value ?: return
        val photos = _selectedPhotos.value
        if (photos.isEmpty()) return
        
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        transferWakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "PhotoSync::TransferWakeLock")
        transferWakeLock?.acquire()
        
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        transferWifiLock = wifiManager.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PhotoSync::TransferWifiLock")
        transferWifiLock?.acquire()
        
        _syncState.value = SyncState.Transferring(
            currentIndex = 0,
            totalCount = photos.size,
            currentFileName = "Performing pairing verification...",
            progress = 0f
        )
        
        viewModelScope.launch(Dispatchers.IO) {
            val client = OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .writeTimeout(0, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .build()
            
            // 1. PIN Handshake (Requirement 3 PIN security)
            var handshakeSuccess = false
            var handshakeErrorMsg = "Pairing handshaking failed."
            try {
                val pingRequest = Request.Builder()
                    .url("http://${target.ip}:${target.port}/api/v1/ping")
                    .get()
                    .addHeader("X-Pairing-PIN", _senderPairPin.value)
                    .build()
                client.newCall(pingRequest).execute().use { resp ->
                    if (resp.code == 200) {
                        handshakeSuccess = true
                        
                        // Save last target device credentials for auto-sync
                        val prefs = appContext.getSharedPreferences("PhotoSyncPrefs", Context.MODE_PRIVATE)
                        val editor = prefs.edit()
                        editor.putString("last_target_ip", target.ip)
                        editor.putInt("last_target_port", target.port)
                        editor.putString("last_target_pin", _senderPairPin.value)
                        
                        if (_rememberAutoPair.value) {
                            editor.putBoolean("auto_pair_enabled", true)
                            editor.putString("auto_pair_name", target.name)
                            editor.putString("auto_pair_pin", _senderPairPin.value)
                            editor.putString("auto_pair_ssid", getWifiSsid(appContext))
                        } else {
                            editor.putBoolean("auto_pair_enabled", false)
                        }
                        editor.apply()
                            
                    } else if (resp.code == 401) {
                        handshakeErrorMsg = "Incorrect pairing PIN entered."
                    } else {
                        handshakeErrorMsg = "Response code ${resp.code} from server."
                    }
                }
            } catch (e: Exception) {
                handshakeErrorMsg = "Failed to reach target: ${e.localizedMessage}"
            }
            
            if (!handshakeSuccess) {
                _syncState.value = SyncState.Failed("Sync Handshake Aborted: $handshakeErrorMsg")
                releaseTransferLocks()
                return@launch
            }

            val successfullyTransferredUris = mutableListOf<Uri>()
            var anyFailure = false
            var failureMessage = ""

            for ((index, payload) in photos.withIndex()) {
                _syncState.value = SyncState.Transferring(
                    currentIndex = index,
                    totalCount = photos.size,
                    currentFileName = payload.filename,
                    progress = 0.05f
                )
                
                try {
                    val streamSize = payload.size
                    
                    val fileBody = object : RequestBody() {
                        override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
                        override fun contentLength() = streamSize
                        override fun writeTo(sink: BufferedSink) {
                            appContext.contentResolver.openInputStream(payload.uri)?.use { stream ->
                                val buffer = ByteArray(65536)
                                var offset = 0L
                                var lastReportProgress = -1f
                                var read: Int
                                while (stream.read(buffer).also { read = it } != -1) {
                                    sink.write(buffer, 0, read)
                                    offset += read
                                    val progressVal = if (streamSize > 0) (offset.toFloat() / streamSize) else 1f
                                    if (progressVal - lastReportProgress >= 0.05f || progressVal >= 1.0f) {
                                        lastReportProgress = progressVal
                                        _syncState.value = SyncState.Transferring(
                                            currentIndex = index,
                                            totalCount = photos.size,
                                            currentFileName = payload.filename,
                                            progress = progressVal
                                        )
                                    }
                                }
                            } ?: run {
                                throw Exception("Cannot access local photo image stream.")
                            }
                        }
                    }

                    val request = Request.Builder()
                        .url("http://${target.ip}:${target.port}/api/v1/upload")
                        .post(fileBody)
                        .addHeader("X-Pairing-PIN", _senderPairPin.value)
                        .addHeader("X-File-Name", Uri.encode(payload.filename))
                        .addHeader("X-File-Hash", payload.hash)
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            successfullyTransferredUris.add(payload.uri)
                        } else if (response.code == 409) {
                            // Duplicate file (Requirement 3) - Already backed up securely, count as success
                            successfullyTransferredUris.add(payload.uri)
                        } else {
                            anyFailure = true
                            failureMessage = when (response.code) {
                                401 -> "Pairing signature unauthorized."
                                422 -> "Integrity Check Failed: Checksum checksum misalign."
                                507 -> "Insufficient storage space on recipient."
                                else -> "Destination rejected write with error: ${response.code}"
                            }
                            break
                        }
                    }
                    
                } catch (e: Exception) {
                    anyFailure = true
                    failureMessage = e.localizedMessage ?: "Network interaction error"
                    break
                }
            }

            if (anyFailure && successfullyTransferredUris.isEmpty()) {
                _syncState.value = SyncState.Failed("Sync aborted: $failureMessage")
            } else {
                // Return success state, and store the actual list of successfully backed up photos
                _syncState.value = SyncState.Success(
                    transferredCount = successfullyTransferredUris.size,
                    transferredUris = successfullyTransferredUris,
                    partialError = if (anyFailure) failureMessage else null
                )
            }
            releaseTransferLocks()
        }
    }

    fun initiateDeferredDeletion(uris: List<Uri>, onRequireDeletionRequest: (List<Uri>) -> Unit) {
        _syncState.value = SyncState.Deleting(uris)
        viewModelScope.launch(Dispatchers.Main) {
            onRequireDeletionRequest(uris)
        }
    }

    fun finalizeDeletionCompleted(resultSuccess: Boolean, targetCount: Int) {
        if (resultSuccess) {
            _syncState.value = SyncState.Success(0, emptyList())
            _selectedPhotos.value = emptyList() // clear on success delete
        } else {
            // Keep remaining photos in view
            _syncState.value = SyncState.Success(targetCount, emptyList())
        }
    }

    // --- RECEIVER ACTIONS ---

    private fun startReceiver() {
        stopReceiver()
        updateLocalIp()
        LocalServerService.clearReceivedPhotos()
        
        val generatedPin = (1000..9999).random().toString()
        val intent = Intent(appContext, LocalServerService::class.java).apply {
            action = "START"
            putExtra("pin", generatedPin)
        }
        try {
            appContext.startService(intent)
        } catch (e: Exception) {
            Log.e("ViewModel", "Failed to start LocalServerService", e)
            _globalError.value = "Failed to start local receiver service: ${e.localizedMessage}"
        }
    }

    private fun stopReceiver() {
        val intent = Intent(appContext, LocalServerService::class.java).apply {
            action = "STOP"
        }
        try {
            appContext.stopService(intent)
        } catch (e: Exception) {
            Log.e("ViewModel", "Failed to stop LocalServerService", e)
        }
    }

    override fun onCleared() {
        stopReceiver()
        stopNsdDiscovery()
        super.onCleared()
    }
}

// ==========================================
// ACTIVITY LIFECYCLE CONTROLLER
// ==========================================

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: PhotoSyncViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Setup Uncaught Incident Exception Logger to intercept any VM execution crashes
        try {
            val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                Log.e("PhotoSync", "UNCAUGHT CRASH IN THREAD: ${thread.name}", throwable)
                try {
                    val file = java.io.File(applicationContext.cacheDir, "crash_log.txt")
                    file.writeText("${System.currentTimeMillis()}\n${throwable.localizedMessage ?: "Unknown Application Error"}\n${throwable.stackTraceToString()}")
                } catch (e: Exception) {
                    Log.e("PhotoSync", "Failed to write crash file", e)
                }
                oldHandler?.uncaughtException(thread, throwable)
            }
        } catch (e: Exception) {
            Log.e("PhotoSync", "Failed to register custom crash handler", e)
        }

        viewModel = PhotoSyncViewModel(this)
        enableEdgeToEdge()
        
        setContent {
            MyApplicationTheme {
                // Media Deletion Request Launcher (Scoped Storage Android 11+ compliant)
                val deleteLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartIntentSenderForResult()
                ) { result ->
                    val currentState = viewModel.syncState.value
                    if (currentState is SyncState.Deleting) {
                        if (result.resultCode == Activity.RESULT_OK) {
                            viewModel.finalizeDeletionCompleted(true, currentState.uris.size)
                        } else {
                            viewModel.finalizeDeletionCompleted(false, currentState.uris.size)
                        }
                    }
                }

                val onTriggerUrisDeletion = { uris: List<Uri> ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            val pendingIntent = MediaStore.createDeleteRequest(contentResolver, uris)
                            val intentSenderRequest = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                            deleteLauncher.launch(intentSenderRequest)
                        } catch (e: Exception) {
                            e.printStackTrace()
                            viewModel.finalizeDeletionCompleted(false, uris.size)
                        }
                    } else {
                        // Fallback delete for APIs below Q
                        var successCount = 0
                        for (uri in uris) {
                            try {
                                val deleteRow = contentResolver.delete(uri, null, null)
                                if (deleteRow > 0) successCount++
                            } catch (e: Exception) {}
                        }
                        viewModel.finalizeDeletionCompleted(true, successCount)
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PhotoSyncAppScreen(viewModel, onTriggerUrisDeletion)
                }
            }
        }
    }
}

// ==========================================
// JETPACK COMPOSE COMPOSABLES UI
// ==========================================

@Composable
fun PhotoSyncAppScreen(
    viewModel: PhotoSyncViewModel,
    onTriggerDeletions: (List<Uri>) -> Unit
) {
    val currentRole by viewModel.currentRole.collectAsStateWithLifecycle()
    val serverIp by viewModel.serverIp.collectAsStateWithLifecycle()
    val serverPort by viewModel.serverPort.collectAsStateWithLifecycle()
    val globalError by viewModel.globalError.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    
    var activeErrorText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(globalError) {
        if (globalError != null) {
            val errText = globalError!!
            if (errText.contains("Crash") || errText.contains("Exception") || errText.length > 100) {
                activeErrorText = errText
            } else {
                snackbarHostState.showSnackbar(
                    message = errText,
                    duration = SnackbarDuration.Long
                )
            }
            viewModel.dismissGlobalError()
        }
    }

    if (activeErrorText != null) {
        AlertDialog(
            onDismissRequest = { activeErrorText = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(
                    text = "Application Incident Detected",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "The application caught an unexpected event or recovered from a crash:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .background(
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.25f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(12.dp)
                    ) {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item {
                                Text(
                                    text = activeErrorText ?: "",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { activeErrorText = null }) {
                    Text("Dismiss", color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFFF7F9FF),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            // Elegant Clean Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF00639B)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "PhotoSync Logo",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "PhotoSync",
                        fontWeight = FontWeight.Medium,
                        fontSize = 18.sp,
                        color = Color(0xFF191C1E)
                    )
                    Text(
                        text = if (serverIp != "No Active Wi-Fi" && serverIp != "No Active IP") "Local Interface Active" else "Disconnected",
                        fontSize = 11.sp,
                        color = if (serverIp != "No Active Wi-Fi" && serverIp != "No Active IP") Color(0xFF00639B) else Color(0xFFDC2626),
                        fontWeight = FontWeight.Normal
                    )
                }

                IconButton(
                    onClick = { viewModel.updateLocalIp() },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFDDE2F0).copy(alpha = 0.5f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh Connection",
                        tint = Color(0xFF191C1E),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Subtitle Onboarding Intro
            AnimatedVisibility(
                visible = currentRole == "NONE",
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(100.dp))
                            .background(Color(0xFFD1E4FF))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF22C55E))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Ready on Local Wi-Fi",
                            color = Color(0xFF001D36),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Where do you want your photos?",
                        fontWeight = FontWeight.Light,
                        color = Color(0xFF191C1E),
                        fontSize = 28.sp,
                        lineHeight = 34.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Transfer backup photos directly between devices with crystal-clear quality over local Wi-Fi. No cloud, no friction.",
                        color = Color(0xFF43474E),
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // Role Toggle Buttons Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RoleSelectionCard(
                    roleName = "Send Photos",
                    description = "Main device",
                    icon = Icons.Default.Send,
                    isSelected = currentRole == "SENDER",
                    modifier = Modifier.weight(1f).testTag("select_sender_role"),
                    onClick = { viewModel.setRole("SENDER") }
                )
                RoleSelectionCard(
                    roleName = "Receive Photos",
                    description = "Backup device",
                    icon = Icons.Default.Home,
                    isSelected = currentRole == "RECEIVER",
                    modifier = Modifier.weight(1f).testTag("select_receiver_role"),
                    onClick = { viewModel.setRole("RECEIVER") }
                )
            }

            // Active Frame Content
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (currentRole) {
                    "SENDER" -> SenderFlowFrame(viewModel, onTriggerDeletions)
                    "RECEIVER" -> ReceiverFlowFrame(viewModel)
                    else -> OnboardingEmptyFrame()
                }
            }

            // Beautiful Clean Minimalism Footer
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = Color(0xFFDDE2F0), thickness = 1.dp)
            Spacer(modifier = Modifier.height(12.dp))
            
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ACTIVE NETWORK",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF43474E),
                        letterSpacing = 0.8.sp
                    )
                    Text(
                        text = if (serverIp != "No Active Wi-Fi" && serverIp != "No Active IP") "Local LAN" else "Disconnected",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00639B)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFF0F4F8))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Secure Sync",
                            tint = Color(0xFF00639B),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Photos are transferred directly between devices. No cloud storage used.",
                        fontSize = 11.sp,
                        color = Color(0xFF43474E),
                        fontWeight = FontWeight.Normal,
                        lineHeight = 15.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
fun RoleSelectionCard(
    roleName: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val isSender = roleName.contains("Send", ignoreCase = true)
    
    val containerColor = when {
        isSelected && isSender -> Color(0xFF00639B)
        isSelected && !isSender -> Color(0xFFDDE2F0)
        !isSelected && isSender -> Color(0xFF00639B).copy(alpha = 0.08f)
        else -> Color(0xFFDDE2F0).copy(alpha = 0.4f)
    }
    
    val textColor = when {
        isSelected && isSender -> Color.White
        isSelected && !isSender -> Color(0xFF191C1E)
        !isSelected && isSender -> Color(0xFF00639B)
        else -> Color(0xFF43474E)
    }

    val descColor = when {
        isSelected && isSender -> Color.White.copy(alpha = 0.7f)
        isSelected && !isSender -> Color(0xFF43474E)
        else -> Color(0xFF43474E).copy(alpha = 0.7f)
    }
    
    val iconContainerColor = when {
        isSelected && isSender -> Color.White.copy(alpha = 0.2f)
        isSelected && !isSender -> Color(0xFF00639B).copy(alpha = 0.1f)
        !isSelected && isSender -> Color(0xFF00639B).copy(alpha = 0.15f)
        else -> Color(0xFF191C1E).copy(alpha = 0.08f)
    }

    val iconColor = when {
        isSelected && isSender -> Color.White
        isSelected && !isSender -> Color(0xFF00639B)
        !isSelected && isSender -> Color(0xFF00639B)
        else -> Color(0xFF191C1E)
    }

    val badgeText = if (isSender) "Main Phone" else "Backup Phone"
    val badgeTextColor = when {
        isSelected && isSender -> Color.White.copy(alpha = 0.6f)
        isSelected && !isSender -> Color(0xFF191C1E).copy(alpha = 0.5f)
        else -> Color(0xFF43474E).copy(alpha = 0.5f)
    }

    val cardShape = RoundedCornerShape(24.dp)

    Card(
        modifier = modifier
            .clickable(onClick = onClick)
            .border(
                width = 1.dp,
                color = if (isSelected) Color.Transparent else Color(0xFFDDE2F0),
                shape = cardShape
            ),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 2.dp else 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(iconContainerColor)
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Text(
                    text = badgeText,
                    color = badgeTextColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = roleName,
                fontWeight = FontWeight.Medium,
                fontSize = 18.sp,
                color = textColor,
                lineHeight = 22.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                fontSize = 11.sp,
                color = descColor,
                lineHeight = 15.sp
            )
        }
    }
}

@Composable
fun OnboardingEmptyFrame() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(Color(0xFFD1E4FF)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search",
                tint = Color(0xFF00639B),
                modifier = Modifier.size(36.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Select an option above to start syncing",
            color = Color(0xFF191C1E),
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Photos are backed up directly over local network. Safe, secure, and private.",
            color = Color(0xFF43474E),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
    }
}


// ==========================================
// SENDER FLOW COMPOSE VIEW
// ==========================================

@Composable
fun SenderFlowFrame(
    viewModel: PhotoSyncViewModel,
    onTriggerDeletions: (List<Uri>) -> Unit
) {
    val context = LocalContext.current
    val devices by viewModel.discoveredDevices.collectAsStateWithLifecycle()
    val selectedDevice by viewModel.selectedDevice.collectAsStateWithLifecycle()
    val selectedPhotos by viewModel.selectedPhotos.collectAsStateWithLifecycle()
    val deletionOption by viewModel.deletionOption.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val senderPin by viewModel.senderPairPin.collectAsStateWithLifecycle()
    val rememberAutoPair by viewModel.rememberAutoPair.collectAsStateWithLifecycle()
    val hasStoragePermission by viewModel.hasStoragePermission.collectAsStateWithLifecycle()
    val availableFolders by viewModel.availableFolders.collectAsStateWithLifecycle()
    val selectedFolderIds by viewModel.selectedFolderIds.collectAsStateWithLifecycle()
    val isScanningFolders by viewModel.isScanningFolders.collectAsStateWithLifecycle()
    val autoSyncEnabled by viewModel.autoSyncEnabled.collectAsStateWithLifecycle()
    var selectionMode by remember { mutableStateOf(0) } // 0 = Photo Picker, 1 = Backup Folders
    
    // Android Visual Photo Picker launcher
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
        onResult = { uris ->
            if (uris.isNotEmpty()) {
                viewModel.importSelectedUris(uris)
            }
        }
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Step 1: Scanner for target Backup Phones
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .border(1.dp, Color(0xFFDDE2F0), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "1. TARGET BACKUP DEVICE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00639B),
                        letterSpacing = 0.5.sp
                    )
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFD1E4FF)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "mDNS SCAN",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF001D36),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(10.dp))

                if (devices.isEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color(0xFF00639B),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Scanning Wi-Fi sub-network...",
                            fontSize = 11.sp,
                            color = Color(0xFF43474E)
                        )
                    }
                } else {
                    devices.values.forEach { device ->
                        val isPicked = selectedDevice?.name == device.name
                        val rowBgColor = if (isPicked) Color(0xFFD1E4FF).copy(alpha = 0.6f) else Color.Transparent
                        val rowBorderColor = if (isPicked) Color(0xFF00639B) else Color(0xFFDDE2F0)
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(rowBgColor)
                                .border(1.dp, rowBorderColor, RoundedCornerShape(12.dp))
                                .clickable { viewModel.selectDevice(device) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isPicked) Icons.Default.CheckCircle else Icons.Default.Home,
                                contentDescription = null,
                                tint = if (isPicked) Color(0xFF00639B) else Color(0xFF43474E),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = device.name,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF191C1E)
                                )
                                Text(
                                    text = "IP: ${device.ip}:${device.port}",
                                    fontSize = 10.sp,
                                    color = Color(0xFF43474E)
                                )
                            }
                            if (isPicked) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF00639B)),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "SELECTED",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 8.sp,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Step 1.5: Pairing Code Authorization PIN (For Requirement 3 PIN security)
        if (selectedDevice != null) {
            item {
                val pinVal = senderPin
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .border(1.dp, Color(0xFFDDE2F0), RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Text(
                        text = "1.5 ENTER SECURITY PAIRING PIN",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00639B),
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Input the one-time 4-digit pairing code shown on your receiver device to register and authorize the photo synchronization session.",
                        fontSize = 11.sp,
                        color = Color(0xFF43474E),
                        lineHeight = 16.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pinVal,
                        onValueChange = { input ->
                            if (input.length <= 4 && input.all { it.isDigit() }) {
                                viewModel.setSenderPairPin(input)
                            }
                        },
                        label = { Text("4-Digit pairing code", fontSize = 12.sp) },
                        placeholder = { Text("e.g. 5832", fontSize = 12.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF00639B),
                            unfocusedBorderColor = Color(0xFFDDE2F0),
                            cursorColor = Color(0xFF00639B)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pin_code_field"),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 4.sp
                        ),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        )
                    )
                    
                    Spacer(modifier = Modifier.height(10.dp))
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { viewModel.setRememberAutoPair(!rememberAutoPair) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = rememberAutoPair,
                            onCheckedChange = { chk -> viewModel.setRememberAutoPair(chk) }
                        )
                        Text(
                            text = "Auto-pair with this device on this Wi-Fi network",
                            fontSize = 12.sp,
                            color = Color(0xFF43474E)
                        )
                    }
                }
            }
        }

        // Step 2: Photo Picker & Carousel
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .border(1.dp, Color(0xFFDDE2F0), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Text(
                    text = "2. CHOOSE SOURCE PHOTOS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00639B),
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFF0F4F8))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val activeColor = Color(0xFF00639B)
                    val inactiveColor = Color.Transparent
                    
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selectionMode == 0) Color.White else inactiveColor)
                            .border(1.dp, if (selectionMode == 0) Color(0xFF191C1E).copy(alpha = 0.1f) else Color.Transparent, RoundedCornerShape(6.dp))
                            .clickable { selectionMode = 0 }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                tint = if (selectionMode == 0) activeColor else Color(0xFF43474E),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Photo Picker",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectionMode == 0) activeColor else Color(0xFF43474E)
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selectionMode == 1) Color.White else inactiveColor)
                            .border(1.dp, if (selectionMode == 1) Color(0xFF191C1E).copy(alpha = 0.1f) else Color.Transparent, RoundedCornerShape(6.dp))
                            .clickable { 
                                selectionMode = 1
                                viewModel.checkAndLoadFolders()
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.List,
                                contentDescription = null,
                                tint = if (selectionMode == 1) activeColor else Color(0xFF43474E),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Backup Folders",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectionMode == 1) activeColor else Color(0xFF43474E)
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))

                if (selectionMode == 0) {
                    Button(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("action_select_photos"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFDDE2F0),
                            contentColor = Color(0xFF191C1E)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, tint = Color(0xFF191C1E))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Access Photo Picker",
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    
                    val permissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission(),
                        onResult = { isGranted ->
                            if (isGranted) {
                                viewModel.loadLocalFolders()
                            } else {
                                viewModel.checkAndLoadFolders()
                            }
                        }
                    )
                    
                    DisposableEffect(Unit) {
                        viewModel.checkAndLoadFolders()
                        onDispose {}
                    }
                    
                    if (hasStoragePermission == false) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Permit storage access to auto-discover local media folders.",
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                color = Color(0xFF43474E),
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Button(
                                onClick = {
                                    val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        android.Manifest.permission.READ_MEDIA_IMAGES
                                    } else {
                                        android.Manifest.permission.READ_EXTERNAL_STORAGE
                                    }
                                    permissionLauncher.launch(perm)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00639B)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Grant Permission", fontSize = 12.sp)
                            }
                        }
                    } else if (isScanningFolders) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color(0xFF00639B),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Scanning memory...", fontSize = 12.sp, color = Color(0xFF43474E))
                        }
                    } else if (availableFolders.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No folder structures containing images identified.",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                    } else {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Tick folders to automatically add their content to the backup queue:",
                                fontSize = 11.sp,
                                color = Color(0xFF43474E),
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            
                            availableFolders.forEach { folder ->
                                val isChecked = selectedFolderIds.contains(folder.id)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isChecked) Color(0xFFD1E4FF).copy(alpha = 0.3f) else Color(0xFFF7F9FF))
                                        .border(1.dp, if (isChecked) Color(0xFF00639B) else Color(0xFFDDE2F0), RoundedCornerShape(10.dp))
                                        .clickable { viewModel.toggleFolderSelection(folder.id) }
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFFE0E5F1)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (folder.firstPhotoUri != null) {
                                            AsyncImage(
                                                model = folder.firstPhotoUri,
                                                contentDescription = null,
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.List,
                                                contentDescription = null,
                                                tint = Color(0xFF00639B),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                    
                                    Spacer(modifier = Modifier.width(10.dp))
                                    
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = folder.name,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF191C1E)
                                        )
                                        Text(
                                            text = "${folder.count} items inside",
                                            fontSize = 11.sp,
                                            color = Color(0xFF43474E)
                                        )
                                    }
                                    
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = { viewModel.toggleFolderSelection(folder.id) },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = Color(0xFF00639B)
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                if (selectedPhotos.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val totalSizeBytes = selectedPhotos.sumOf { it.size }
                        val representationStr = formatBytes(totalSizeBytes)
                        Text(
                            text = "${selectedPhotos.size} Photos Chosen",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF191C1E)
                        )
                        Text(
                            text = "Size: $representationStr",
                            fontSize = 11.sp,
                            color = Color(0xFF00639B)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(selectedPhotos) { photo ->
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Black)
                                    .border(1.dp, Color(0xFFDDE2F0), RoundedCornerShape(8.dp))
                            ) {
                                AsyncImage(
                                    model = photo.uri,
                                    contentDescription = photo.filename,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                    }
                } else if (syncState is SyncState.Preparation) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color(0xFF00639B))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Preparing media files...", fontSize = 11.sp, color = Color(0xFF43474E))
                    }
                }
            }
        }

        // Step 3: Deletion Policy Setup
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .border(1.dp, Color(0xFFDDE2F0), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Text(
                    text = "3. POST-TRANSFER ACTION",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00639B),
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Preservation radio
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { viewModel.setDeletionOption(DeletionOption.KEEP) }
                        .padding(vertical = 4.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = deletionOption == DeletionOption.KEEP,
                        onClick = { viewModel.setDeletionOption(DeletionOption.KEEP) },
                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF00639B))
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Column {
                        Text(
                            text = "Preserve raw photos here",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF191C1E)
                        )
                        Text(
                            text = "Keep image copies safe on both devices.",
                            fontSize = 10.sp,
                            color = Color(0xFF43474E)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Deletion radio
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { viewModel.setDeletionOption(DeletionOption.DELETE) }
                        .padding(vertical = 4.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = deletionOption == DeletionOption.DELETE,
                        onClick = { viewModel.setDeletionOption(DeletionOption.DELETE) },
                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF00639B))
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Column {
                        Text(
                            text = "Delete photos after successful transfer",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF191C1E)
                        )
                        Text(
                            text = "Automated post-sync cleanup. Safeguards space.",
                            fontSize = 10.sp,
                            color = Color(0xFF43474E)
                        )
                    }
                }

                AnimatedVisibility(visible = deletionOption == DeletionOption.DELETE) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFFFB900).copy(alpha = 0.1f))
                            .border(1.dp, Color(0xFFFFB900).copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                            .padding(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color(0xFFD97706),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Android OS will launch a secure permission confirmation dialog at the end of the backup before wiping files from this device.",
                            fontSize = 9.sp,
                            color = Color(0xFF975A16),
                            lineHeight = 13.sp
                        )
                    }
                }
            }
        }

        // Active Progress status frame
        item {
            AnimatedVisibility(visible = syncState != SyncState.Idle && syncState != SyncState.Preparation) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .border(1.dp, Color(0xFFDDE2F0), RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    when (val state = syncState) {
                        is SyncState.Transferring -> {
                            Text(
                                text = "TRANSFER IN PROGRESS",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00639B),
                                letterSpacing = 0.5.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Syncing photo ${state.currentIndex + 1} of ${state.totalCount}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF191C1E)
                                )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = state.currentFileName,
                                fontSize = 10.sp,
                                color = Color(0xFF43474E),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            val animatedProgress by animateFloatAsState(targetValue = state.progress, label = "uploadProgress")
                            LinearProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = Color(0xFF00639B),
                                trackColor = Color(0xFFDDE2F0),
                            )
                        }
                        
                        is SyncState.Deleting -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color(0xFF00639B))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Launching Android Media Store Cleanup Request...",
                                    fontSize = 12.sp,
                                    color = Color(0xFF191C1E),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        is SyncState.Success -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (state.partialError != null) Icons.Default.Warning else Icons.Default.Done,
                                    contentDescription = null,
                                    tint = if (state.partialError != null) Color(0xFFEAB308) else Color(0xFF16A34A),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = if (state.partialError != null) "Partial Sync Complete" else "Sync Handshake Complete!",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF191C1E)
                                    )
                                    Text(
                                        text = "${state.transferredCount} items successfully backed up securely.",
                                        fontSize = 11.sp,
                                        color = Color(0xFF43474E)
                                    )
                                    if (state.partialError != null) {
                                        Text(
                                            text = "Error: ${state.partialError}",
                                            fontSize = 9.sp,
                                            color = Color(0xFFBA1A1A)
                                        )
                                    }
                                }
                            }
                            
                            if (state.transferredUris.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFF00639B).copy(alpha = 0.04f))
                                        .border(1.dp, Color(0xFFDDE2F0), RoundedCornerShape(12.dp))
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        text = "REVIEW TRANSFERRED FILES",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF00639B),
                                        letterSpacing = 0.5.sp
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth().height(64.dp)
                                    ) {
                                        items(state.transferredUris) { uri ->
                                            Card(
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.size(64.dp)
                                            ) {
                                                AsyncImage(
                                                    model = uri,
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            }
                                        }
                                    }
                                    
                                    Spacer(modifier = Modifier.height(14.dp))
                                    
                                    Text(
                                        text = "Would you like to delete these successfully backed up copies from this local device to free up storage space?",
                                        fontSize = 11.sp,
                                        color = Color(0xFF191C1E),
                                        lineHeight = 15.sp
                                    )
                                    
                                    Spacer(modifier = Modifier.height(12.dp))
                                    
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                viewModel.initiateDeferredDeletion(state.transferredUris, onTriggerDeletions)
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFFBA1A1A),
                                                contentColor = Color.White
                                            ),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Wipe Local", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        
                                        OutlinedButton(
                                            onClick = {
                                                viewModel.clearSelections()
                                            },
                                            border = BorderStroke(1.dp, Color(0xFFDDE2F0)),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Keep Local Only", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF43474E))
                                        }
                                    }
                                }
                            } else {
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = { viewModel.clearSelections() },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDDE2F0), contentColor = Color(0xFF191C1E)),
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text(text = "Dismiss & Create New Sync Batch", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }

                        is SyncState.Failed -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = null,
                                    tint = Color(0xFFDC2626),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Sync Failed",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF191C1E)
                                    )
                                    Text(
                                        text = state.error,
                                        fontSize = 10.sp,
                                        color = Color(0xFFDC2626)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = { viewModel.clearSelections() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDDE2F0), contentColor = Color(0xFF191C1E)),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(text = "Reset Sync Tool", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        else -> {}
                    }
                }
            }
        }

        // Action Trigger Button
        item {
            
            val canLaunch = selectedDevice != null && 
                            senderPin.length == 4 && 
                            selectedPhotos.isNotEmpty() && 
                            syncState == SyncState.Idle
                            
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-Sync New Photos",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = Color(0xFF191C1E)
                        )
                        Text(
                            text = "Automatically backup new photos added to this phone.",
                            fontSize = 11.sp,
                            color = Color(0xFF43474E),
                            lineHeight = 14.sp
                        )
                    }
                    Switch(
                        checked = autoSyncEnabled,
                        onCheckedChange = { 
                            if (selectedDevice != null && senderPin.length == 4) {
                                viewModel.setAutoSyncEnabled(it)
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF00639B),
                            checkedBorderColor = Color.Transparent
                        )
                    )
                }

                Button(
                    onClick = {
                        viewModel.executeTransfer(onTriggerDeletions)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("action_start_sync"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00639B),
                    disabledContainerColor = Color(0xFFDDE2F0).copy(alpha = 0.6f),
                    contentColor = Color.White,
                    disabledContentColor = Color(0xFF191C1E).copy(alpha = 0.35f)
                ),
                shape = RoundedCornerShape(14.dp),
                enabled = canLaunch
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = if (canLaunch) Color.White else Color(0xFF191C1E).copy(alpha = 0.35f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                val desc = if (selectedDevice == null) "Select Backup Device first" 
                           else if (senderPin.length < 4) "Enter 4-digit Pairing PIN"
                           else if (selectedPhotos.isEmpty()) "Select photos to backup"
                           else "Start Photo Sync"
                Text(
                    text = desc,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.sp
                )
            }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// ==========================================
// RECEIVER FLOW COMPOSE VIEW
// ==========================================

@Composable
fun ReceiverFlowFrame(viewModel: PhotoSyncViewModel) {
    val serverIp by viewModel.serverIp.collectAsStateWithLifecycle()
    val serverPort by viewModel.serverPort.collectAsStateWithLifecycle()
    val isRunning by viewModel.isServerRunning.collectAsStateWithLifecycle()
    val receiverProgressName by viewModel.receiverProgressName.collectAsStateWithLifecycle()
    val receiverProgressVal by viewModel.receiverProgressVal.collectAsStateWithLifecycle()
    val receivedPhotos by viewModel.receivedPhotos.collectAsStateWithLifecycle()
    val pairingPinVal by LocalServerService.pairingPin.collectAsStateWithLifecycle()

    val dynamicDeviceModel = Build.MODEL

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Step 1: Active Connection broadcast card
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .border(1.dp, Color(0xFFDDE2F0), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "BACKUP RECEIVER NODE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00639B),
                        letterSpacing = 0.5.sp
                    )
                    
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isRunning) Color(0xFFD1E4FF) else Color(0xFFDDE2F0))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isRunning) "ACTIVE ADVERTISING" else "STANDBY",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isRunning) Color(0xFF001D36) else Color(0xFF43474E)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isRunning) Icons.Default.Search else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (isRunning) Color(0xFF00639B) else Color(0xFF43474E),
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Backup_${dynamicDeviceModel.replace(" ", "_")}",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF191C1E)
                        )
                        Text(
                            text = if (isRunning) "Listening on http://$serverIp:$serverPort/api/v1/upload" 
                                   else "Requires static Shared LAN connection",
                            fontSize = 10.sp,
                            color = Color(0xFF43474E)
                        )
                    }
                }
                
                // Show Secure Pairing PIN if service is active
                if (isRunning && pairingPinVal.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF00639B).copy(alpha = 0.08f))
                            .border(1.dp, Color(0xFF00639B).copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "ONE-TIME SECURE PAIRING CODE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00639B),
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = pairingPinVal,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF00639B),
                            letterSpacing = 6.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Enter this PIN on the sender device of the pairing connection.",
                            fontSize = 9.sp,
                            color = Color(0xFF43474E),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // Active Incoming progression sheet
        item {
            AnimatedVisibility(visible = receiverProgressName.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .border(1.dp, Color(0xFFDDE2F0), RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Text(
                        text = "RECEIVING PHOTO DATA",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00639B),
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = receiverProgressName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF191C1E)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    val animatedProgress by animateFloatAsState(targetValue = receiverProgressVal, label = "receiverProgress")
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = Color(0xFF00639B),
                        trackColor = Color(0xFFDDE2F0),
                    )
                }
            }
        }

        // Received Photo items Grid
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .border(1.dp, Color(0xFFDDE2F0), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Text(
                    text = "BACKUP GALLERY HISTORY",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00639B),
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                if (receivedPhotos.isEmpty()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.List,
                            contentDescription = null,
                            tint = Color(0xFFDDE2F0),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No backup images received inside this session.",
                            fontSize = 11.sp,
                            color = Color(0xFF43474E)
                        )
                        Text(
                            text = "Stored photos can be resolved inside DCIM/BackupSync directory.",
                            fontSize = 9.sp,
                            color = Color(0xFF43474E).copy(alpha = 0.7f)
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Transferred this Session:", fontSize = 12.sp, color = Color(0xFF191C1E), fontWeight = FontWeight.Bold)
                        Text(text = "${receivedPhotos.size} Photos", fontSize = 12.sp, color = Color(0xFF00639B), fontWeight = FontWeight.Bold)
                    }
                    
                    // Custom grid layout using standard Rows list to avoid nested vertically scrollable components exception
                    val itemsList = receivedPhotos
                    val chunks = itemsList.chunked(3)
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        chunks.forEach { rowItems ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                rowItems.forEach { photo ->
                                    Card(
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(1f)
                                            .border(0.8.dp, Color(0xFFDDE2F0), RoundedCornerShape(8.dp)),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Box(modifier = Modifier.fillMaxSize()) {
                                            AsyncImage(
                                                model = photo.second,
                                                contentDescription = photo.first,
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                            )
                                            // Small text overlay for visual check
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.BottomStart)
                                                    .fillMaxWidth()
                                                    .background(Color.Black.copy(alpha = 0.4f))
                                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = photo.first,
                                                    fontSize = 7.sp,
                                                    color = Color.White,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                                // Pad the Row with spacers if the chunk has less than 3 items
                                val emptySpaces = 3 - rowItems.size
                                repeat(emptySpaces) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// STRING DECIMAL FORMATTER
// ==========================================
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 Bytes"
    val units = arrayOf("Bytes", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format("%.2f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

