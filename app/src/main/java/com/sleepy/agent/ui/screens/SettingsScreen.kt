package com.sleepy.agent.ui.screens

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sleepy.agent.download.ModelDownloadManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel,
    onRequestOverlayPermission: () -> Unit = {},
    onRequestMediaProjection: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                viewModel.onModelFileSelected(uri.toString())
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadSettings()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Model Section
            ModelSection(
                uiState = uiState,
                viewModel = viewModel,
                onSelectModel = {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "*/*"))
                    }
                    filePickerLauncher.launch(intent)
                },
                onLoadModel = { viewModel.loadModel() }
            )

            HorizontalDivider()

            // Server Section
            ServerSection(
                searchServerUrl = uiState.searchServerUrl,
                delegateServerUrl = uiState.delegateServerUrl,
                onSearchServerChange = { viewModel.setSearchServerUrl(it) },
                onDelegateServerChange = { viewModel.setDelegateServerUrl(it) }
            )

            HorizontalDivider()

            // TTS Section
            TtsSection(
                enabled = uiState.ttsEnabled,
                autoMode = uiState.ttsAutoMode,
                onEnabledChange = { viewModel.setTtsEnabled(it) },
                onAutoModeChange = { viewModel.setTtsAutoMode(it) }
            )

            HorizontalDivider()

            // Experimental Features
            ExperimentalSection(
                floatingButtonEnabled = uiState.floatingButtonEnabled,
                overlayPermissionGranted = Settings.canDrawOverlays(context),
                onFloatingButtonChange = { enabled ->
                    if (enabled && !Settings.canDrawOverlays(context)) {
                        onRequestOverlayPermission()
                    }
                    viewModel.setFloatingButtonEnabled(enabled)
                },
                onRequestMediaProjection = onRequestMediaProjection
            )

            HorizontalDivider()

            // Device Info
            DeviceSection()

            HorizontalDivider()

            // About
            AboutSection()
        }
    }
}

