package com.sleepy.agent.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Simple camera capture utility for taking photos to send to the multimodal model.
 */
class CameraCapture(private val context: Context) {
    
    companion object {
        private const val TAG = "CameraCapture"
    }
    
    private var imageCapture: ImageCapture? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    
    /**
     * Starts camera preview and returns a capture function.
     * Call this from a Compose AndroidView or similar.
     */
    suspend fun startCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: androidx.camera.view.PreviewView
    ): Result<Unit> = withContext(Dispatchers.Main) {
        try {
            val cameraProvider = suspendCoroutine<ProcessCameraProvider> { continuation ->
                ProcessCameraProvider.getInstance(context).apply {
                    addListener({
                        try {
                            continuation.resume(get())
                        } catch (e: Exception) {
                            continuation.resumeWithException(e)
                        }
                    }, ContextCompat.getMainExecutor(context))
                }
            }
            
            // Set up preview
            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            
            // Set up image capture
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            
            // Select back camera
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            
            // Unbind all use cases and rebind
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture
            )
            
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start camera", e)
            Result.failure(e)
        }
    }
    
    /**
     * Captures a photo and returns it as a Bitmap.
     */
    suspend fun capturePhoto(): Result<Bitmap> = withContext(Dispatchers.IO) {
        try {
            val capture = imageCapture ?: throw IllegalStateException("Camera not started")
            
            val bitmap = suspendCoroutine<Bitmap> { continuation ->
                capture.takePicture(
                    cameraExecutor,
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            try {
                                val bitmap = imageProxyToBitmap(image)
                                image.close()
                                continuation.resume(bitmap)
                            } catch (e: Exception) {
                                image.close()
                                continuation.resumeWithException(e)
                            }
                        }
                        
                        override fun onError(exception: ImageCaptureException) {
                            continuation.resumeWithException(exception)
                        }
                    }
                )
            }
            
            Result.success(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture photo", e)
            Result.failure(e)
        }
    }
    
    /**
     * Converts ImageProxy to Bitmap, handling rotation.
     */
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        
        val bitmap = when (image.format) {
            ImageFormat.JPEG -> {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            ImageFormat.YUV_420_888 -> {
                // Convert YUV to Bitmap
                val yuvImage = YuvImage(
                    bytes,
                    ImageFormat.NV21,
                    image.width,
                    image.height,
                    null
                )
                val out = ByteArrayOutputStream()
                yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)
                BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
            }
            else -> throw IllegalArgumentException("Unsupported image format: ${image.format}")
        }
        
        // Handle rotation
        val matrix = Matrix()
        matrix.postRotate(image.imageInfo.rotationDegrees.toFloat())
        
        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
    }
    
    fun shutdown() {
        cameraExecutor.shutdown()
    }
}
