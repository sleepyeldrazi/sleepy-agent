package com.sleepy.agent.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.sleepy.agent.MainActivity
import com.sleepy.agent.R
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Floating button service that provides an overlay button for quick access.
 * 
 * Features:
 * - Tap: Opens Sleepy Agent (compact mode)
 * - Long press: Takes screenshot and opens Sleepy Agent
 * - Drag: Moves button around screen
 */
class FloatingButtonService : Service(), LifecycleOwner {
    
    private lateinit var windowManager: WindowManager
    private var floatingButton: View? = null
    private var params: WindowManager.LayoutParams? = null
    
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var longPressHandler: Handler? = null
    private var isLongPress = false
    
    private lateinit var lifecycleRegistry: LifecycleRegistry
    
    companion object {
        private const val TAG = "FloatingButtonService"
        private const val LONG_PRESS_DURATION = 800L // ms
        private const val DRAG_THRESHOLD = 10f // pixels
        private const val CHANNEL_ID = "floating_button_channel"
        private const val NOTIFICATION_ID = 1001
        
        var mediaProjection: MediaProjection? = null
        @Volatile
        var isRunning = false
        
        fun start(context: Context) {
            val intent = Intent(context, FloatingButtonService::class.java)
            context.startForegroundService(intent)
        }
        
        fun stop(context: Context) {
            val intent = Intent(context, FloatingButtonService::class.java)
            context.stopService(intent)
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        
        try {
            lifecycleRegistry = LifecycleRegistry(this)
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
            
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            longPressHandler = Handler(Looper.getMainLooper())
            
            // Create notification channel first
            createNotificationChannel()
            
            // Then start as foreground service
            startForeground()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error creating service", e)
            stopSelf()
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            // Check overlay permission before showing button
            if (!Settings.canDrawOverlays(this)) {
                Log.e(TAG, "Overlay permission not granted, stopping service")
                stopSelf()
                return START_NOT_STICKY
            }
            
            if (!isRunning) {
                showFloatingButton()
                isRunning = true
                lifecycleRegistry.currentState = Lifecycle.State.STARTED
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onStartCommand", e)
            stopSelf()
        }
        return START_STICKY
    }
    
    private fun startForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sleepy Agent")
            .setContentText("Floating button active")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
        
        startForeground(NOTIFICATION_ID, notification)
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                "Floating Button",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun showFloatingButton() {
        try {
            val buttonSize = 150 // dp
            val sizePx = (buttonSize * resources.displayMetrics.density).toInt()
            
            // Create floating button view
            floatingButton = ImageView(this).apply {
                setImageResource(R.drawable.ic_launcher_foreground)
                val drawable = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ContextCompat.getColor(this@FloatingButtonService, android.R.color.white))
                }
                background = drawable
                setPadding(20, 20, 20, 20)
                elevation = 10f
                
                setOnTouchListener { view, event ->
                    handleTouch(event, view)
                    true
                }
            }
            
            // Set up window parameters
            params = WindowManager.LayoutParams(
                sizePx,
                sizePx,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = resources.displayMetrics.widthPixels - sizePx - 50
                y = 200
            }
            
            windowManager.addView(floatingButton, params)
            Log.d(TAG, "Floating button shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show floating button", e)
        }
    }
    
    private fun handleTouch(event: MotionEvent, view: View) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params?.x ?: 0
                initialY = params?.y ?: 0
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false
                isLongPress = false
                
                // Start long press timer
                longPressHandler?.postDelayed({
                    if (!isDragging) {
                        isLongPress = true
                        performLongPress()
                    }
                }, LONG_PRESS_DURATION)
            }
            
            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - initialTouchX
                val deltaY = event.rawY - initialTouchY
                
                // Check if we're dragging
                if (!isDragging && (Math.abs(deltaX) > DRAG_THRESHOLD || Math.abs(deltaY) > DRAG_THRESHOLD)) {
                    isDragging = true
                    longPressHandler?.removeCallbacksAndMessages(null)
                }
                
                if (isDragging) {
                    params?.x = (initialX + deltaX).toInt()
                    params?.y = (initialY + deltaY).toInt()
                    windowManager.updateViewLayout(view, params)
                }
            }
            
            MotionEvent.ACTION_UP -> {
                longPressHandler?.removeCallbacksAndMessages(null)
                
                if (!isDragging && !isLongPress) {
                    // Simple tap - open app
                    openMainActivity()
                }
                
                isDragging = false
            }
            
            MotionEvent.ACTION_CANCEL -> {
                longPressHandler?.removeCallbacksAndMessages(null)
                isDragging = false
            }
        }
    }
    
    private fun performLongPress() {
        Log.d(TAG, "Long press detected - taking screenshot")
        takeScreenshot()
    }
    
    private fun takeScreenshot() {
        val projection = mediaProjection ?: run {
            Log.e(TAG, "MediaProjection not available")
            // Fallback: just open app without screenshot
            openMainActivity()
            return
        }
        
        try {
            val displayMetrics = resources.displayMetrics
            val width = displayMetrics.widthPixels
            val height = displayMetrics.heightPixels
            val density = displayMetrics.densityDpi
            
            val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            
            val virtualDisplay = projection.createVirtualDisplay(
                "screenshot",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface, null, null
            )
            
            // Wait for image
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val image = imageReader.acquireLatestImage()
                    if (image != null) {
                        val bitmap = imageToBitmap(image)
                        val screenshotPath = saveBitmap(bitmap)
                        image.close()
                        
                        Log.d(TAG, "Screenshot saved: $screenshotPath")
                        
                        // Open app with screenshot
                        openMainActivityWithImage(screenshotPath)
                    } else {
                        Log.e(TAG, "Failed to acquire image")
                        openMainActivity()
                    }
                    
                    virtualDisplay.release()
                    imageReader.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing screenshot", e)
                    openMainActivity()
                }
            }, 500) // Small delay to ensure capture
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to take screenshot", e)
            openMainActivity()
        }
    }
    
    private fun imageToBitmap(image: android.media.Image): Bitmap {
        val planes = image.planes
        val buffer: ByteBuffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width
        
        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }
    
    private fun saveBitmap(bitmap: Bitmap): String {
        val filename = "screenshot_${System.currentTimeMillis()}.png"
        val file = File(cacheDir, filename)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return file.absolutePath
    }
    
    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("from_floating_button", true)
        }
        startActivity(intent)
    }
    
    private fun openMainActivityWithImage(imagePath: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("from_floating_button", true)
            putExtra("screenshot_path", imagePath)
            putExtra("auto_analyze", true)
        }
        startActivity(intent)
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        isRunning = false
        
        floatingButton?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing floating button", e)
            }
        }
        floatingButton = null
    }
    
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry
}
