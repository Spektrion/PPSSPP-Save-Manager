package com.spekt.ppssppsync

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class SyncService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false
    private val CHANNEL_ID = "PPSSPP_SYNC_CHANNEL"
    private val NOTIFICATION_ID = 1

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            startForegroundService()
            isRunning = true
            startSyncLoop()
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PPSSPP Synchronization",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PPSSPP Sync Active")
            .setContentText("Watching for save file changes...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startSyncLoop() {
        serviceScope.launch {
            while (isRunning) {
                try {
                    val sharedPref = getSharedPreferences("com.spekt.ppssppsync_preferences", Context.MODE_PRIVATE)
                    val ip = sharedPref.getString("pc_ip", "") ?: ""
                    val pin = sharedPref.getString("pc_pin", "") ?: ""
                    val uriString = sharedPref.getString("savedata_uri", "") ?: ""

                    if (ip.isNotEmpty() && pin.isNotEmpty() && uriString.isNotEmpty()) {
                        performSync(ip, pin, Uri.parse(uriString))
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(120_000)
            }
        }
    }

    private suspend fun performSync(ip: String, pin: String, uri: Uri) {
        try {
            val request = Request.Builder()
                .url("http://$ip:5000/games")
                .addHeader("X-Sync-Pin", pin)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return

            val responseData = response.body?.string() ?: "{}"
            val type = object : TypeToken<Map<String, Map<String, Long>>>() {}.type
            val pcGames: Map<String, Map<String, Long>> = Gson().fromJson(responseData, type)

            val saveDir = DocumentFile.fromTreeUri(this, uri) ?: return
            val localGames = saveDir.listFiles().filter { it.isDirectory }.associateBy { it.name }

            // 1. Sync games that are on PC (Handles PC->Android and updates to existing Android->PC)
            for ((gameId, pcFiles) in pcGames) {
                val localGameDir = localGames[gameId]
                if (localGameDir != null) {
                    syncGameFiles(ip, pin, gameId, pcFiles, localGameDir)
                }
            }

            // 2. NEW: Check games that are on Android but NOT on PC, and push them
            for ((gameId, localGameDir) in localGames) {
                if (gameId != null && !pcGames.containsKey(gameId)) {
                    // This game is new on Android, push all its files to PC
                    val files = localGameDir.listFiles().filter { it.isFile }
                    for (file in files) {
                        uploadFile(ip, pin, gameId, file.name!!, file.lastModified() / 1000, file)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun syncGameFiles(ip: String, pin: String, gameId: String, pcFiles: Map<String, Long>, localGameDir: DocumentFile) {
        val localFiles = localGameDir.listFiles().filter { it.isFile }.associate { it.name!! to (it.lastModified() / 1000) }

        for ((fileName, pcTime) in pcFiles) {
            val localTime = localFiles[fileName]
            if (localTime == null || pcTime > localTime) {
                downloadFile(ip, pin, gameId, fileName, localGameDir)
            }
        }

        for ((fileName, localTime) in localFiles) {
            val pcTime = pcFiles[fileName]
            if (pcTime == null || localTime > pcTime) {
                uploadFile(ip, pin, gameId, fileName, localTime, localGameDir.findFile(fileName)!!)
            }
        }
    }

    private suspend fun downloadFile(ip: String, pin: String, gameId: String, fileName: String, localGameDir: DocumentFile) {
        try {
            val request = Request.Builder()
                .url("http://$ip:5000/download/$gameId/$fileName")
                .addHeader("X-Sync-Pin", pin)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful && response.body != null) {
                var fileDoc = localGameDir.findFile(fileName) ?: localGameDir.createFile("application/octet-stream", fileName)
                fileDoc?.let { doc ->
                    contentResolver.openOutputStream(doc.uri)?.use { out ->
                        response.body!!.byteStream().use { it.copyTo(out) }
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private suspend fun uploadFile(ip: String, pin: String, gameId: String, fileName: String, localTime: Long, fileDoc: DocumentFile) {
        try {
            val cacheFile = File(cacheDir, fileName)
            contentResolver.openInputStream(fileDoc.uri)?.use { inStream ->
                FileOutputStream(cacheFile).use { inStream.copyTo(it) }
            }

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("mtime", localTime.toString())
                .addFormDataPart("file", fileName, cacheFile.asRequestBody("application/octet-stream".toMediaTypeOrNull()))
                .build()

            val request = Request.Builder()
                .url("http://$ip:5000/upload/$gameId/$fileName")
                .addHeader("X-Sync-Pin", pin)
                .post(requestBody)
                .build()

            client.newCall(request).execute()
            cacheFile.delete()
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onDestroy() {
        isRunning = false
        serviceScope.cancel()
        super.onDestroy()
    }
}
