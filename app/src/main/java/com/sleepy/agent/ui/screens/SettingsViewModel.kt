package com.sleepy.agent.ui.screens

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.sleepy.agent.download.ModelDownloadManager
import com.sleepy.agent.download.ModelDownloadWorker
import com.sleepy.agent.inference.LlmEngine
import com.sleepy.agent.settings.ModelSource
import com.sleepy.agent.settings.UserSettings
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

data class SettingsUiState(
    val modelSource: ModelSource = ModelSource.FILE_PATH,
    val modelPath: String = "",
    val modelUri: String = "",
    val serverEnabled: Boolean = false,
    val searchServerUrl: String = "",
    val delegateServerUrl: String = "",
    val searchServerHealthy: Boolean? = null,
    val delegateServerHealthy: Boolean? = null,
    val serverModels: List<String> = emptyList(),
    val selectedModel: String = "",
    val isCheckingSearchHealth: Boolean = false,
    val isCheckingDelegateHealth: Boolean = false,
    val searchHealthError: String? = null,
    val delegateHealthError: String? = null,
    val isLoading: Boolean = true,
    val isLoadingModel: Boolean = false,
    val modelLoaded: Boolean = false,
    val modelLoadError: String? = null,
    // TTS settings
    val ttsEnabled: Boolean = true,
    val ttsAutoMode: Boolean = true,
    // Download state
    val downloadState: ModelDownloadManager.DownloadState = ModelDownloadManager.DownloadState.Idle,
    val downloadProgress: Float = 0f,
    val downloadedSize: String = "0 MB",
    val isModelDownloaded: Boolean = false,
    // Experimental features
    val floatingButtonEnabled: Boolean = false,
    // Model variants (E2B and E4B)
    val isE2BDownloaded: Boolean = false,
    val isE4BDownloaded: Boolean = false,
    val selectedModelVariant: String = "e2b", // "e2b" or "e4b"
    val downloadingVariant: String? = null
)

