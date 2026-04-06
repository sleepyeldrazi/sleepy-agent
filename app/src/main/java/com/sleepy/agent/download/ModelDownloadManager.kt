package com.sleepy.agent.download

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Manages downloading the Gemma 4 model from HuggingFace.
 * Uses WorkManager for reliable background downloads that persist across app updates.
 */
class ModelDownloadManager(private val context: Context) {
    
    companion object {
        private const val TAG = "ModelDownloadManager"
        
        // E2B Model
        const val E2B_MODEL_URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
        const val E2B_MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
        const val E2B_MODEL_SIZE_BYTES = 2717263232L // ~2.53 GB
        
        // E4B Model
        const val E4B_MODEL_URL = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm"
        const val E4B_MODEL_FILE_NAME = "gemma-4-E4B-it.litertlm"
        const val E4B_MODEL_SIZE_BYTES = 4831838208L // ~4.5 GB
        
        fun getModelsDir(context: Context): File {
            return File(context.getExternalFilesDir(null), "models").apply { mkdirs() }
        }
        
        // E2B Methods
        fun getE2BModelFile(context: Context): File {
            return File(getModelsDir(context), E2B_MODEL_FILE_NAME)
        }
        
        fun isE2BDownloaded(context: Context): Boolean {
            val file = getE2BModelFile(context)
            return file.exists() && file.length() > 100000000L // At least 100MB (partial download check)
        }
        
        // E4B Methods
        fun getE4BModelFile(context: Context): File {
            return File(getModelsDir(context), E4B_MODEL_FILE_NAME)
        }
        
        fun isE4BDownloaded(context: Context): Boolean {
            val file = getE4BModelFile(context)
            return file.exists() && file.length() > 100000000L // At least 100MB (partial download check)
        }
        
        // Legacy methods (for backward compatibility, default to E2B)
        fun getModelFile(context: Context): File = getE2BModelFile(context)
        fun isModelDownloaded(context: Context): Boolean = isE2BDownloaded(context)
        
        fun getDownloadProgress(context: Context): Float {
            val file = getE2BModelFile(context)
            return if (!file.exists()) 0f else (file.length().toFloat() / E2B_MODEL_SIZE_BYTES).coerceIn(0f, 1f)
        }
        
        fun getDownloadedSize(context: Context): String {
            val file = getE2BModelFile(context)
            return formatBytes(file.length())
        }
        
        fun formatBytes(bytes: Long): String {
            val mb = bytes / (1024 * 1024)
            val gb = mb / 1024f
            return if (gb >= 1) String.format("%.2f GB", gb) else "$mb MB"
        }
        
        // New variant-aware methods
        fun downloadModelVariant(context: Context, variant: String): Boolean {
            val url = when (variant) {
                "e2b" -> E2B_MODEL_URL
                "e4b" -> E4B_MODEL_URL
                else -> return false
            }
            
            // Start download via WorkManager
            val workManager = WorkManager.getInstance(context)
            
            val workRequest = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(workDataOf(
                    "MODEL_URL" to url,
                    "MODEL_VARIANT" to variant
                ))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresStorageNotLow(true)
                        .build()
                )
                .addTag("model_download_$variant")
                .build()
            
            workManager.enqueueUniqueWork(
                "model_download_$variant",
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
            
            return true
        }
        
        fun deleteModelVariant(context: Context, variant: String) {
            val file = when (variant) {
                "e2b" -> getE2BModelFile(context)
                "e4b" -> getE4BModelFile(context)
                else -> return
            }
            if (file.exists()) {
                file.delete()
            }
        }
    }
    
    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()
    
    sealed class DownloadState {
        object Idle : DownloadState()
        object Checking : DownloadState()
        data class Downloading(val progress: Float, val bytesDownloaded: Long) : DownloadState()
        object Completed : DownloadState()
        data class Error(val message: String) : DownloadState()
    }
    
    /**
     * Starts the model download using WorkManager for reliability.
     */
    fun startDownload(): androidx.work.Operation {
        _downloadState.value = DownloadState.Checking
        
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()
        
        val downloadWork = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag("model_download")
            .build()
        
        val workManager = WorkManager.getInstance(context)
        
        // Observe work progress
        workManager.getWorkInfoByIdLiveData(downloadWork.id).observeForever { workInfo ->
            when (workInfo?.state) {
                WorkInfo.State.RUNNING -> {
                    val progress = workInfo.progress.getFloat(ModelDownloadWorker.PROGRESS, 0f)
                    val bytes = workInfo.progress.getLong(ModelDownloadWorker.BYTES_DOWNLOADED, 0L)
                    _downloadState.value = DownloadState.Downloading(progress, bytes)
                }
                WorkInfo.State.SUCCEEDED -> {
                    _downloadState.value = DownloadState.Completed
                }
                WorkInfo.State.FAILED -> {
                    val error = workInfo.outputData.getString(ModelDownloadWorker.ERROR_MSG) ?: "Download failed"
                    _downloadState.value = DownloadState.Error(error)
                }
                WorkInfo.State.CANCELLED -> {
                    _downloadState.value = DownloadState.Idle
                }
                else -> {}
            }
        }
        
        return workManager.enqueue(downloadWork)
    }
    
    fun cancelDownload() {
        WorkManager.getInstance(context).cancelAllWorkByTag("model_download")
        _downloadState.value = DownloadState.Idle
    }
    
    fun deleteModel() {
        val file = getModelFile(context)
        if (file.exists()) {
            file.delete()
            Log.d(TAG, "Deleted model file")
        }
        _downloadState.value = DownloadState.Idle
    }
}
