package com.sleepy.agent.download

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * WorkManager worker for downloading the Gemma 4 model.
 * Handles resumable downloads and reports progress.
 */
class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    companion object {
        private const val TAG = "ModelDownloadWorker"
        const val PROGRESS = "progress"
        const val BYTES_DOWNLOADED = "bytes_downloaded"
        const val ERROR_MSG = "error_msg"
        private const val CHUNK_SIZE = 8192 // 8KB chunks
        private const val PROGRESS_UPDATE_INTERVAL = 500L // Update every 500ms
    }
    
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // Get variant from input data (e2b or e4b)
            val variant = inputData.getString("MODEL_VARIANT") ?: "e2b"
            
            val modelFile = when (variant) {
                "e2b" -> ModelDownloadManager.getE2BModelFile(applicationContext)
                "e4b" -> ModelDownloadManager.getE4BModelFile(applicationContext)
                else -> ModelDownloadManager.getE2BModelFile(applicationContext)
            }
            
            val tempFile = File(modelFile.parentFile, "${modelFile.name}.tmp")
            
            // Check if we have a partial download to resume
            val resumeFrom = if (tempFile.exists()) tempFile.length() else 0L
            val totalSize = when (variant) {
                "e2b" -> ModelDownloadManager.E2B_MODEL_SIZE_BYTES
                "e4b" -> ModelDownloadManager.E4B_MODEL_SIZE_BYTES
                else -> ModelDownloadManager.E2B_MODEL_SIZE_BYTES
            }
            
            val modelUrl = when (variant) {
                "e2b" -> ModelDownloadManager.E2B_MODEL_URL
                "e4b" -> ModelDownloadManager.E4B_MODEL_URL
                else -> ModelDownloadManager.E2B_MODEL_URL
            }
            
            Log.d(TAG, "Starting download of $variant from byte $resumeFrom, total: $totalSize")
            
            val url = URL(modelUrl)
            val connection = url.openConnection() as HttpURLConnection
            
            // Set up connection
            connection.apply {
                setRequestProperty("User-Agent", "SleepyAgent/1.0")
                connectTimeout = 30000
                readTimeout = 30000
                
                // Resume partial download if exists
                if (resumeFrom > 0) {
                    setRequestProperty("Range", "bytes=$resumeFrom-")
                    Log.d(TAG, "Resuming download from $resumeFrom")
                }
            }
            
            connection.connect()
            
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                val error = "HTTP error: $responseCode"
                Log.e(TAG, error)
                return@withContext Result.failure(
                    Data.Builder().putString(ERROR_MSG, error).build()
                )
            }
            
            // Get content length (might be partial content length or full)
            val contentLength = connection.contentLengthLong
            val actualTotalSize = if (responseCode == HttpURLConnection.HTTP_PARTIAL && resumeFrom > 0 && contentLength > 0) {
                resumeFrom + contentLength
            } else if (contentLength > 0) {
                contentLength
            } else {
                totalSize // Fallback to expected size
            }
            
            Log.d(TAG, "Content length: $contentLength, actual total: $actualTotalSize")
            
            connection.inputStream.use { input ->
                FileOutputStream(tempFile, resumeFrom > 0).use { output ->
                    val buffer = ByteArray(CHUNK_SIZE)
                    var bytesRead: Int
                    var totalRead = resumeFrom
                    var lastProgressUpdate = System.currentTimeMillis()
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        // Check if work was cancelled
                        if (!coroutineContext.isActive) {
                            Log.d(TAG, "Download cancelled")
                            return@withContext Result.failure()
                        }
                        
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        
                        // Update progress periodically
                        val now = System.currentTimeMillis()
                        if (now - lastProgressUpdate > PROGRESS_UPDATE_INTERVAL) {
                            val progress = totalRead.toFloat() / actualTotalSize
                            setProgress(
                                Data.Builder()
                                    .putFloat(PROGRESS, progress)
                                    .putLong(BYTES_DOWNLOADED, totalRead)
                                    .build()
                            )
                            lastProgressUpdate = now
                            Log.d(TAG, "Progress: ${(progress * 100).toInt()}%")
                        }
                    }
                    
                    // Final progress update
                    setProgress(
                        Data.Builder()
                            .putFloat(PROGRESS, 1f)
                            .putLong(BYTES_DOWNLOADED, totalRead)
                            .build()
                    )
                }
            }
            
            connection.disconnect()
            
            // Verify download completed
            if (tempFile.length() < actualTotalSize - 1000) { // Allow 1KB tolerance
                val error = "Download incomplete: ${tempFile.length()} / $actualTotalSize"
                Log.e(TAG, error)
                return@withContext Result.failure(
                    Data.Builder().putString(ERROR_MSG, error).build()
                )
            }
            
            // Move temp file to final location
            if (modelFile.exists()) {
                modelFile.delete()
            }
            tempFile.renameTo(modelFile)
            
            Log.d(TAG, "Download completed: ${modelFile.absolutePath}")
            Result.success()
            
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            Result.failure(
                Data.Builder()
                    .putString(ERROR_MSG, e.message ?: "Unknown error")
                    .build()
            )
        }
    }
}