class SettingsViewModel(
    private val userSettings: UserSettings,
    private val httpClient: HttpClient,
    private val llmEngine: LlmEngine,
    private val context: Context,
    private val downloadManager: ModelDownloadManager = ModelDownloadManager(context)
) : ViewModel() {

    companion object {
        private const val TAG = "SettingsViewModel"
    }

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
        observeDownloadState()
        observeVariantDownloads()
        checkModelDownloaded()
    }
    
    private fun observeVariantDownloads() {
        val workManager = WorkManager.getInstance(context)
        
        viewModelScope.launch {
            // Observe E2B download work
            workManager.getWorkInfosByTagFlow("model_download_e2b").collect { workInfos ->
                val workInfo = workInfos.firstOrNull()
                when (workInfo?.state) {
                    androidx.work.WorkInfo.State.RUNNING -> {
                        val progress = workInfo.progress.getFloat(ModelDownloadWorker.PROGRESS, 0f)
                        _uiState.value = _uiState.value.copy(
                            downloadingVariant = "e2b",
                            downloadProgress = progress * 100
                        )
                    }
                    androidx.work.WorkInfo.State.SUCCEEDED -> {
                        _uiState.value = _uiState.value.copy(
                            downloadingVariant = null,
                            isE2BDownloaded = ModelDownloadManager.isE2BDownloaded(context),
                            downloadProgress = 100f
                        )
                    }
                    androidx.work.WorkInfo.State.FAILED, androidx.work.WorkInfo.State.CANCELLED -> {
                        _uiState.value = _uiState.value.copy(
                            downloadingVariant = null
                        )
                    }
                    else -> {}
                }
            }
        }
        
        viewModelScope.launch {
            // Observe E4B download work
            workManager.getWorkInfosByTagFlow("model_download_e4b").collect { workInfos ->
                val workInfo = workInfos.firstOrNull()
                when (workInfo?.state) {
                    androidx.work.WorkInfo.State.RUNNING -> {
                        val progress = workInfo.progress.getFloat(ModelDownloadWorker.PROGRESS, 0f)
                        _uiState.value = _uiState.value.copy(
                            downloadingVariant = "e4b",
                            downloadProgress = progress * 100
                        )
                    }
                    androidx.work.WorkInfo.State.SUCCEEDED -> {
                        _uiState.value = _uiState.value.copy(
                            downloadingVariant = null,
                            isE4BDownloaded = ModelDownloadManager.isE4BDownloaded(context),
                            downloadProgress = 100f
                        )
                    }
                    androidx.work.WorkInfo.State.FAILED, androidx.work.WorkInfo.State.CANCELLED -> {
                        _uiState.value = _uiState.value.copy(
                            downloadingVariant = null
                        )
                    }
                    else -> {}
                }
            }
        }
    }

    private fun observeDownloadState() {
        viewModelScope.launch {
            downloadManager.downloadState.collectLatest { state ->
                _uiState.value = _uiState.value.copy(
                    downloadState = state,
                    downloadProgress = when (state) {
                        is ModelDownloadManager.DownloadState.Downloading -> state.progress
                        is ModelDownloadManager.DownloadState.Completed -> 1f
                        else -> 0f
                    }
                )
                
                if (state is ModelDownloadManager.DownloadState.Completed || 
                    state is ModelDownloadManager.DownloadState.Downloading) {
                    _uiState.value = _uiState.value.copy(
                        downloadedSize = ModelDownloadManager.getDownloadedSize(context)
                    )
                }
                
                if (state is ModelDownloadManager.DownloadState.Completed) {
                    checkModelDownloaded()
                    val modelFile = ModelDownloadManager.getModelFile(context)
                    setModelPath(modelFile.absolutePath)
                }
            }
        }
    }
    
    private fun checkModelDownloaded() {
        val isDownloaded = ModelDownloadManager.isModelDownloaded(context)
        _uiState.value = _uiState.value.copy(
            isModelDownloaded = isDownloaded,
            downloadedSize = ModelDownloadManager.getDownloadedSize(context)
        )
    }

    fun loadSettings() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            
            val modelSource = userSettings.modelSource.first()
            val modelPath = userSettings.modelPath.first()
            val serverEnabled = userSettings.enableServerDelegation.first()
            val searchServerUrl = userSettings.searchServerUrl.first()
            val delegateServerUrl = userSettings.delegateServerUrl.first()
            val selectedModel = userSettings.selectedServerModel.first()
            val ttsEnabled = userSettings.ttsEnabled.first()
            val ttsAutoMode = userSettings.ttsAutoMode.first()
            val floatingButtonEnabled = userSettings.floatingButtonEnabled.first()
            
            val finalModelPath = if (modelPath.isEmpty() && ModelDownloadManager.isModelDownloaded(context)) {
                ModelDownloadManager.getModelFile(context).absolutePath
            } else {
                modelPath
            }
            
            _uiState.value = SettingsUiState(
                modelSource = modelSource,
                modelPath = finalModelPath,
                modelUri = finalModelPath,
                serverEnabled = serverEnabled,
                searchServerUrl = searchServerUrl,
                delegateServerUrl = delegateServerUrl,
                selectedModel = selectedModel,
                isLoading = false,
                modelLoaded = llmEngine.isLoaded(),
                isModelDownloaded = ModelDownloadManager.isModelDownloaded(context),
                downloadedSize = ModelDownloadManager.getDownloadedSize(context),
                ttsEnabled = ttsEnabled,
                ttsAutoMode = ttsAutoMode,
                floatingButtonEnabled = floatingButtonEnabled,
                isE2BDownloaded = ModelDownloadManager.isE2BDownloaded(context),
                isE4BDownloaded = ModelDownloadManager.isE4BDownloaded(context)
            )
        }
    }

    fun setModelSource(source: ModelSource) {
        _uiState.value = _uiState.value.copy(modelSource = source)
        viewModelScope.launch {
            userSettings.setModelSource(source)
        }
    }

    fun setModelPath(path: String) {
        _uiState.value = _uiState.value.copy(modelPath = path)
        viewModelScope.launch {
            userSettings.setModelPath(path)
        }
    }

    fun setModelUri(uriString: String) {
        _uiState.value = _uiState.value.copy(
            modelUri = uriString,
            modelPath = uriString,
            modelLoaded = false,
            modelLoadError = null
        )
        viewModelScope.launch {
            userSettings.setModelPath(uriString)
        }
    }

    fun loadModel() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingModel = true,
                modelLoadError = null
            )
            
            try {
                val modelPath = if (_uiState.value.modelPath.isEmpty() && 
                                   ModelDownloadManager.isModelDownloaded(context)) {
                    ModelDownloadManager.getModelFile(context).absolutePath
                } else {
                    _uiState.value.modelPath
                }
                
                if (modelPath.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isLoadingModel = false,
                        modelLoadError = "No model file selected"
                    )
                    return@launch
                }

                val finalPath = getPathFromUri(modelPath) ?: modelPath
                
                if (finalPath == null) {
                    _uiState.value = _uiState.value.copy(
                        isLoadingModel = false,
                        modelLoadError = "Cannot access model file. Please select a valid .litertlm file."
                    )
                    return@launch
                }

                val modelFile = File(finalPath)
                if (!modelFile.exists()) {
                    _uiState.value = _uiState.value.copy(
                        isLoadingModel = false,
                        modelLoadError = "Model file does not exist: $finalPath"
                    )
                    return@launch
                }

                if (modelFile.length() == 0L) {
                    _uiState.value = _uiState.value.copy(
                        isLoadingModel = false,
                        modelLoadError = "Model file is empty"
                    )
                    return@launch
                }

                val magicBytes = modelFile.inputStream().use { it.readNBytes(8) }
                val magicString = String(magicBytes)
                Log.d(TAG, "File magic bytes: $magicString")
                
                if (!magicString.startsWith("LITERTLM")) {
                    _uiState.value = _uiState.value.copy(
                        isLoadingModel = false,
                        modelLoadError = "Invalid file format. Expected LITERTLM, got: $magicString"
                    )
                    return@launch
                }

                Log.d(TAG, "Loading model from: $finalPath (${modelFile.length()} bytes)")

                val result = llmEngine.loadModel(finalPath)
                
                result.fold(
                    onSuccess = {
                        _uiState.value = _uiState.value.copy(
                            isLoadingModel = false,
                            modelLoaded = true,
                            modelLoadError = null,
                            modelPath = finalPath
                        )
                        userSettings.setModelPath(finalPath)
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Failed to load model", error)
                        _uiState.value = _uiState.value.copy(
                            isLoadingModel = false,
                            modelLoaded = false,
                            modelLoadError = "Failed to load model: ${error.message}"
                        )
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error loading model", e)
                _uiState.value = _uiState.value.copy(
                    isLoadingModel = false,
                    modelLoaded = false,
                    modelLoadError = "Error: ${e.message}"
                )
            }
        }
    }

    private suspend fun getPathFromUri(uriString: String): String? {
        return try {
            val uri = Uri.parse(uriString)
            
            Log.d(TAG, "URI: $uri")
            
            if (uri.scheme == "file") {
                return uri.path
            }
            
            var displayName: String? = null
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        displayName = cursor.getString(nameIndex)
                    }
                }
            }
            
            val originalFileName = displayName ?: uri.lastPathSegment?.substringAfterLast("/") ?: "model.litertlm"
            val fileName = if (originalFileName.endsWith(".litertlm", ignoreCase = true)) {
                originalFileName
            } else {
                "$originalFileName.litertlm"
            }
            
            val modelsDir = File(context.filesDir, "models")
            modelsDir.mkdirs()
            
            val destFile = File(modelsDir, fileName)
            
            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return null
            
            if (!destFile.exists()) return null
            
            destFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error copying file", e)
            null
        }
    }

    fun startModelDownload() {
        downloadManager.startDownload()
    }
    
    fun cancelDownload() {
        downloadManager.cancelDownload()
    }
    
    fun deleteDownloadedModel() {
        downloadManager.deleteModel()
        checkModelDownloaded()
    }

    fun setServerEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(serverEnabled = enabled)
        viewModelScope.launch {
            userSettings.setEnableServerDelegation(enabled)
        }
    }
    
    fun setSearchServerUrl(url: String) {
        _uiState.value = _uiState.value.copy(searchServerUrl = url, searchServerHealthy = null)
        viewModelScope.launch {
            userSettings.setSearchServerUrl(url)
        }
    }
    
    fun setDelegateServerUrl(url: String) {
        _uiState.value = _uiState.value.copy(delegateServerUrl = url, delegateServerHealthy = null)
        viewModelScope.launch {
            userSettings.setDelegateServerUrl(url)
        }
    }

    fun saveSearchServerUrl() {
        viewModelScope.launch {
            userSettings.setSearchServerUrl(_uiState.value.searchServerUrl)
        }
    }
    
    fun saveDelegateServerUrl() {
        viewModelScope.launch {
            userSettings.setDelegateServerUrl(_uiState.value.delegateServerUrl)
        }
    }

    fun checkSearchServerHealth() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isCheckingSearchHealth = true,
                searchHealthError = null,
                searchServerHealthy = null
            )
            
            try {
                val url = _uiState.value.searchServerUrl.trim().trimEnd('/')
                val response: HttpResponse = httpClient.get("$url/search?q=test&format=json")
                
                _uiState.value = _uiState.value.copy(
                    searchServerHealthy = response.status.isSuccess(),
                    isCheckingSearchHealth = false
                )
                
                if (response.status.isSuccess()) {
                    userSettings.setSearchServerUrl(url)
                } else {
                    _uiState.value = _uiState.value.copy(
                        searchHealthError = "Server returned ${response.status}"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    searchServerHealthy = false,
                    isCheckingSearchHealth = false,
                    searchHealthError = e.message ?: "Unknown error"
                )
            }
        }
    }
    
    fun checkDelegateServerHealth() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isCheckingDelegateHealth = true,
                delegateHealthError = null,
                delegateServerHealthy = null
            )
            
            try {
                val url = _uiState.value.delegateServerUrl.trim().trimEnd('/')
                val response: HttpResponse = httpClient.get("$url/v1/models")
                
                if (response.status.isSuccess()) {
                    val body = response.bodyAsText()
                    val models = parseModelsFromResponse(body)
                    
                    _uiState.value = _uiState.value.copy(
                        delegateServerHealthy = true,
                        serverModels = models,
                        isCheckingDelegateHealth = false,
                        selectedModel = models.firstOrNull() ?: ""
                    )
                    
                    userSettings.setDelegateServerUrl(url)
                    models.firstOrNull()?.let { userSettings.setSelectedServerModel(it) }
                } else {
                    _uiState.value = _uiState.value.copy(
                        delegateServerHealthy = false,
                        isCheckingDelegateHealth = false,
                        delegateHealthError = "Server returned ${response.status}"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    delegateServerHealthy = false,
                    isCheckingDelegateHealth = false,
                    delegateHealthError = e.message ?: "Unknown error"
                )
            }
        }
    }

    private fun parseModelsFromResponse(json: String): List<String> {
        return try {
            val jsonElement = Json.parseToJsonElement(json)
            val jsonObject = jsonElement.jsonObject
            val data = jsonObject["data"]?.jsonArray
            data?.mapNotNull { 
                it.jsonObject["id"]?.jsonPrimitive?.content 
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    fun setSelectedModel(model: String) {
        _uiState.value = _uiState.value.copy(selectedModel = model)
        viewModelScope.launch {
            userSettings.setSelectedServerModel(model)
        }
    }
    
    // TTS settings
    fun setTtsEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(ttsEnabled = enabled)
        viewModelScope.launch {
            userSettings.setTtsEnabled(enabled)
        }
    }
    
    fun setTtsAutoMode(auto: Boolean) {
        _uiState.value = _uiState.value.copy(ttsAutoMode = auto)
        viewModelScope.launch {
            userSettings.setTtsAutoMode(auto)
        }
    }
    
    // Floating button (experimental)
    fun setFloatingButtonEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(floatingButtonEnabled = enabled)
        viewModelScope.launch {
            userSettings.setFloatingButtonEnabled(enabled)
        }
    }
    
    fun onModelFileSelected(uri: String) {
        setModelUri(uri)
    }
    
    // Model variant selection (E2B / E4B)
    fun selectModelVariant(variant: String) {
        _uiState.value = _uiState.value.copy(selectedModelVariant = variant)
        // Set the model path based on variant
        val path = when (variant) {
            "e2b" -> ModelDownloadManager.getE2BModelFile(context)?.absolutePath ?: ""
            "e4b" -> ModelDownloadManager.getE4BModelFile(context)?.absolutePath ?: ""
            else -> ""
        }
        if (path.isNotEmpty()) {
            setModelPath(path)
        }
    }
    
    fun downloadModel(variant: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(downloadingVariant = variant)
            val result = ModelDownloadManager.downloadModelVariant(context, variant)
            _uiState.value = _uiState.value.copy(
                downloadingVariant = null,
                isE2BDownloaded = ModelDownloadManager.isE2BDownloaded(context),
                isE4BDownloaded = ModelDownloadManager.isE4BDownloaded(context)
            )
        }
    }
    
    fun deleteModel(variant: String) {
        viewModelScope.launch {
            ModelDownloadManager.deleteModelVariant(context, variant)
            _uiState.value = _uiState.value.copy(
                isE2BDownloaded = ModelDownloadManager.isE2BDownloaded(context),
                isE4BDownloaded = ModelDownloadManager.isE4BDownloaded(context)
            )
        }
    }
}