@Composable
private fun ModelSection(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    onSelectModel: () -> Unit,
    onLoadModel: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Models",
            style = MaterialTheme.typography.titleMedium
        )

        // Current model status
        when {
            uiState.isLoadingModel -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Loading model...", style = MaterialTheme.typography.bodyMedium)
                }
            }
            uiState.modelLoaded -> {
                Text(
                    text = "✓ Model loaded: ${uiState.modelPath.takeLast(40)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            uiState.modelLoadError != null -> {
                Text(
                    text = "✗ ${uiState.modelLoadError}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        // Download progress (for variant downloads)
        if (uiState.downloadingVariant != null) {
            Column {
                LinearProgressIndicator(
                    progress = { uiState.downloadProgress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Downloading ${uiState.downloadingVariant.uppercase()}: ${uiState.downloadProgress.toInt()}%",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // Gemma 4 E2B Card
        ModelCard(
            name = "Gemma 4 E2B",
            description = "2B params, fastest, good for most tasks (~2.7GB)",
            isDownloaded = uiState.isE2BDownloaded,
            isSelected = uiState.selectedModelVariant == "e2b",
            onSelect = { viewModel.selectModelVariant("e2b") },
            onDownload = { viewModel.downloadModel("e2b") },
            onDelete = { viewModel.deleteModel("e2b") },
            isDownloading = uiState.downloadingVariant == "e2b",
            enabled = !uiState.isLoadingModel
        )

        // Gemma 4 E4B Card
        ModelCard(
            name = "Gemma 4 E4B",
            description = "4B params, better quality, slower (~4.5GB)",
            isDownloaded = uiState.isE4BDownloaded,
            isSelected = uiState.selectedModelVariant == "e4b",
            onSelect = { viewModel.selectModelVariant("e4b") },
            onDownload = { viewModel.downloadModel("e4b") },
            onDelete = { viewModel.deleteModel("e4b") },
            isDownloading = uiState.downloadingVariant == "e4b",
            enabled = !uiState.isLoadingModel
        )

        // Select from file button
        OutlinedButton(
            onClick = onSelectModel,
            enabled = !uiState.isLoadingModel,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Select from file")
        }

        // Load button (if model selected but not loaded)
        if (uiState.modelPath.isNotEmpty() && !uiState.modelLoaded && !uiState.isLoadingModel) {
            Button(
                onClick = onLoadModel,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Load Selected Model")
            }
        }
    }
}

@Composable
private fun ModelCard(
    name: String,
    description: String,
    isDownloaded: Boolean,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    isDownloading: Boolean,
    enabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.surfaceVariant
        ),
        border = if (isSelected) {
            androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else null
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Model info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isDownloaded) {
                    Text(
                        text = "✓",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Select button (only enabled if downloaded)
                OutlinedButton(
                    onClick = onSelect,
                    enabled = enabled && isDownloaded && !isSelected,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isSelected) "Selected" else "Select")
                }

                // Download or Delete button
                if (isDownloaded) {
                    OutlinedButton(
                        onClick = onDelete,
                        enabled = enabled && !isDownloading,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Delete")
                    }
                } else {
                    Button(
                        onClick = onDownload,
                        enabled = enabled && !isDownloading,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isDownloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(if (isDownloading) "..." else "Download")
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerSection(
    searchServerUrl: String,
    delegateServerUrl: String,
    onSearchServerChange: (String) -> Unit,
    onDelegateServerChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Servers",
            style = MaterialTheme.typography.titleMedium
        )

        // Search Server
        OutlinedTextField(
            value = searchServerUrl,
            onValueChange = onSearchServerChange,
            label = { Text("Search Server (SearXNG)") },
            placeholder = { Text("http://your-server:8080") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // Delegate Server
        OutlinedTextField(
            value = delegateServerUrl,
            onValueChange = onDelegateServerChange,
            label = { Text("Delegate Server (LLM)") },
            placeholder = { Text("http://your-server:7777") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Text(
            text = "Leave empty to disable server features. URLs are saved automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TtsSection(
    enabled: Boolean,
    autoMode: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onAutoModeChange: (Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Text to Speech",
            style = MaterialTheme.typography.titleMedium
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Enable TTS")
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Auto-detect mode (voice → speak, text → silent)")
            Switch(
                checked = autoMode,
                onCheckedChange = onAutoModeChange,
                enabled = enabled
            )
        }
    }
}

@Composable
private fun ExperimentalSection(
    floatingButtonEnabled: Boolean,
    overlayPermissionGranted: Boolean,
    onFloatingButtonChange: (Boolean) -> Unit,
    onRequestMediaProjection: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "🧪 Experimental Features",
                style = MaterialTheme.typography.titleMedium
            )

            // Floating Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Floating Button")
                    Text(
                        "Tap to open • Hold for screenshot",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = floatingButtonEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled && !overlayPermissionGranted) {
                            // Will request permission outside
                        }
                        onFloatingButtonChange(enabled)
                    }
                )
            }

            if (floatingButtonEnabled && !overlayPermissionGranted) {
                Text(
                    text = "⚠️ Overlay permission required. Please enable in system settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // MediaProjection button (for screenshot functionality)
            if (floatingButtonEnabled && overlayPermissionGranted) {
                OutlinedButton(
                    onClick = onRequestMediaProjection,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Enable Screen Capture (for screenshots)")
                }
            }

            Text(
                text = "Experimental features may be unstable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DeviceSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memoryInfo = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memoryInfo)
    
    // Total RAM
    val totalRam = memoryInfo.totalMem
    val totalRamGb = totalRam / (1024 * 1024 * 1024)
    
    // Available RAM
    val availableRam = memoryInfo.availMem
    val availableRamGb = availableRam / (1024 * 1024 * 1024)
    val availableRamMb = availableRam / (1024 * 1024)
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Your Device",
                style = MaterialTheme.typography.titleMedium
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Total RAM",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "${totalRamGb} GB",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Available RAM",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = if (availableRamGb > 0) "${availableRamGb} GB" else "${availableRamMb} MB",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            // Device model
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Device",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "${Build.MANUFACTURER} ${Build.MODEL}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // SDK version
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Android",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "API ${Build.VERSION.SDK_INT}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AboutSection() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Sleepy Agent",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = "Local LLM inference with Gemma 4",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
