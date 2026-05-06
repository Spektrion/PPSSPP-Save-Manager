package com.spekt.ppssppsync

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {

    private var selectedDirectoryUri by mutableStateOf<Uri?>(null)

    private val selectDirLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, takeFlags)
                selectedDirectoryUri = uri
                
                val sharedPref = getSharedPreferences("com.spekt.ppssppsync_preferences", Context.MODE_PRIVATE)
                with (sharedPref.edit()) {
                    putString("savedata_uri", uri.toString())
                    apply()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val sharedPref = getSharedPreferences("com.spekt.ppssppsync_preferences", Context.MODE_PRIVATE)
        val savedUri = sharedPref.getString("savedata_uri", null)
        if (savedUri != null) {
            selectedDirectoryUri = Uri.parse(savedUri)
        }
        
        val savedIp = sharedPref.getString("pc_ip", "")
        val savedPin = sharedPref.getString("pc_pin", "")

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        savedIp ?: "",
                        savedPin ?: "",
                        onSelectFolder = {
                            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                            selectDirLauncher.launch(intent)
                        },
                        onSaveConfig = { ip, pin ->
                            with (sharedPref.edit()) {
                                putString("pc_ip", ip)
                                putString("pc_pin", pin)
                                apply()
                            }
                        }
                    )
                }
            }
        }
    }

    @Composable
    fun MainScreen(initialIp: String, initialPin: String, onSelectFolder: () -> Unit, onSaveConfig: (String, String) -> Unit) {
        var ip by remember { mutableStateOf(initialIp) }
        var pin by remember { mutableStateOf(initialPin) }
        var logText by remember { mutableStateOf("Waiting for action...") }
        var isSyncing by remember { mutableStateOf(false) }
        var isServiceRunning by remember { mutableStateOf(isServiceRunning(SyncService::class.java)) }
        var showForceDialog by remember { mutableStateOf(false) }
        var pendingNoMatchGame by remember { mutableStateOf("") }
        var pendingNoMatchFiles by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
        
        val coroutineScope = rememberCoroutineScope()
        val scrollState = rememberScrollState()

        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                startTheService(ip, pin, onSaveConfig)
                isServiceRunning = true
            }
        }

        fun appendLog(msg: String) {
            logText += "\n$msg"
        }

        fun toggleService() {
            if (isServiceRunning) {
                val intent = Intent(this@MainActivity, SyncService::class.java)
                stopService(intent)
                isServiceRunning = false
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        startTheService(ip, pin, onSaveConfig)
                        isServiceRunning = true
                    }
                } else {
                    startTheService(ip, pin, onSaveConfig)
                    isServiceRunning = true
                }
            }
        }

        fun doSync() {
            if (selectedDirectoryUri == null) {
                logText = "Error: Please select the PPSSPP SAVEDATA folder first."
                return
            }
            if (ip.isBlank() || pin.isBlank()) {
                logText = "Error: IP and PIN are required."
                return
            }
            onSaveConfig(ip, pin)
            isSyncing = true
            logText = "Starting manual synchronization..."
            
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val client = OkHttpClient()
                    val request = Request.Builder()
                        .url("http://$ip:5000/games")
                        .addHeader("X-Sync-Pin", pin)
                        .build()
                        
                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) {
                        withContext(Dispatchers.Main) { appendLog("Connection error: ${response.code}") }
                        withContext(Dispatchers.Main) { isSyncing = false }
                        return@launch
                    }
                    
                    val responseData = response.body?.string() ?: "{}"
                    val type = object : TypeToken<Map<String, Map<String, Long>>>() {}.type
                    val pcGames: Map<String, Map<String, Long>> = Gson().fromJson(responseData, type)
                    
                    val saveDir = DocumentFile.fromTreeUri(this@MainActivity, selectedDirectoryUri!!)
                    val localGames = saveDir?.listFiles()?.filter { it.isDirectory }?.associateBy { it.name } ?: emptyMap()
                    
                    for ((gameId, pcFiles) in pcGames) {
                        if (localGames.containsKey(gameId)) {
                            withContext(Dispatchers.Main) { appendLog("Syncing $gameId...") }
                            syncGameFiles(client, ip, pin, gameId, pcFiles, localGames[gameId]!!)
                        } else {
                            withContext(Dispatchers.Main) { 
                                pendingNoMatchGame = gameId
                                pendingNoMatchFiles = pcFiles
                                showForceDialog = true
                            }
                            while(showForceDialog) { kotlinx.coroutines.delay(500) }
                        }
                    }
                    
                    withContext(Dispatchers.Main) { 
                        appendLog("Manual sync finished.")
                        isSyncing = false 
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { 
                        appendLog("Error: ${e.message}")
                        isSyncing = false 
                    }
                }
            }
        }

        if (showForceDialog) {
            AlertDialog(
                onDismissRequest = { showForceDialog = false },
                title = { Text("New game on PC") },
                text = { Text("The game $pendingNoMatchGame exists on PC but not on Android. Do you want to sync it?") },
                confirmButton = {
                    Button(onClick = { 
                        coroutineScope.launch(Dispatchers.IO) {
                            val saveDir = DocumentFile.fromTreeUri(this@MainActivity, selectedDirectoryUri!!)
                            val newDir = saveDir?.createDirectory(pendingNoMatchGame)
                            if (newDir != null) {
                                syncGameFiles(OkHttpClient(), ip, pin, pendingNoMatchGame, pendingNoMatchFiles, newDir)
                            }
                        }
                        showForceDialog = false 
                    }) { Text("Yes") }
                },
                dismissButton = {
                    Button(onClick = { showForceDialog = false }) { Text("No") }
                }
            )
        }

        Column(modifier = Modifier.padding(16.dp).verticalScroll(scrollState)) {
            Text("PPSSPP Sync Auto", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(onClick = onSelectFolder, modifier = Modifier.fillMaxWidth()) {
                Text(if (selectedDirectoryUri != null) "SAVEDATA Folder Ready" else "1. Select SAVEDATA Folder")
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(value = ip, onValueChange = { ip = it }, label = { Text("PC IP Address") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = pin, onValueChange = { pin = it }, label = { Text("Security PIN") }, modifier = Modifier.fillMaxWidth())
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Automatic Sync", style = MaterialTheme.typography.titleMedium)
                        Text("Runs in background", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = isServiceRunning, onCheckedChange = { toggleService() })
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { doSync() }, enabled = !isSyncing, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)) {
                Text(if (isSyncing) "Syncing..." else "Sync Manually Now")
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            Text("Log:", style = MaterialTheme.typography.titleSmall)
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().height(150.dp)) {
                Text(text = logText, modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    private fun startTheService(ip: String, pin: String, onSaveConfig: (String, String) -> Unit) {
        onSaveConfig(ip, pin)
        val intent = Intent(this, SyncService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private suspend fun syncGameFiles(client: OkHttpClient, ip: String, pin: String, gameId: String, pcFiles: Map<String, Long>, localGameDir: DocumentFile) {
        val localFiles = localGameDir.listFiles().filter { it.isFile }.associate { it.name!! to (it.lastModified() / 1000) }
        for ((fileName, pcTime) in pcFiles) {
            val localTime = localFiles[fileName]
            if (localTime == null || pcTime > localTime) {
                downloadFile(client, ip, pin, gameId, fileName, localGameDir)
            }
        }
        for ((fileName, localTime) in localFiles) {
            val pcTime = pcFiles[fileName]
            if (pcTime == null || localTime > pcTime) {
                uploadFile(client, ip, pin, gameId, fileName, localTime, localGameDir.findFile(fileName)!!)
            }
        }
    }

    private suspend fun downloadFile(client: OkHttpClient, ip: String, pin: String, gameId: String, fileName: String, localGameDir: DocumentFile) {
        try {
            val request = Request.Builder().url("http://$ip:5000/download/$gameId/$fileName").addHeader("X-Sync-Pin", pin).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful && response.body != null) {
                var fileDoc = localGameDir.findFile(fileName) ?: localGameDir.createFile("application/octet-stream", fileName)
                fileDoc?.let { doc ->
                    contentResolver.openOutputStream(doc.uri)?.use { out -> response.body!!.byteStream().use { it.copyTo(out) } }
                }
            }
        } catch (e: Exception) {}
    }

    private suspend fun uploadFile(client: OkHttpClient, ip: String, pin: String, gameId: String, fileName: String, localTime: Long, fileDoc: DocumentFile) {
        try {
            val cacheFile = File(cacheDir, fileName)
            contentResolver.openInputStream(fileDoc.uri)?.use { inStream -> FileOutputStream(cacheFile).use { inStream.copyTo(it) } }
            val requestBody = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("mtime", localTime.toString()).addFormDataPart("file", fileName, cacheFile.asRequestBody("application/octet-stream".toMediaTypeOrNull())).build()
            val request = Request.Builder().url("http://$ip:5000/upload/$gameId/$fileName").addHeader("X-Sync-Pin", pin).post(requestBody).build()
            client.newCall(request).execute()
            cacheFile.delete()
        } catch (e: Exception) {}
    }
}
